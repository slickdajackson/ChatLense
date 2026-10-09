package app.chatlens.asr

import java.io.File

/** Quelle aus einem normalen Ordner (rekursiv). Wird in Tests und fuer manuell kopierte Ordner benutzt. */
class FileVoiceSource(private val root: File, private val maxDepth: Int = 4) : VoiceFileSource {
    override val label: String = "Ordner"
    override fun list(): List<VoiceFile> {
        val out = ArrayList<VoiceFile>()
        fun walk(d: File, depth: Int) {
            for (f in d.listFiles().orEmpty()) {
                if (f.isDirectory && depth < maxDepth) walk(f, depth + 1)
                else if (f.isFile) out.add(VoiceFile(f.absolutePath, f.name, f.lastModified(), f.length()))
            }
        }
        walk(root, 0)
        return out
    }

    override fun read(f: VoiceFile): ByteArray = File(f.id).readBytes()
}

/** Zwischenspeicher fertiger Transkripte als kleine Textdateien im App-Cache (nicht im Backup, loeschbar). */
class FileVoiceCache(private val dir: File) : VoiceTextCache {
    override fun get(key: String): String? = File(dir, "$key.txt").takeIf { it.isFile }?.readText(Charsets.UTF_8)
    override fun put(key: String, text: String) {
        dir.mkdirs()
        File(dir, "$key.txt").writeText(text, Charsets.UTF_8)
    }

    fun clear(): Int = dir.listFiles().orEmpty().count { it.delete() }
    fun count(): Int = dir.listFiles().orEmpty().size
}
