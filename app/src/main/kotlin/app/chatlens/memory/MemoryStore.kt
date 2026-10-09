package app.chatlens.memory

import app.chatlens.match.NameMatcher
import java.io.File
import java.security.MessageDigest

/**
 * Verschluesselte Ablage: eine Datei je Chat im App-internen Speicher. Dateiname ist ein Hash des Chat-Schluessels,
 * der Name steht nur im verschluesselten Inhalt. Kein Backup (allowBackup=false, Datenextraktion ausgeschlossen).
 */
class MemoryStore(private val dir: File, private val crypto: Crypto) {

    init {
        dir.mkdirs()
    }

    private fun file(key: String): File {
        val h = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return File(dir, h.joinToString("") { "%02x".format(it) }.take(32) + EXT)
    }

    fun save(m: ChatMemory) {
        val tmp = File(dir, "tmp-" + System.nanoTime())
        tmp.writeBytes(crypto.encrypt(MemoryCodec.toJson(m).toByteArray(Charsets.UTF_8)))
        val dst = file(m.chatKey)
        if (!tmp.renameTo(dst)) {
            dst.delete()
            if (!tmp.renameTo(dst)) {
                tmp.delete()
                throw java.io.IOException("Gedaechtnis konnte nicht gespeichert werden")
            }
        }
    }

    fun load(key: String): ChatMemory? {
        val f = file(key)
        if (!f.isFile) return null
        return try {
            MemoryCodec.fromJson(String(crypto.decrypt(f.readBytes()), Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    fun loadByTitle(title: String): ChatMemory? = load(NameMatcher.normalize(title))

    fun list(): List<ChatMemory> = (dir.listFiles { f -> f.name.endsWith(EXT) } ?: emptyArray())
        .mapNotNull { f ->
            runCatching { MemoryCodec.fromJson(String(crypto.decrypt(f.readBytes()), Charsets.UTF_8)) }.getOrNull()
        }
        .sortedByDescending { it.updatedAt }

    fun delete(key: String): Boolean = file(key).delete()

    fun deleteAll(): Int {
        var n = 0
        dir.listFiles { f -> f.name.endsWith(EXT) || f.name.startsWith("tmp-") }?.forEach { if (it.delete()) n++ }
        return n
    }

    /** Klartext-Export fuer den Nutzer (JSON). Wird nur auf ausdruecklichen Wunsch erzeugt und geteilt. */
    fun exportJson(key: String? = null): String {
        val items = if (key != null) listOfNotNull(load(key)) else list()
        return org.json.JSONArray(items.map { org.json.JSONObject(MemoryCodec.toJson(it)) }).toString(2)
    }

    private companion object {
        const val EXT = ".mem"
    }
}
