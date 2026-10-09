package app.chatlens.models

import java.io.File

/**
 * Laedt ein mehrteiliges Modell (Spracherkennung) nacheinander in einen Ordner `<modelle>/<id>/`.
 * Jede Datei hat eigene Groesse und SHA-256 und wird wie bei einteiligen Modellen mit Fortsetzen geladen.
 * Bereits vollstaendige Dateien (richtige Groesse) werden uebersprungen; eine Pruefsumme wird dann nur beim Laden berechnet,
 * nicht erneut (Hinweis: ein Nachpruefen gibt es ueber [verifyAll]).
 */
object MultiFileDownload {
    /** Funktion, die eine Datei laedt. Austauschbar, damit der Ablauf ohne Netz getestet werden kann. */
    fun interface FileFetcher {
        fun fetch(url: String, target: File, size: Long, sha256: String, cancel: () -> Boolean, onProgress: (DlProgress) -> Unit): DownloadResult
    }

    val real = FileFetcher { url, target, size, sha, cancel, onProgress -> ModelDownloader.download(url, target, size, sha, cancel, onProgress) }

    class Progress(val file: String, val phase: DlPhase, val bytesDone: Long, val bytesTotal: Long, val fileIndex: Int, val fileCount: Int)

    class Result(val dir: File, val downloaded: List<String>, val skipped: List<String>, val allVerified: Boolean)

    fun dirFor(base: File, e: ModelEntry) = File(base, e.id)

    /** Bytes, die schon vorhanden sind: vollstaendige Dateien plus Teildateien. */
    fun haveBytes(dir: File, e: ModelEntry): Long = e.files.sumOf { f ->
        val t = File(dir, f.name)
        if (t.isFile && t.length() == f.sizeBytes) f.sizeBytes else File(dir, f.name + ".part").takeIf { it.isFile }?.length()?.coerceAtMost(f.sizeBytes) ?: 0L
    }

    fun complete(dir: File, e: ModelEntry): Boolean = e.files.isNotEmpty() && e.files.all { f -> File(dir, f.name).let { it.isFile && it.length() == f.sizeBytes } }

    fun download(
        e: ModelEntry, dir: File, cancel: () -> Boolean, onProgress: (Progress) -> Unit,
        fetcher: FileFetcher = real,
    ): Result {
        require(e.multiFile) { "Kein mehrteiliges Modell" }
        dir.mkdirs()
        val total = e.sizeBytes.takeIf { it > 0 } ?: e.files.sumOf { it.sizeBytes }
        val downloaded = ArrayList<String>()
        val skipped = ArrayList<String>()
        var before = 0L
        var verified = true
        e.files.forEachIndexed { i, f ->
            val target = File(dir, f.name)
            if (target.isFile && target.length() == f.sizeBytes) {
                skipped.add(f.name)
                before += f.sizeBytes
                onProgress(Progress(f.name, DlPhase.DOWNLOAD, before, total, i + 1, e.files.size))
                return@forEachIndexed
            }
            if (target.exists()) target.delete()
            val base = before
            val res = fetcher.fetch(e.fileUrl(f), target, f.sizeBytes, f.sha256, cancel) { p ->
                val done = if (p.phase == DlPhase.VERIFY) base + f.sizeBytes else base + p.bytesDone
                onProgress(Progress(f.name, p.phase, done, total, i + 1, e.files.size))
            }
            if (!res.verified) verified = false
            downloaded.add(f.name)
            before += f.sizeBytes
        }
        return Result(dir, downloaded, skipped, verified)
    }

    /** Berechnet die SHA-256 aller Dateien neu und nennt die Namen, die nicht stimmen (leer = alles in Ordnung). */
    fun verifyAll(dir: File, e: ModelEntry, cancel: () -> Boolean = { false }): List<String> =
        e.files.filter { f -> val t = File(dir, f.name); !t.isFile || !Sha256.of(t, cancel).equals(f.sha256, ignoreCase = true) }.map { it.name }

    fun deleteAll(dir: File, e: ModelEntry) {
        e.files.forEach { f -> File(dir, f.name).delete(); File(dir, f.name + ".part").delete() }
        dir.delete()
    }
}
