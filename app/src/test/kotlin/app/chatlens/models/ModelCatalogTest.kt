package app.chatlens.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Catalog invariants (against the real asset file) and recommendation logic. JVM only. */
class ModelCatalogTest {
    private val jsonFile = File("src/main/assets/model-catalog.json")
    private val cat: ModelCatalog.Parsed by lazy { ModelCatalog.parse(jsonFile.readText()) }

    private fun dev(total: Long = 12_000, avail: Long = 6_000, storageGb: Double = 50.0, unmetered: Boolean? = true) =
        DeviceInfo(total, avail, (storageGb * 1e9).toLong(), unmetered)

    private fun m(id: String) = cat.byId(id) ?: error("missing: $id")

    // ---------- Catalog ----------

    @Test fun assetExistsAndParses() {
        assertTrue("Asset nicht gefunden: ${jsonFile.absolutePath}", jsonFile.isFile)
        assertTrue(cat.models.size >= 8)
        assertTrue(cat.checkedAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    @Test fun idsUnique() {
        assertEquals(cat.models.size, cat.models.map { it.id }.toSet().size)
    }

    @Test fun defaultIsGemmaE4bStandard() {
        val d = m(ModelCatalog.DEFAULT_ID)
        assertEquals("gemma-4-e4b", d.id)
        assertEquals(ModelStatus.STANDARD, d.status)
        assertEquals(1, cat.models.count { it.status == ModelStatus.STANDARD })
        assertEquals("gemma-4-E4B-it.litertlm", d.file)
        assertEquals(3_659_530_240L, d.sizeBytes)
    }

    @Test fun downloadableEntriesHaveChecksumRevisionAndPinnedUrl() {
        val dl = cat.models.filter { it.downloadable && !it.multiFile }
        assertTrue(dl.size >= 8)
        for (e in dl) {
            assertEquals(e.id, 64, e.sha256.length)
            assertTrue(e.id, e.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(e.id, e.revision.matches(Regex("[0-9a-f]{40}")))
            assertTrue(e.id, e.sizeBytes > 100_000_000L)
            assertEquals(ModelFormat.LITERT_LM, e.format)
            assertTrue(e.id, e.file!!.endsWith(".litertlm"))
            assertEquals("https://huggingface.co/${e.repo}/resolve/${e.revision}/${e.file}", e.downloadUrl)
            assertTrue(e.id, e.repo.startsWith("litert-community/"))
            assertFalse(e.id, e.gated)
        }
    }

    @Test fun gatedAndAsrAreLinkOnly() {
        val gated = cat.models.filter { it.gated }
        assertTrue("mindestens ein gated Eintrag", gated.isNotEmpty())
        gated.forEach { assertNull(it.id, it.downloadUrl); assertFalse(it.id, it.downloadable) }
        val asr = cat.models.filter { it.kind == ModelKind.ASR }
        assertTrue(asr.isNotEmpty())
        // from 0.2.6, multi-file ASR entries are downloadable (SHA-256 per file), but without a single-file link
        asr.forEach { assertNull(it.id, it.downloadUrl); assertEquals(ModelFormat.SHERPA_ONNX, it.format); assertTrue(it.id, it.multiFile) }
    }

    @Test fun pageUrlsAreHuggingFace() {
        cat.models.forEach {
            assertTrue(it.id, it.pageUrl.startsWith("https://huggingface.co/"))
            it.links.forEach { l -> assertTrue(l.url, l.url.startsWith("https://")) }
        }
    }

    @Test fun suitabilityInRange() {
        cat.models.forEach { e ->
            listOf(e.suit.classify, e.suit.summary, e.suit.profile).forEach { assertTrue(e.id, it in 0..3) }
        }
    }

    @Test fun qwenEntriesPresent() {
        listOf("qwen3-0.6b-int8", "qwen3-1.7b-int8", "qwen3-4b-int4").forEach { assertNotNull(it, cat.byId(it)) }
        assertEquals(614_236_160L, m("qwen3-0.6b-int8").sizeBytes)
        assertEquals("Qwen3-0.6B.litertlm", m("qwen3-0.6b-int8").file)
    }

    @Test fun noDashesOrEmojiInCatalog() {
        val bad = Regex("[\u2013\u2014\u2012\u2015\u2600-\u27BF\u2B00-\u2BFF]|[\uD83C-\uD83E][\uDC00-\uDFFF]")
        assertFalse(bad.containsMatchIn(jsonFile.readText()))
    }

    // ---------- Advisor ----------

    @Test fun planRamMeasuredVsEstimated() {
        val e = m("gemma-4-e4b")
        assertEquals(3283 to false, ModelAdvisor.planRamMb(e, false))
        val none = e.copy(ramCpuMb = null, ramGpuMb = null)
        val (need, est) = ModelAdvisor.planRamMb(none, false)
        assertTrue(est)
        assertEquals((none.sizeMb * 2 + 1024).toInt(), need)
    }

    @Test fun assessThreeLevels() {
        val e = m("gemma-4-e4b") // CPU 3283
        assertEquals(Fit.GUT, ModelAdvisor.assess(e, dev(total = 12_000, avail = 6_000), false).fit)
        assertEquals(Fit.KNAPP, ModelAdvisor.assess(e, dev(total = 6_000, avail = 2_500), false).fit)
        assertEquals(Fit.ZU_WENIG, ModelAdvisor.assess(e, dev(total = 4_000, avail = 1_500), false).fit)
    }

    @Test fun storageOkTightMissing() {
        val e = m("gemma-4-e4b")
        val ok = ModelAdvisor.assess(e, dev(storageGb = 50.0), false)
        assertTrue(ok.storageOk); assertFalse(ok.storageTight)
        val tight = ModelAdvisor.assess(e, dev(storageGb = 4.5), false)
        assertTrue(tight.storageOk); assertTrue(tight.storageTight)
        val none = ModelAdvisor.assess(e, dev(storageGb = 2.0), false)
        assertFalse(none.storageOk)
        val inst = ModelAdvisor.assess(e, dev(storageGb = 0.1), false, installed = true)
        assertTrue(inst.storageOk)
    }

    @Test fun recommendKeepsGemmaOnBigDevice() {
        val r = ModelAdvisor.recommend(cat.models, dev(total = 16_000, avail = 8_000))!!
        assertEquals("gemma-4-e4b", r.entry.id)
    }

    @Test fun recommendSwitchesOnSmallDevice() {
        val r = ModelAdvisor.recommend(cat.models, dev(total = 4_000, avail = 2_000))!!
        assertTrue(r.entry.id != "gemma-4-e4b")
        assertNotNull(r.entry.downloadUrl)
        assertTrue(r.assessment.fit != Fit.ZU_WENIG)
    }

    @Test fun recommendSwitchesOnLowStorage() {
        val r = ModelAdvisor.recommend(cat.models, dev(total = 16_000, avail = 8_000, storageGb = 1.0))!!
        assertTrue(r.entry.sizeBytes < 900_000_000L)
    }

    @Test fun recommendKeepsInstalledGemmaDespiteNoStorage() {
        val r = ModelAdvisor.recommend(cat.models, dev(total = 16_000, avail = 8_000, storageGb = 0.05), installedIds = setOf("gemma-4-e4b"))!!
        assertEquals("gemma-4-e4b", r.entry.id)
    }

    @Test fun neverRecommendGatedOrPreview() {
        for (total in listOf(2_000L, 3_000L, 4_000L, 6_000L, 8_000L, 12_000L, 24_000L)) {
            for (f in Focus.values()) {
                val rank = ModelAdvisor.rank(cat.models, dev(total = total, avail = total / 2), f)
                rank.forEach { assertFalse(it.entry.id, it.entry.gated || it.entry.status == ModelStatus.PREVIEW || it.entry.kind == ModelKind.ASR) }
                val rec = ModelAdvisor.recommend(cat.models, dev(total = total, avail = total / 2), f)
                assertNotNull(rec)
                assertFalse(rec!!.entry.gated)
                assertTrue(rec.entry.kind == ModelKind.LLM)
            }
        }
    }

    @Test fun rankPutsGoodBeforeTightAndStableBeforeExperimental() {
        val r = ModelAdvisor.rank(cat.models, dev(total = 8_000, avail = 5_000), Focus.PROFILE)
        val fits = r.map { it.assessment.fit }
        val firstKnapp = fits.indexOf(Fit.KNAPP)
        if (firstKnapp >= 0) assertTrue(fits.drop(firstKnapp).none { it == Fit.GUT })
        val good = r.filter { it.assessment.fit == Fit.GUT }
        val firstExp = good.indexOfFirst { it.entry.status == ModelStatus.EXPERIMENTAL }
        if (firstExp >= 0) assertTrue(good.drop(firstExp).none { it.entry.status != ModelStatus.EXPERIMENTAL })
    }

    @Test fun limitsForContext() {
        val big = ModelAdvisor.limitsFor(m("gemma-4-e4b"), 8192, 12_000)
        assertEquals(8192, big.maxTokens); assertEquals(12_000, big.contextChars); assertEquals("", big.note)
        val q4 = ModelAdvisor.limitsFor(m("qwen3-4b-int4"), 8192, 12_000)
        assertEquals(2048, q4.maxTokens); assertEquals((2048 - 1100) * 3, q4.contextChars)
        assertTrue(q4.note.isNotBlank())
        val q06 = ModelAdvisor.limitsFor(m("qwen3-0.6b-int8"), 8192, 12_000)
        assertEquals(4096, q06.maxTokens); assertEquals((4096 - 1100) * 3, q06.contextChars)
        val unknown = ModelAdvisor.limitsFor(m("gemma-4-e4b").copy(contextTokens = 0), 8192, 12_000)
        assertEquals(4096, unknown.maxTokens); assertTrue(unknown.note.contains("angenommen"))
        val huge = ModelAdvisor.limitsFor(m("gemma-4-e4b").copy(contextTokens = 32768), 8192, 12_000)
        assertEquals(8192, huge.maxTokens)
    }

    @Test fun downloadCheckBlocksAndWarns() {
        val gated = m("gemma-3-1b-it")
        assertFalse(ModelAdvisor.downloadCheck(gated, dev(), 0).allowed)
        assertTrue(ModelAdvisor.downloadCheck(gated, dev(), 0).blockReason!!.contains("Gated"))
        val asr = cat.models.first { it.kind == ModelKind.ASR }
        assertTrue(ModelAdvisor.downloadCheck(asr, dev(), 0).allowed)
        assertFalse(ModelAdvisor.downloadCheck(asr.copy(files = emptyList(), downloadable = false), dev(), 0).allowed)
        assertFalse(ModelAdvisor.downloadCheck(asr.copy(files = asr.files.map { it.copy(sha256 = "") }), dev(), 0).allowed)
        val e = m("gemma-4-e4b")
        val noSpace = ModelAdvisor.downloadCheck(e, dev(storageGb = 3.0), 0)
        assertFalse(noSpace.allowed)
        val wifi = ModelAdvisor.downloadCheck(e, dev(unmetered = true), 0)
        assertTrue(wifi.allowed); assertFalse(wifi.needsMeteredConfirm)
        val mobile = ModelAdvisor.downloadCheck(e, dev(unmetered = false), 0)
        assertTrue(mobile.allowed); assertTrue(mobile.needsMeteredConfirm); assertTrue(mobile.warnings.isNotEmpty())
        val unknownNet = ModelAdvisor.downloadCheck(e, dev(unmetered = null), 0)
        assertTrue(unknownNet.needsMeteredConfirm)
        // partly downloaded: less space is required
        val partly = ModelAdvisor.downloadCheck(e, dev(storageGb = 1.0), 3_000_000_000L)
        assertTrue(partly.allowed)
    }

    @Test fun smallFileOnMobileNeedsNoConfirm() {
        val small = m("qwen3-0.6b-int4gpu").copy(sizeBytes = 150_000_000L)
        assertFalse(ModelAdvisor.downloadCheck(small, dev(unmetered = false), 0).needsMeteredConfirm)
    }

    // ---------- Qwen3.5 (0.2.2) ----------

    @Test fun qwen35EntriesMatchResearch() {
        val a = m("qwen35-4b-int4")
        assertEquals("Qwen3.5-4B_mixed_int4.litertlm", a.file); assertEquals(2_754_365_536L, a.sizeBytes)
        assertEquals("2345e16c6e6e5a2db95d5bcfa3b4fc4dc2fc0394", a.revision)
        val b = m("qwen35-4b-int8")
        assertEquals("Qwen3.5-4B_int8.litertlm", b.file); assertEquals(4_407_428_464L, b.sizeBytes)
        val c = m("qwen35-0.8b-int8")
        assertEquals("Qwen3.5-0.8B_int8.litertlm", c.file); assertEquals(963_184_864L, c.sizeBytes)
        for (e in listOf(a, b, c, m("qwen35-2b-int8"))) {
            assertEquals(e.id, 4096, e.contextTokens)
            assertFalse(e.id + " Vision", e.vision)
            assertTrue(e.id, e.german.contains("nicht belegt"))
            assertNotNull(e.id, e.downloadUrl)
            assertEquals(40, e.revision.length)
        }
    }

    @Test fun qwen35CpuOnlyAndStatus() {
        assertFalse(m("qwen35-0.8b-int8").prefersGpu)
        assertFalse(m("qwen35-4b-int4").prefersGpu)
        assertFalse(m("qwen35-4b-int8").prefersGpu)
        assertEquals(ModelStatus.EXPERIMENTAL, m("qwen35-2b-int8").status)
        assertEquals(ModelStatus.EXPERIMENTAL, m("qwen35-4b-int8").status)
        assertEquals(ModelStatus.STANDARD, m("gemma-4-e4b").status)
    }

    @Test fun exactlyOneComparisonCandidate() {
        val c = cat.models.filter { it.comparison }
        assertEquals(listOf("qwen35-4b-int4"), c.map { it.id })
    }

    @Test fun qwenEntriesCarryGermanNotice() {
        cat.models.filter { it.id.startsWith("qwen") }.forEach { assertTrue(it.id, it.german.isNotBlank()) }
    }

    @Test fun limitsForQwen35Budget4096() {
        for (id in listOf("qwen35-4b-int4", "qwen35-0.8b-int8", "qwen35-2b-int8")) {
            val l = ModelAdvisor.limitsFor(m(id), 8192, 12_000)
            assertEquals(id, 4096, l.maxTokens)
            assertEquals(id, (4096 - 1100) * 3, l.contextChars)
            assertTrue(l.note.isNotBlank())
        }
    }

    @Test fun comparisonShownNextToGemmaOnBigDevice() {
        val d = dev(total = 16_000, avail = 9_000)
        assertEquals("gemma-4-e4b", ModelAdvisor.recommend(cat.models, d)!!.entry.id)
        val c = ModelAdvisor.comparisonFor(cat.models, d)!!
        assertEquals("qwen35-4b-int4", c.entry.id)
        assertTrue(c.assessment.fit != Fit.ZU_WENIG)
    }

    @Test fun comparisonAbsentWhenItDoesNotFit() {
        assertNull(ModelAdvisor.comparisonFor(cat.models, dev(total = 5_000, avail = 2_000)))
        assertNull(ModelAdvisor.comparisonFor(cat.models, dev(total = 16_000, avail = 9_000, storageGb = 1.0)))
    }

    @Test fun comparisonNeverStandardGatedOrPreview() {
        val bad = cat.models.map { if (it.id == "gemma-4-e4b" || it.gated || it.status == ModelStatus.PREVIEW) it.copy(comparison = true) else it }
        assertNull(ModelAdvisor.comparisonFor(bad.map { if (it.id == "qwen35-4b-int4") it.copy(comparison = false) else it }, dev(total = 16_000, avail = 9_000)))
    }

    @Test fun gemmaStaysRecommendedEvenWithQwen35Installed() {
        val r = ModelAdvisor.recommend(cat.models, dev(total = 16_000, avail = 9_000), installedIds = setOf("qwen35-4b-int4"))!!
        assertEquals("gemma-4-e4b", r.entry.id)
    }

    @Test fun qwen35FitOn8GbAndNot6Gb() {
        val e = m("qwen35-4b-int4")
        assertEquals(Fit.KNAPP, ModelAdvisor.assess(e, dev(total = 8_000, avail = 3_500), false).fit)
        assertEquals(Fit.ZU_WENIG, ModelAdvisor.assess(e, dev(total = 6_000, avail = 3_000), false).fit)
        assertEquals(Fit.GUT, ModelAdvisor.assess(e, dev(total = 16_000, avail = 9_000), false).fit)
    }

    @Test fun smallestQwen35IsGoodOn6Gb() {
        assertEquals(Fit.GUT, ModelAdvisor.assess(m("qwen35-0.8b-int8"), dev(total = 6_000, avail = 3_000), false).fit)
    }

    @Test fun cacheWarningForQwen35FourB() {
        // 2.75 GB file: space for the file plus cache is just short of 5.6 GB
        val chk = ModelAdvisor.downloadCheck(m("qwen35-4b-int4"), dev(storageGb = 4.0), 0)
        assertTrue(chk.allowed); assertTrue(chk.warnings.any { it.contains("Cache") })
    }

    @Test fun noThinkSuffixOnlyForQwen3NotQwen35() {
        assertTrue(app.chatlens.llm.noThinkSuffixFor("Qwen3-0.6B.litertlm"))
        assertTrue(app.chatlens.llm.noThinkSuffixFor("Qwen3_1.7B.litertlm"))
        assertTrue(app.chatlens.llm.noThinkSuffixFor("qwen3_4b_mixed_int4.litertlm"))
        assertFalse(app.chatlens.llm.noThinkSuffixFor("Qwen3.5-4B_mixed_int4.litertlm"))
        assertFalse(app.chatlens.llm.noThinkSuffixFor("Qwen3.5-0.8B_int8.litertlm"))
        assertFalse(app.chatlens.llm.noThinkSuffixFor("gemma-4-E4B-it.litertlm"))
        cat.models.filter { it.file != null && it.file!!.contains("Qwen3.5", ignoreCase = true) }.forEach {
            assertFalse(it.id, app.chatlens.llm.noThinkSuffixFor(it.file!!))
        }
    }
}
