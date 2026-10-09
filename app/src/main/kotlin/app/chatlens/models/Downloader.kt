package app.chatlens.models

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class DownloadCancelled : Exception("Download angehalten")

class DownloadException(message: String, val httpCode: Int = 0, cause: Throwable? = null) : IOException(message, cause)

class ChecksumMismatch(val expected: String, val actual: String) : IOException("Prüfsumme stimmt nicht: erwartet ${expected.take(12)}, erhalten ${actual.take(12)}")

enum class DlPhase { CONNECT, DOWNLOAD, VERIFY }

class DlProgress(val phase: DlPhase, val bytesDone: Long, val total: Long)

class DownloadResult(val file: File, val verified: Boolean, val sha256: String, val resumedFrom: Long)

object Sha256 {
    fun of(file: File, cancel: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 20)
        var done = 0L
        file.inputStream().use { input ->
            while (true) {
                if (cancel()) throw DownloadCancelled()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                onProgress(done)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Download with resume. The partial file is named `<target>.part` and is kept on cancel (pause). Resume via HTTP Range.
 * Redirects are followed by hand (Hugging Face redirects to a CDN), and no access token is sent along.
 * After the download, the size and, if present, the SHA-256 are checked. On a mismatch the partial file is deleted.
 */
object ModelDownloader {
    private const val MAX_REDIRECTS = 6
    private const val BUF = 256 * 1024

    fun partFile(target: File) = File(target.path + ".part")

    fun download(
        url: String,
        target: File,
        expectedSize: Long,
        expectedSha256: String?,
        cancel: () -> Boolean,
        onProgress: (DlProgress) -> Unit,
        open: (URL) -> HttpURLConnection = { (it.openConnection() as HttpURLConnection) },
    ): DownloadResult {
        target.parentFile?.mkdirs()
        val part = partFile(target)
        var start = if (part.isFile) part.length() else 0L
        if (expectedSize > 0 && start > expectedSize) { part.delete(); start = 0L }
        val resumedFrom = start

        if (expectedSize <= 0 || start < expectedSize) {
            onProgress(DlProgress(DlPhase.CONNECT, start, expectedSize))
            start = transfer(url, part, start, expectedSize, cancel, onProgress, open, retriedFresh = false)
        }
        if (expectedSize > 0 && part.length() != expectedSize) {
            part.delete()
            throw DownloadException("Dateigröße stimmt nicht: erwartet $expectedSize, erhalten ${part.length()}. Teildatei gelöscht.")
        }
        var sha = ""
        var verified = false
        if (!expectedSha256.isNullOrBlank()) {
            sha = Sha256.of(part, cancel) { onProgress(DlProgress(DlPhase.VERIFY, it, part.length())) }
            if (!sha.equals(expectedSha256, ignoreCase = true)) {
                part.delete()
                throw ChecksumMismatch(expectedSha256, sha)
            }
            verified = true
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        return DownloadResult(target, verified, sha, resumedFrom)
    }

    private fun connect(url: String, rangeStart: Long, open: (URL) -> HttpURLConnection): HttpURLConnection {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val c = open(URL(current))
            c.instanceFollowRedirects = false
            c.connectTimeout = 30_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "ChatLens-ModelDownload")
            c.setRequestProperty("Accept-Encoding", "identity")
            if (rangeStart > 0) c.setRequestProperty("Range", "bytes=$rangeStart-")
            val code = try { c.responseCode } catch (e: IOException) { throw DownloadException("Verbindung fehlgeschlagen: ${e.message}", 0, e) }
            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val loc = c.getHeaderField("Location") ?: throw DownloadException("Umleitung ohne Ziel", code)
                current = URL(URL(current), loc).toString()
                c.disconnect()
                return@repeat
            }
            return c
        }
        throw DownloadException("Zu viele Umleitungen")
    }

    private fun transfer(
        url: String, part: File, startIn: Long, expectedSize: Long,
        cancel: () -> Boolean, onProgress: (DlProgress) -> Unit, open: (URL) -> HttpURLConnection, retriedFresh: Boolean,
    ): Long {
        var start = startIn
        val c = connect(url, start, open)
        try {
            val code = c.responseCode
            when {
                code == 416 -> {
                    // Range not acceptable: the partial file does not match the file on the server. Start fresh once.
                    c.disconnect()
                    if (retriedFresh || start == 0L) throw DownloadException("Server lehnt den Bereich ab (HTTP 416).", 416)
                    part.delete()
                    return transfer(url, part, 0L, expectedSize, cancel, onProgress, open, retriedFresh = true)
                }
                code == 401 || code == 403 -> throw DownloadException("Zugriff verweigert (HTTP $code). Das Modell ist vermutlich gated und braucht ein Hugging-Face-Konto.", code)
                code == 404 -> throw DownloadException("Datei nicht gefunden (HTTP 404). Der Katalog ist vermutlich veraltet.", code)
                code != 200 && code != 206 -> throw DownloadException("Unerwartete Antwort vom Server (HTTP $code).", code)
            }
            val append: Boolean
            val total: Long
            if (code == 206) {
                val cr = c.getHeaderField("Content-Range") ?: ""
                val m = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""").find(cr)
                val from = m?.groupValues?.get(1)?.toLongOrNull()
                if (from == null || from != start) throw DownloadException("Server liefert einen anderen Bereich als angefragt ($cr).")
                total = m.groupValues[3].toLongOrNull() ?: expectedSize
                append = true
            } else {
                // Server ignores Range: start from the beginning
                if (start > 0) { part.delete(); start = 0L }
                total = c.contentLengthLong.takeIf { it > 0 } ?: expectedSize
                append = false
            }
            if (expectedSize > 0 && total > 0 && total != expectedSize) {
                throw DownloadException("Server meldet $total Byte, der Katalog erwartet $expectedSize. Datei hat sich geändert.")
            }
            var done = start
            RandomAccessFile(part, "rw").use { raf ->
                if (append) raf.seek(start) else raf.setLength(0)
                c.inputStream.use { input ->
                    val buf = ByteArray(BUF)
                    var lastReport = 0L
                    while (true) {
                        if (cancel()) { raf.fd.sync(); throw DownloadCancelled() }
                        val n = try { input.read(buf) } catch (e: IOException) { throw DownloadException("Verbindung unterbrochen: ${e.message}", 0, e) }
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastReport > 200_000_000L) { onProgress(DlProgress(DlPhase.DOWNLOAD, done, total)); lastReport = now }
                    }
                }
            }
            onProgress(DlProgress(DlPhase.DOWNLOAD, done, total))
            // Connection closed before the end: keep the partial file, resume is possible
            if (total > 0 && done < total) throw DownloadException("Verbindung vor dem Ende geschlossen ($done von $total Byte). Fortsetzen ist möglich.")
            return done
        } finally {
            c.disconnect()
        }
    }
}
