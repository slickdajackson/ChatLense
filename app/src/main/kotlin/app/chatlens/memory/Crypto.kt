package app.chatlens.memory

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface Crypto {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

class CryptoException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

/**
 * AES-256-GCM. Format: version (1 byte) + IV (12 bytes) + ciphertext with tag. The key comes from outside
 * (on the device from the Android Keystore, in tests a random key). A tampered blob is rejected.
 *
 * IMPORTANT (Keystore rule): when encrypting, the caller must NOT supply an IV. The Android Keystore generates the IV itself
 * (setRandomizedEncryptionRequired is the default) and otherwise throws InvalidAlgorithmParameterException "Caller-provided IV not permitted".
 * So: init(ENCRYPT_MODE, key) without parameters, then the IV is read from cipher.iv and stored with the blob. Only when decrypting
 * is the stored IV passed via GCMParameterSpec. [newCipher] is replaceable so tests can reproduce the Keystore rule.
 */
class AesGcmCrypto(
    private val newCipher: () -> Cipher = { Cipher.getInstance("AES/GCM/NoPadding") },
    private val key: () -> SecretKey,
) : Crypto {

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = newCipher()
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv ?: throw CryptoException("Cipher lieferte keinen IV")
        if (iv.size != IV_LEN) throw CryptoException("Unerwartete IV-Laenge ${iv.size}")
        val ct = c.doFinal(plain)
        return byteArrayOf(VERSION) + iv + ct
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size < 1 + IV_LEN + 16 || blob[0] != VERSION) throw CryptoException("Unbekanntes Format")
        return try {
            val c = newCipher()
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(1, 1 + IV_LEN)))
            c.doFinal(blob, 1 + IV_LEN, blob.size - 1 - IV_LEN)
        } catch (e: Exception) {
            throw CryptoException("Entschluesselung fehlgeschlagen (falscher Schluessel oder manipulierte Datei)", e)
        }
    }

    private companion object {
        const val VERSION: Byte = 1
        const val IV_LEN = 12
    }
}
