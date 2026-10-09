package app.chatlens

import app.chatlens.memory.AesGcmCrypto
import app.chatlens.memory.ChatMemory
import app.chatlens.memory.CryptoException
import app.chatlens.memory.MemoryStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.AlgorithmParameters
import java.security.InvalidAlgorithmParameterException
import java.security.Key
import java.security.Provider
import java.security.SecureRandom
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Reproduces the Android Keystore rule: on encrypt the caller must not supply an IV
 * (otherwise InvalidAlgorithmParameterException "Caller-provided IV not permitted"). The keystore generates the IV itself.
 * That makes the bug from the device log (version 0.2.2) reproducible: the old implementation fails in this test.
 */
class KeystoreRuleTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Cipher service that uses the JVM AES/GCM but enforces the keystore rule. */
    class KeystoreLikeGcm : CipherSpi() {
        private val d: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private var encrypting = false
        override fun engineSetMode(mode: String?) {}
        override fun engineSetPadding(padding: String?) {}
        override fun engineGetBlockSize(): Int = d.blockSize
        override fun engineGetOutputSize(inputLen: Int): Int = d.getOutputSize(inputLen)
        override fun engineGetIV(): ByteArray? = d.iv
        override fun engineGetParameters(): AlgorithmParameters? = d.parameters
        override fun engineInit(opmode: Int, key: Key?, random: SecureRandom?) {
            encrypting = opmode == Cipher.ENCRYPT_MODE
            d.init(opmode, key, random ?: SecureRandom())
        }
        override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameterSpec?, random: SecureRandom?) {
            encrypting = opmode == Cipher.ENCRYPT_MODE || opmode == Cipher.WRAP_MODE
            if (encrypting && params != null) throw InvalidAlgorithmParameterException("Caller-provided IV not permitted")
            d.init(opmode, key, params, random ?: SecureRandom())
        }
        override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameters?, random: SecureRandom?) {
            if (opmode == Cipher.ENCRYPT_MODE && params != null) throw InvalidAlgorithmParameterException("Caller-provided IV not permitted")
            d.init(opmode, key, params, random ?: SecureRandom())
        }
        override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray? = d.update(input, inputOffset, inputLen)
        override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int =
            d.update(input, inputOffset, inputLen, output, outputOffset)
        override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray = d.doFinal(input, inputOffset, inputLen)
        override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int =
            d.doFinal(input, inputOffset, inputLen, output, outputOffset)
    }

    private val provider = object : Provider("KeystoreLike", 1.0, "Test") {
        init { put("Cipher.AES/GCM/NoPadding", KeystoreLikeGcm::class.java.name) }
    }

    private fun strictCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding", provider)

    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun simulatorRejectsCallerProvidedIvOnEncrypt() {
        val c = strictCipher()
        try {
            c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, ByteArray(12)))
            fail("Die Nachbildung muss einen vorgegebenen IV ablehnen")
        } catch (e: InvalidAlgorithmParameterException) {
            assertTrue(e.message!!.contains("Caller-provided IV not permitted"))
        }
    }

    @Test fun appCryptoWorksUnderTheKeystoreRule() {
        val k = key()
        val c = AesGcmCrypto({ strictCipher() }) { k }
        val blob = c.encrypt("Gedaechtnis".toByteArray())
        assertEquals("Gedaechtnis", String(c.decrypt(blob)))
        assertEquals(1 + 12 + "Gedaechtnis".length + 16, blob.size)
        // second run: a fresh IV
        val blob2 = c.encrypt("Gedaechtnis".toByteArray())
        assertFalse(blob.copyOfRange(1, 13).contentEquals(blob2.copyOfRange(1, 13)))
    }

    @Test fun storedIvIsTheOneTheCipherProduced() {
        val k = key()
        val c = AesGcmCrypto({ strictCipher() }) { k }
        val blob = c.encrypt("x".toByteArray())
        val iv = blob.copyOfRange(1, 13)
        val ref = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv)) }
        assertArrayEquals("x".toByteArray(), ref.doFinal(blob, 13, blob.size - 13))
    }

    @Test fun oldImplementationWouldHaveFailedLikeOnTheDevice() {
        // This is how 0.2.2 encrypted: it generated the IV itself and then passed it in. Under the keystore rule that throws.
        val c = strictCipher()
        try {
            c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, ByteArray(12).also { SecureRandom().nextBytes(it) }))
            fail("erwartet")
        } catch (e: InvalidAlgorithmParameterException) {
            assertEquals("Caller-provided IV not permitted", e.message)
        }
    }

    @Test fun memoryStoreAndQueueStyleBlobsWorkUnderTheRule() {
        val k = key()
        val crypto = AesGcmCrypto({ strictCipher() }) { k }
        val dir = tmp.newFolder("mem-ks")
        val store = MemoryStore(dir, crypto)
        store.save(ChatMemory("anna", "Anna", profile = "Kollegin", updatedAt = 5))
        assertEquals("Kollegin", store.load("anna")!!.profile)
        assertNotNull(store.list().firstOrNull())
        val raw = dir.listFiles()!!.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertFalse(raw.contains("Kollegin"))
        // a wrong key and tampering are still rejected
        val other = AesGcmCrypto({ strictCipher() }) { key() }
        try { other.decrypt(crypto.encrypt("a".toByteArray())); fail() } catch (e: CryptoException) {}
    }

    @Test fun noEncryptInitInSourcesPassesParameters() {
        // Source guard: main must not contain init(ENCRYPT_MODE, key, <parameters>), not even split across lines.
        val root = File("src/main/kotlin")
        val bad = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            val t = f.readText()
            Regex("""\.init\(\s*Cipher\.ENCRYPT_MODE\s*,[^)]*\)""").findAll(t).forEach { m ->
                val args = m.value.removePrefix(".init(").removeSuffix(")")
                // allowed: exactly two arguments (mode, key). Parentheses inside the key expression do not count as a comma.
                val depth0 = args.count { it == ',' }
                if (depth0 >= 2 || m.value.contains("GCMParameterSpec") || m.value.contains("IvParameterSpec")) bad.add(f.name + ": " + m.value)
            }
            // also: GCMParameterSpec may appear only with DECRYPT_MODE
            if (t.contains("ENCRYPT_MODE") && t.contains("GCMParameterSpec")) {
                val lines = t.lines()
                lines.forEachIndexed { i, l -> if (l.contains("ENCRYPT_MODE") && !l.contains("DECRYPT_MODE") && l.contains("GCMParameterSpec")) bad.add(f.name + ":" + (i + 1)) }
            }
        }
        assertTrue("Verbotene Verschluesselungs-Initialisierung: $bad", bad.isEmpty())
        assertTrue(File("src/main/kotlin/app/chatlens/data/SecretStore.kt").readText().contains("c.init(Cipher.ENCRYPT_MODE, key())"))
        assertTrue(File("src/main/kotlin/app/chatlens/memory/Crypto.kt").readText().contains("c.init(Cipher.ENCRYPT_MODE, key())"))
    }
}
