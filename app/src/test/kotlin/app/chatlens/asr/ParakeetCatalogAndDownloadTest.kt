package app.chatlens.asr

import app.chatlens.models.DlPhase
import app.chatlens.models.DlProgress
import app.chatlens.models.DownloadResult
import app.chatlens.models.ModelAdvisor
import app.chatlens.models.ModelCatalog
import app.chatlens.models.ModelFormat
import app.chatlens.models.ModelKind
import app.chatlens.models.MultiFileDownload
import app.chatlens.models.DeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ParakeetCatalogAndDownloadTest {
    @get:Rule val tmp = TemporaryFolder()

    private val entry by lazy {
        val json = File("src/main/assets/model-catalog.json").readText()
        ModelCatalog.parse(json).byId("parakeet-tdt-0.6b-v3")!!
    }

    @Test
    fun catalogEntryIsCompleteAndDownloadable() {
        val e = entry
        assertEquals(ModelKind.ASR, e.kind); assertEquals(ModelFormat.SHERPA_ONNX, e.format)
        assertTrue(e.multiFile); assertTrue(e.canDownload); assertFalse(e.gated)
        assertEquals(listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"), e.files.map { it.name })
        assertEquals(AsrModelFiles.ALL.toSet(), e.files.map { it.name }.toSet())
        assertEquals(e.sizeBytes, e.files.sumOf { it.sizeBytes })
        assertEquals(670478772L, e.sizeBytes)
        e.files.forEach { f -> assertTrue(f.name, Regex("[0-9a-f]{64}").matches(f.sha256)) }
        assertEquals(40, e.revision.length)
        assertEquals(
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/${e.revision}/encoder.int8.onnx",
            e.fileUrl(e.files[0]),
        )
        assertTrue(e.license.contains("CC-BY-4.0"))
        assertEquals("acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247", e.files[0].sha256)
        assertEquals(null, e.downloadUrl) // the single-file display does not apply
    }

    @Test
    fun advisorAllowsMultiFileDownloadAndChecksStorage() {
        val ok = ModelAdvisor.downloadCheck(entry, DeviceInfo(16000, 8000, 50_000_000_000L, true), 0)
        assertTrue(ok.blockReason, ok.allowed)
        val no = ModelAdvisor.downloadCheck(entry, DeviceInfo(16000, 8000, 500_000_000L, true), 0)
        assertFalse(no.allowed)
    }

    private fun fakeFetcher(log: MutableList<String>, failOn: String? = null) = MultiFileDownload.FileFetcher { url, target, size, sha, _, onProgress ->
        val name = url.substringAfterLast('/')
        log.add(name)
        if (name == failOn) throw app.chatlens.models.DownloadException("kaputt")
        target.writeBytes(ByteArray(size.toInt()) { 1 })
        onProgress(DlProgress(DlPhase.DOWNLOAD, size / 2, size))
        onProgress(DlProgress(DlPhase.DOWNLOAD, size, size))
        onProgress(DlProgress(DlPhase.VERIFY, size, size))
        DownloadResult(target, true, sha, 0)
    }

    private fun small() = entry.copy(
        files = listOf(
            app.chatlens.models.ModelFile("encoder.int8.onnx", 100, "a".repeat(64)), app.chatlens.models.ModelFile("decoder.int8.onnx", 40, "b".repeat(64)),
            app.chatlens.models.ModelFile("joiner.int8.onnx", 30, "c".repeat(64)), app.chatlens.models.ModelFile("tokens.txt", 10, "d".repeat(64)),
        ),
        sizeBytes = 180,
    )

    @Test
    fun downloadsAllFilesInOrderWithTotalProgressAndSkipsCompleteOnes() {
        val e = small(); val dir = File(tmp.root, "m")
        val log = ArrayList<String>()
        val prog = ArrayList<MultiFileDownload.Progress>()
        val r = MultiFileDownload.download(e, dir, { false }, { prog.add(it) }, fakeFetcher(log))
        assertEquals(listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"), log)
        assertTrue(r.allVerified); assertTrue(MultiFileDownload.complete(dir, e))
        assertEquals(180L, prog.last().bytesDone); assertEquals(4, prog.last().fileIndex)
        assertTrue(prog.zipWithNext().all { (a, b) -> b.bytesDone >= a.bytesDone })
        assertTrue(AsrModelFiles.complete(dir))
        // second run: everything is already present
        log.clear()
        val r2 = MultiFileDownload.download(e, dir, { false }, {}, fakeFetcher(log))
        assertTrue(log.isEmpty()); assertEquals(4, r2.skipped.size)
        // one file is missing: only that file is downloaded
        File(dir, "joiner.int8.onnx").delete()
        MultiFileDownload.download(e, dir, { false }, {}, fakeFetcher(log))
        assertEquals(listOf("joiner.int8.onnx"), log)
    }

    @Test
    fun failureKeepsFinishedFilesAndResumeContinues() {
        val e = small(); val dir = File(tmp.root, "m2")
        val log = ArrayList<String>()
        try { MultiFileDownload.download(e, dir, { false }, {}, fakeFetcher(log, failOn = "joiner.int8.onnx")); fail() } catch (_: app.chatlens.models.DownloadException) { }
        assertFalse(MultiFileDownload.complete(dir, e))
        assertEquals(140L, MultiFileDownload.haveBytes(dir, e))
        log.clear()
        MultiFileDownload.download(e, dir, { false }, {}, fakeFetcher(log))
        assertEquals(listOf("joiner.int8.onnx", "tokens.txt"), log)
        assertTrue(MultiFileDownload.complete(dir, e))
        MultiFileDownload.deleteAll(dir, e)
        assertFalse(dir.exists())
    }

    @Test
    fun partFilesCountAsProgress() {
        val e = small(); val dir = File(tmp.root, "m3"); dir.mkdirs()
        File(dir, "encoder.int8.onnx.part").writeBytes(ByteArray(60))
        assertEquals(60L, MultiFileDownload.haveBytes(dir, e))
        assertNotNull(e.files.first())
    }

    @Test
    fun verifyAllReportsWrongFiles() {
        val data = File(tmp.root, "m4"); data.mkdirs()
        val sha = { s: String -> java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
        File(data, "encoder.int8.onnx").writeText("eins"); File(data, "decoder.int8.onnx").writeText("zwei")
        val e = entry.copy(files = listOf(
            app.chatlens.models.ModelFile("encoder.int8.onnx", 4, sha("eins")), app.chatlens.models.ModelFile("decoder.int8.onnx", 4, sha("drei")),
            app.chatlens.models.ModelFile("tokens.txt", 1, sha("x")),
        ))
        assertEquals(listOf("decoder.int8.onnx", "tokens.txt"), MultiFileDownload.verifyAll(data, e))
    }
}
