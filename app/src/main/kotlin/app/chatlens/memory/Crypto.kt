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
 * AES-256-GCM. Format: Version (1 Byte) + IV (12 Byte) + Chiffrat mit Tag. Der Schluessel kommt von aussen
 * (auf dem Geraet aus dem Android Keystore, in Tests ein Zufallsschluessel). Ein manipulierter Blob wird abgelehnt.
 *
 * WICHTIG (Keystore-Regel): Beim Verschluesseln darf der Aufrufer KEINEN IV vorgeben. Der Android Keystore erzeugt den IV selbst
 * (setRandomizedEncryptionRequired ist Standard) und wirft sonst InvalidAlgorithmParameterException "Caller-provided IV not permitted".
 * Darum: init(ENCRYPT_MODE, key) ohne Parameter, der IV wird danach aus cipher.iv gelesen und mitgespeichert. Nur beim Entschluesseln
 * wird der gespeicherte IV per GCMParameterSpec uebergeben. [newCipher] ist austauschbar, damit Tests die Keystore-Regel nachbilden koennen.
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
