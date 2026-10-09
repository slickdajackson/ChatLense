package app.chatlens.models

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import java.security.MessageDigest
import java.util.Random

/** Download mit Range, Pause/Fortsetzen, Pruefsumme gegen einen kleinen lokalen HTTP-Server (ServerSocket). Kein Netzzugriff. */
class DownloaderTest {
    private lateinit var server: ServerSocket
    private lateinit var dir: File
    private val data = ByteArray(3_000_000).also { Random(42).nextBytes(it) }
    private val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    @Volatile private var honorRange = true
    @Volatile private var status = 200
    @Volatile private var truncateAt = -1
    @Volatile private var lastRange: String? = null
    private val base get() = "http://127.0.0.1:${server.localPort}"

    @Before fun up() {
        dir = File.createTempFile("dltest", "").also { it.delete(); it.mkdirs() }
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { try { handle(s) } catch (_: Exception) {} finally { runCatching { s.close() } } }
            }
        }
    }

    @After fun down() { server.close(); dir.deleteRecursively() }

    private fun handle(s: Socket) {
        val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
        val path = r.readLine().split(" ")[1]
        var range: String? = null
        while (true) {
            val l = r.readLine() ?: break
            if (l.isEmpty()) break
            if (l.startsWith("Range:", ignoreCase = true)) range = l.substringAfter(":").trim()
        }
        val out = s.getOutputStream()
        fun head(code: Int, text: String, extra: String = "", len: Long = 0) {
            out.write("HTTP/1.1 $code $text\r\n${extra}Content-Length: $len\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
        }
        if (path == "/f") { head(302, "Found", "Location: /data\r\n"); return }
        if (status != 200) { head(status, "Err"); return }
        lastRange = range
        var from = 0
        var code = 200
        var extra = "Accept-Ranges: bytes\r\n"
        if (range != null && honorRange) {
            from = range.removePrefix("bytes=").removeSuffix("-").toInt()
            if (from >= data.size) { head(416, "Range Not Satisfiable"); return }
            code = 206
            extra += "Content-Range: bytes $from-${data.size - 1}/${data.size}\r\n"
        }
        head(code, if (code == 206) "Partial Content" else "OK", extra, (data.size - from).toLong())
        var pos = from
        val end = if (truncateAt in 1 until data.size) truncateAt else data.size
        while (pos < end) {
            val n = minOf(64 * 1024, end - pos)
            out.write(data, pos, n); out.flush(); pos += n
        }
    }

    private fun dl(target: File, size: Long = data.size.toLong(), expectSha: String? = sha, cancel: () -> Boolean = { false }, on: (DlProgress) -> Unit = {}) =
        ModelDownloader.download("$base/f", target, size, expectSha, cancel, on)

    @Test fun fullDownloadFollowsRedirectAndVerifies() {
        val t = File(dir, "m.litertlm")
        val seen = ArrayList<DlPhase>()
        val r = dl(t, on = { seen.add(it.phase) })
        assertTrue(r.verified); assertEquals(sha, r.sha256); assertEquals(0L, r.resumedFrom)
        assertArrayEquals(data, t.readBytes())
        assertFalse(ModelDownloader.partFile(t).exists())
        assertTrue(seen.contains(DlPhase.DOWNLOAD)); assertTrue(seen.contains(DlPhase.VERIFY))
    }

    @Test fun pauseThenResumeAppendsAndVerifies() {
        val t = File(dir, "m.litertlm")
        val partF = ModelDownloader.partFile(t)
        try {
            // Pause, sobald mindestens 1 MB auf der Platte liegt (die Abbruchfrage laeuft vor jedem Lesen)
            dl(t, cancel = { partF.length() >= 1_000_000 })
            fail("Abbruch erwartet")
        } catch (_: DownloadCancelled) {}
        val part = ModelDownloader.partFile(t)
        assertTrue(part.isFile)
        val partLen = part.length()
        assertTrue(partLen in 1 until data.size.toLong())
        assertFalse(t.exists())
        val r = dl(t)
        assertEquals(partLen, r.resumedFrom)
        assertEquals("bytes=$partLen-", lastRange)
        assertTrue(r.verified)
        assertArrayEquals(data, t.readBytes())
    }

    @Test fun serverIgnoringRangeRestartsFromZero() {
        val t = File(dir, "m.litertlm")
        ModelDownloader.partFile(t).writeBytes(data.copyOf(500_000))
        honorRange = false
        val r = dl(t)
        assertTrue(r.verified)
        assertArrayEquals(data, t.readBytes())
    }

    @Test fun checksumMismatchDeletesPart() {
        val t = File(dir, "m.litertlm")
        try { dl(t, expectSha = "0".repeat(64)); fail("erwartet") } catch (e: ChecksumMismatch) { assertEquals(sha, e.actual) }
        assertFalse(t.exists()); assertFalse(ModelDownloader.partFile(t).exists())
    }

    @Test fun sizeMismatchWithCatalogThrows() {
        val t = File(dir, "m.litertlm")
        try { dl(t, size = data.size.toLong() + 10); fail("erwartet") } catch (_: DownloadException) {}
        assertFalse(t.exists())
    }

    @Test fun forbiddenGivesGatedHint() {
        status = 403
        try { dl(File(dir, "m.litertlm")); fail("erwartet") } catch (e: DownloadException) {
            assertEquals(403, e.httpCode); assertTrue(e.message!!.contains("gated"))
        }
    }

    @Test fun notFoundGivesStaleCatalogHint() {
        status = 404
        try { dl(File(dir, "m.litertlm")); fail("erwartet") } catch (e: DownloadException) {
            assertEquals(404, e.httpCode); assertTrue(e.message!!.contains("veraltet"))
        }
    }

    @Test fun truncatedStreamKeepsPartFile() {
        val t = File(dir, "m.litertlm")
        truncateAt = 1_000_000
        try { dl(t); fail("erwartet") } catch (_: java.io.IOException) {}
        val part = ModelDownloader.partFile(t)
        assertTrue(part.isFile && part.length() > 0 && part.length() < data.size)
        assertFalse(t.exists())
        truncateAt = -1
        val r = dl(t)
        assertTrue(r.verified); assertArrayEquals(data, t.readBytes())
    }

    @Test fun sha256OfMatchesKnownVector() {
        val f = File(dir, "abc"); f.writeText("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.of(f))
    }
}
