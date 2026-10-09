package app.chatlens.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.chatlens.auto.AutoQueue
import app.chatlens.checkup.CheckupCodec
import app.chatlens.checkup.CheckupStored
import app.chatlens.memory.AesGcmCrypto
import app.chatlens.memory.ChatMemory
import app.chatlens.memory.Crypto
import app.chatlens.prompts.PromptBook
import app.chatlens.prompts.PromptCodec
import app.chatlens.memory.MemoryStore
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Zugang zum verschluesselten Gedaechtnis auf dem Geraet. Schluessel: AES-256 im Android Keystore (nicht auslesbar),
 * Dateien: app-interner Speicher, kein Cloud-Backup (allowBackup=false, Extraktionsregeln schliessen alles aus).
 */
class MemoryRepo private constructor(ctx: Context) {
    private val crypto: Crypto = AesGcmCrypto { keystoreKey() }
    val store = MemoryStore(File(ctx.applicationContext.filesDir, "memory"), crypto)
    private val filesDir = ctx.applicationContext.filesDir

    /** Gedaechtnis je Messenger getrennt: WhatsApp nutzt den bisherigen Ordner, jeder weitere einen eigenen (ab 0.3.0). */
    fun storeFor(adapter: app.chatlens.messenger.MessengerAdapter): MemoryStore =
        if (adapter.id == app.chatlens.messenger.WhatsAppAdapter.ID) store else MemoryStore(File(filesDir, adapter.memoryDirName), crypto)

    private val queueFile = File(ctx.applicationContext.filesDir, "auto-queue.dat")
    private val checkupFile = File(ctx.applicationContext.filesDir, "checkup.dat")
    private val promptFile = File(ctx.applicationContext.filesDir, "prompts.dat")
    private val ichFile = File(ctx.applicationContext.filesDir, "ich.dat")
    private val selfFile = File(ctx.applicationContext.filesDir, "selfanalysis.dat")

    fun loadByTitle(title: String): ChatMemory? = store.loadByTitle(title)
    fun save(m: ChatMemory) = store.save(m)
    fun list(): List<ChatMemory> = store.list()
    fun exportJson(key: String? = null): String = store.exportJson(key)
    fun delete(key: String) = store.delete(key)
    fun deleteAll(): Int {
        var n = store.deleteAll()
        for (a in app.chatlens.messenger.MessengerRegistry.all) if (a.id != app.chatlens.messenger.WhatsAppAdapter.ID) n += storeFor(a).deleteAll()
        queueFile.delete()
        checkupFile.delete()
        promptFile.delete()
        ichFile.delete()
        selfFile.delete()
        return n
    }

    fun saveQueue(q: AutoQueue) {
        queueFile.writeBytes(crypto.encrypt(q.toJson().toByteArray(Charsets.UTF_8)))
    }

    fun loadQueue(): AutoQueue? {
        if (!queueFile.isFile) return null
        return runCatching { AutoQueue.fromJson(String(crypto.decrypt(queueFile.readBytes()), Charsets.UTF_8)) }.getOrNull()
    }

    /** Checkup-Auswahl: nur Namen, verschluesselt wie das Gedaechtnis. */
    fun saveCheckup(s: CheckupStored) {
        checkupFile.writeBytes(crypto.encrypt(CheckupCodec.toJson(s).toByteArray(Charsets.UTF_8)))
    }

    fun loadCheckup(): CheckupStored? {
        if (!checkupFile.isFile) return null
        return runCatching { CheckupCodec.fromJson(String(crypto.decrypt(checkupFile.readBytes()), Charsets.UTF_8)) }.getOrNull()
    }

    /** Prompt-Buch ("Analysieren" mit eigenem Prompt): Vorlagen und zuletzt genutzte Prompts, verschluesselt wie das Gedaechtnis. */
    fun savePromptBook(b: PromptBook) {
        promptFile.writeBytes(crypto.encrypt(PromptCodec.toJson(b).toByteArray(Charsets.UTF_8)))
    }

    fun loadPromptBook(): PromptBook? {
        if (!promptFile.isFile) return null
        return runCatching { PromptCodec.fromJson(String(crypto.decrypt(promptFile.readBytes()), Charsets.UTF_8)) }.getOrNull()
    }

    /** Ich-Profil: eigener verschluesselter Speicher, getrennt von den Chat-Profilen. Loeschen: [deleteIch] oder "Gedaechtnis loeschen". */
    @Synchronized fun saveIch(p: app.chatlens.memory.IchProfile) {
        ichFile.writeBytes(crypto.encrypt(app.chatlens.memory.IchCodec.toJson(p).toByteArray(Charsets.UTF_8)))
    }

    @Synchronized fun loadIch(): app.chatlens.memory.IchProfile =
        if (!ichFile.isFile) app.chatlens.memory.IchProfile()
        else runCatching { app.chatlens.memory.IchCodec.fromJson(String(crypto.decrypt(ichFile.readBytes()), Charsets.UTF_8)) }.getOrDefault(app.chatlens.memory.IchProfile())

    fun deleteIch() { ichFile.delete() }

    /** Teilergebnisse und Vorschlag der Selbstanalyse (nur abstrakte Merkmale, keine Chatnamen), verschluesselt. */
    @Synchronized fun saveSelf(json: String) { selfFile.writeBytes(crypto.encrypt(json.toByteArray(Charsets.UTF_8))) }
    @Synchronized fun loadSelf(): Pair<List<app.chatlens.memory.SelfPartial>, app.chatlens.memory.IchProfile?>? =
        if (!selfFile.isFile) null else runCatching { app.chatlens.memory.SelfCodec.partialsFromJson(String(crypto.decrypt(selfFile.readBytes()), Charsets.UTF_8)) }.getOrNull()
    fun deleteSelf() { selfFile.delete() }

    fun clearQueue() {
        queueFile.delete()
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        private const val ALIAS = "chatlens_memory_key"

        @Volatile
        private var inst: MemoryRepo? = null

        fun get(ctx: Context): MemoryRepo = inst ?: synchronized(this) { inst ?: MemoryRepo(ctx).also { inst = it } }
    }
}
