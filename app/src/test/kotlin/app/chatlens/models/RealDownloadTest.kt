package app.chatlens.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Echter Download der kleinsten Katalogdatei von Hugging Face (345 MB). Standardmaessig uebersprungen,
 * laeuft nur mit Umgebungsvariable CHATLENS_NET_TEST=1. Prueft Redirect, Pause, Fortsetzen und SHA-256 gegen den echten Server.
 */
class RealDownloadTest {
    @Test fun smallestFileRealDownloadPauseResume() {
        assumeTrue(System.getenv("CHATLENS_NET_TEST") == "1")
        val cat = ModelCatalog.parse(File("src/main/assets/model-catalog.json").readText())
        val e = cat.byId("qwen3-0.6b-int4gpu")!!
        val dir = File(System.getProperty("java.io.tmpdir"), "chatlens-realdl").apply { deleteRecursively(); mkdirs() }
        val t = File(dir, e.file!!)
        val part = ModelDownloader.partFile(t)
        try {
            ModelDownloader.download(e.downloadUrl!!, t, e.sizeBytes, e.sha256, { part.length() >= 100_000_000L }, {})
            throw AssertionError("Abbruch erwartet")
        } catch (_: DownloadCancelled) {}
        val partLen = part.length()
        println("REALDL pause bei $partLen Byte")
        assertTrue(partLen in 100_000_000L until e.sizeBytes)
        val r = ModelDownloader.download(e.downloadUrl!!, t, e.sizeBytes, e.sha256, { false }, {})
        println("REALDL fertig: ${t.length()} Byte, sha=${r.sha256}, verified=${r.verified}, resumedFrom=${r.resumedFrom}")
        assertEquals(partLen, r.resumedFrom)
        assertTrue(r.verified)
        assertEquals(e.sizeBytes, t.length())
        dir.deleteRecursively()
    }
}
