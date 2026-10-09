package app.chatlens

import app.chatlens.agent.DotFix
import app.chatlens.agent.DotStatus
import app.chatlens.agent.Phase
import app.chatlens.agent.ProgressUi
import app.chatlens.agent.PromptChoice
import app.chatlens.agent.PromptChoiceBroker
import app.chatlens.agent.RunProgress
import app.chatlens.agent.StepKind
import app.chatlens.core.TaskMode
import app.chatlens.agent.WfStep
import app.chatlens.agent.Workflow
import app.chatlens.asr.VoiceAutoDownload
import app.chatlens.models.DlStatus
import app.chatlens.models.Measured
import app.chatlens.models.MeasuredStats
import app.chatlens.models.ModelCatalog
import app.chatlens.models.ModelKind
import app.chatlens.models.ModelRatings
import app.chatlens.models.RatingArea
import app.chatlens.prompts.PromptCodec
import app.chatlens.prompts.PromptBook
import app.chatlens.prompts.PromptLimits
import app.chatlens.prompts.PromptMode
import app.chatlens.prompts.QuickPrompts
import app.chatlens.service.OverlayRemoval
import app.chatlens.ui.RingAction
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Logik der Version 0.2.7 ohne Android: Punkt entfernen, Prompt-Auswahl, Fortschritt, Stimme, Reihenfolge, Modellbewertung, Logo-Ressourcen. */
class V027LogicTest {
    // ---------- Punkt entfernen ----------

    @Test fun removalIdleRemovesAtOnce() {
        assertEquals(OverlayRemoval.Step.REMOVE_NOW, OverlayRemoval.decide(false, 0, 1_000))
        assertEquals("Punkt entfernt. Wieder einschalten in den Einstellungen.", OverlayRemoval.message(OverlayRemoval.Step.REMOVE_NOW))
    }

    @Test fun removalWhileRunningWarnsThenCancels() {
        assertEquals(OverlayRemoval.Step.WARN_FIRST, OverlayRemoval.decide(true, 0, 10_000))
        assertEquals(OverlayRemoval.Step.CANCEL_AND_REMOVE, OverlayRemoval.decide(true, 10_000, 14_000))
        // Fenster abgelaufen: wieder zuerst warnen
        assertEquals(OverlayRemoval.Step.WARN_FIRST, OverlayRemoval.decide(true, 10_000, 10_000 + OverlayRemoval.CONFIRM_WINDOW_MS + 1))
        assertTrue(OverlayRemoval.message(OverlayRemoval.Step.WARN_FIRST).contains("bricht ihn ab"))
    }

    @Test fun ringHasRemoveAndSelfAnalysisButNoMoreThanSevenItems() {
        assertTrue(RingAction.entries.any { it.label == "Entfernen" })
        assertTrue(RingAction.entries.any { it.label == "Selbst" })
        // 7 Eintraege bei Radius 88: Sehnenabstand muss groesser als die 64 dp grosse Schaltflaeche sein
        val n = RingAction.entries.size
        assertTrue(n <= 7)
        assertTrue(2 * 88 * Math.sin(Math.PI / n) > 64)
    }

    // ---------- Prompt-Auswahl ----------

    @Test fun promptBookKeepsRecentWithoutDuplicatesAndCap() {
        var b = PromptBook()
        for (i in 1..14) b = b.withUsed(PromptMode.CUSTOM, "Aufgabe $i")
        b = b.withUsed(PromptMode.CUSTOM, "Aufgabe 10")
        assertEquals(PromptLimits.MAX_RECENT, b.recent.size)
        assertEquals("Aufgabe 10", b.recent.first())
        assertEquals(1, b.recent.count { it == "Aufgabe 10" })
        assertEquals(PromptMode.CUSTOM, b.lastMode)
        val std = b.withUsed(PromptMode.STANDARD, "")
        assertEquals(PromptMode.STANDARD, std.lastMode)
        assertEquals(b.recent, std.recent)
    }

    @Test fun promptBookSavedReplaceAndRemove() {
        var b = PromptBook().withSaved("Termine", "Suche Termine").withSaved("termine", "Suche Fristen")
        assertEquals(1, b.saved.size)
        assertEquals("Suche Fristen", b.saved[0].text)
        assertEquals(1, b.withSaved("   ", "x").saved.size)
        assertEquals(0, b.withoutSaved("TERMINE").saved.size)
        assertEquals(PromptLimits.MAX_LEN, PromptLimits.clean("a".repeat(9000)).length)
    }

    @Test fun promptCodecRoundTrip() {
        val b = PromptBook().withSaved("A", "Text A").withUsed(PromptMode.CUSTOM, "Zeile 1\nZeile 2")
        val back = PromptCodec.fromJson(PromptCodec.toJson(b))
        assertEquals(b.lastMode, back.lastMode)
        assertEquals(b.lastText, back.lastText)
        assertEquals(b.recent, back.recent)
        assertEquals(b.saved, back.saved)
    }

    @Test fun quickPromptsAreGermanAndWithoutDashes() {
        assertTrue(QuickPrompts.all.size >= 4)
        for (q in QuickPrompts.all) assertFalse(q.text.contains('\u2013') || q.text.contains('\u2014'))
    }

    @Test fun brokerTimesOutToCancel() = runBlocking {
        assertTrue(PromptChoiceBroker.ask("Chat", 10, timeoutMs = 40) is PromptChoice.Cancel)
        assertNull(PromptChoiceBroker.pending.value)
    }

    @Test fun brokerDeliversAnswer() = runBlocking {
        val a = async { PromptChoiceBroker.ask("Chat", 10, timeoutMs = 5_000) }
        var req = PromptChoiceBroker.pending.value
        var guard = 0
        while (req == null && guard++ < 200) { delay(10); req = PromptChoiceBroker.pending.value }
        assertTrue(req != null)
        PromptChoiceBroker.answer(req!!.id + 99, PromptChoice.Standard)   // falsche Id wird ignoriert
        assertTrue(PromptChoiceBroker.pending.value != null)
        PromptChoiceBroker.answer(req.id, PromptChoice.Custom("Nur Termine"))
        val got = a.await()
        assertTrue(got is PromptChoice.Custom && got.text == "Nur Termine")
        assertNull(PromptChoiceBroker.pending.value)
    }

    // ---------- Fortschritt ----------

    @Test fun planContainsStepsPerTask() {
        val p = RunProgress.plan(TaskMode.ANALYSE, chatAlreadyOpen = true, askPrompt = true, voiceEnabled = true, localModel = true)
        assertEquals(listOf(StepKind.COLLECT, StepKind.TRANSCRIBE, StepKind.CHOOSE, StepKind.LOAD_MODEL, StepKind.LLM), p)
        val m = RunProgress.plan(TaskMode.MEMORY, chatAlreadyOpen = false, askPrompt = false, voiceEnabled = false, localModel = false)
        assertEquals(listOf(StepKind.OPEN_CHAT, StepKind.COLLECT, StepKind.LLM, StepKind.SAVE), m)
        assertFalse(RunProgress.plan(TaskMode.SUGGEST, true, true, false, false).contains(StepKind.CHOOSE))
    }

    @Test fun advanceDropAndCounter() {
        var p = RunProgress.begin(RunProgress.plan(TaskMode.ANALYSE, false, true, true, true), 1_000)
        assertEquals("0/6 erledigt", RunProgress.counter(p, RunProgress.End.RUNNING))
        p = RunProgress.advance(p, StepKind.COLLECT, 2_000)
        assertEquals("1/6 erledigt", RunProgress.counter(p, RunProgress.End.RUNNING))
        assertEquals("Nachrichten sammeln", RunProgress.headline(p, RunProgress.End.RUNNING))
        p = RunProgress.drop(p, StepKind.TRANSCRIBE)
        assertEquals(5, p.steps.size)
        assertEquals(StepKind.COLLECT, p.steps[p.index])
        // Schritt ausserhalb des Plans aendert nichts
        assertEquals(p, RunProgress.advance(p, StepKind.SAVE, 3_000))
        assertEquals("5/5 erledigt", RunProgress.counter(p, RunProgress.End.DONE))
        assertEquals("Fertig", RunProgress.headline(p, RunProgress.End.DONE))
    }

    @Test fun endStatesNameTheStep() {
        var p = RunProgress.begin(listOf(StepKind.COLLECT, StepKind.LLM), 0, "Gemma analysiert")
        p = RunProgress.advance(p, StepKind.LLM, 5_000)
        assertEquals("Abgebrochen bei: Gemma analysiert", RunProgress.headline(p, RunProgress.end(Phase.CANCELLED)))
        assertEquals("Fehler bei: Gemma analysiert", RunProgress.headline(p, RunProgress.end(Phase.FAILED)))
        assertEquals(RunProgress.End.RUNNING, RunProgress.end(Phase.LLM))
    }

    @Test fun llmDetailShowsElapsedAndNoTokenCounterByDefault() {
        val p = ProgressUi(listOf(StepKind.LLM), 0, "Gemma analysiert", 0, 10_000)
        assertEquals("seit 42 s", RunProgress.llmDetail(p, 52_000))
        assertEquals("seit 1:05 min", RunProgress.llmDetail(p, 75_000))
        assertEquals(-1, p.tokens)
        assertEquals("seit 3 s, 120 Token", RunProgress.llmDetail(p.copy(tokens = 120), 13_000))
    }

    @Test fun queueAndDownloadLines() {
        assertEquals("Chat 3 von 12", RunProgress.queueLine(2, 12))
        assertEquals("Chat 12 von 12", RunProgress.queueLine(40, 12))
        assertEquals("", RunProgress.queueLine(0, 0))
        assertEquals("335 von 670 MB, 50 Prozent", RunProgress.downloadLine(335_000_000, 670_000_000))
        val p = RunProgress.advance(RunProgress.begin(listOf(StepKind.OPEN_CHAT, StepKind.COLLECT, StepKind.LLM), 0), StepKind.COLLECT, 1)
        assertEquals("2/3 Nachrichten sammeln", RunProgress.notificationLine(p))
        assertEquals("", RunProgress.notificationLine(ProgressUi()))
    }

    @Test fun llmLabelsPerTaskAndModel() {
        assertEquals("Gemma analysiert", RunProgress.llmLabel(TaskMode.ANALYSE, "gemma-4-E4B-it", true))
        assertEquals("API berät", RunProgress.llmLabel(TaskMode.ADVISE, "gpt", false))
        assertEquals("Gemma analysiert deinen Stil", RunProgress.llmLabel(TaskMode.SELF, "Gemma 4", true))
    }

    // ---------- Stimme: automatischer Download ----------

    @Test fun voiceDecide() {
        assertEquals(VoiceAutoDownload.Action.NOTHING, VoiceAutoDownload.decide(true, DlStatus.IDLE, true))
        assertEquals(VoiceAutoDownload.Action.ALREADY_RUNNING, VoiceAutoDownload.decide(false, DlStatus.RUNNING, true))
        assertEquals(VoiceAutoDownload.Action.ALREADY_RUNNING, VoiceAutoDownload.decide(false, DlStatus.VERIFYING, false))
        assertEquals(VoiceAutoDownload.Action.START, VoiceAutoDownload.decide(false, DlStatus.IDLE, true))
        assertEquals(VoiceAutoDownload.Action.START, VoiceAutoDownload.decide(false, DlStatus.FAILED, true))
        assertEquals(VoiceAutoDownload.Action.ASK_METERED, VoiceAutoDownload.decide(false, DlStatus.IDLE, false))
        assertEquals(VoiceAutoDownload.Action.ASK_METERED, VoiceAutoDownload.decide(false, DlStatus.PAUSED, null))
    }

    @Test fun voiceTriggerLogsAndStartsOnlyOnWifi() {
        val log = ArrayList<String>(); var started: Boolean? = null
        VoiceAutoDownload.trigger(false, DlStatus.IDLE, true, 670_000_000, { started = it }, { log.add(it) })
        assertEquals(false, started)
        assertTrue(log.single().startsWith("STIMME: Download gestartet"))
        assertTrue(log.single().contains("670 MB"))
    }

    @Test fun voiceTriggerOnMobileAsksFirstAndStartsAfterConfirm() {
        val log = ArrayList<String>(); var started: Boolean? = null
        val a = VoiceAutoDownload.trigger(false, DlStatus.IDLE, false, 670_000_000, { started = it }, { log.add(it) })
        assertEquals(VoiceAutoDownload.Action.ASK_METERED, a)
        assertNull(started)
        assertFalse(log.any { it.startsWith("STIMME: Download gestartet") })
        VoiceAutoDownload.confirmMetered(670_000_000, { started = it }, { log.add(it) })
        assertEquals(true, started)
        assertTrue(log.last().startsWith("STIMME: Download gestartet"))
    }

    @Test fun voiceTriggerDoesNothingWhenReadyOrRunning() {
        var n = 0
        VoiceAutoDownload.trigger(true, DlStatus.IDLE, true, 1, { n++ }, { n++ })
        VoiceAutoDownload.trigger(false, DlStatus.RUNNING, true, 1, { n++ }, { n++ })
        assertEquals(0, n)
    }

    @Test fun voiceHintAndFailureText() {
        assertTrue(VoiceAutoDownload.hint(670_000_000, VoiceAutoDownload.Action.ASK_METERED).contains("WLAN"))
        assertTrue(VoiceAutoDownload.hint(670_000_000, VoiceAutoDownload.Action.ASK_METERED).contains("670 MB"))
        assertTrue(VoiceAutoDownload.failedText("Prüfsumme falsch").contains("Wiederholen"))
        assertTrue(VoiceAutoDownload.failedText("").contains("unbekannter Fehler"))
    }

    // ---------- Reihenfolge: Checkup zuerst ----------

    @Test fun workflowStepsFollowOrder() {
        assertEquals(WfStep.PERMISSIONS, Workflow.state(false, true, true, 50, 5, 0).step)
        assertEquals(WfStep.PERMISSIONS, Workflow.state(true, false, true, 0, 0, 0).step)
        assertEquals(WfStep.PERMISSIONS, Workflow.state(true, true, false, 0, 0, 0).step)
        assertEquals(WfStep.CHECKUP, Workflow.state(true, true, true, 0, 0, 0).step)
        assertEquals(WfStep.SELECTION, Workflow.state(true, true, true, 50, 0, 0).step)
        assertEquals(WfStep.SETUP, Workflow.state(true, true, true, 50, 10, 0).step)
        assertEquals(WfStep.DONE, Workflow.state(true, true, true, 50, 10, 4).step)
    }

    @Test fun multiChatLockedUntilCheckup() {
        assertFalse(Workflow.multiChatUnlocked(0))
        assertTrue(Workflow.multiChatUnlocked(1))
        assertTrue(Workflow.lockReason("das Setup", 0, 0)!!.contains("Checkup"))
        assertTrue(Workflow.lockReason("das Setup", 50, 0)!!.contains("ankreuzen"))
        assertNull(Workflow.lockReason("das Setup", 50, 3))
        assertNull(Workflow.lockReason("die Selbstanalyse", 50, 0, needsSelection = false))
    }

    @Test fun chatsComeOnlyFromCheckupList() {
        val list = listOf("Anna Beispiel", "Bernd Test", "Familie")
        assertEquals(listOf("Anna Beispiel", "Familie"), Workflow.fromCheckup(listOf("anna beispiel", "Familie", "Unbekannt", "Familie"), list))
        assertTrue(Workflow.fromCheckup(listOf("Fremder"), list).isEmpty())
        assertTrue(Workflow.fromCheckup(listOf("Anna"), emptyList()).isEmpty())
    }

    @Test fun wizardMarksFinishedSteps() {
        val w = Workflow.state(true, true, true, 50, 0, 0)
        val s = Workflow.stepsText(w)
        assertEquals(4, s.size)
        assertTrue(s[0].second)          // Berechtigungen erledigt
        assertTrue(s[1].second)          // Checkup erledigt
        assertFalse(s[2].second)         // Auswahl offen
    }

    // ---------- Status des Punkts ----------

    @Test fun dotStatusFixes() {
        val ok = DotStatus.of(true, true, true)
        assertTrue(ok.ok); assertTrue(ok.fixes.isEmpty())
        assertEquals(listOf(DotFix.OPEN_OVERLAY_PERMISSION, DotFix.OPEN_A11Y), DotStatus.of(true, false, false).fixes)
        assertEquals(listOf(DotFix.ENABLE_SWITCH), DotStatus.of(false, true, true).fixes)
        assertFalse(DotStatus.of(false, false, true).ok)
    }

    // ---------- Modellbewertung ----------

    private val cat by lazy { ModelCatalog.parse(File("src/main/assets/model-catalog.json").readText()) }

    @Test fun everyModelHasValidRatingsWithReasons() {
        assertTrue(cat.ratingsNote.contains("Einschätzung") && cat.ratingsNote.contains("nicht auf dem Gerät gemessen"))
        for (m in cat.models) {
            val areas = if (m.kind == ModelKind.ASR) RatingArea.entries.filter { it != RatingArea.ANALYSIS } else RatingArea.entries
            for (a in areas) {
                val r = m.ratings[a] ?: error("${m.id}: Bewertung ${a.key} fehlt")
                assertTrue("${m.id}/${a.key}", ModelRatings.valid(r))
                assertTrue(r.why.length >= 10)
            }
            if (m.kind == ModelKind.ASR) assertNull(m.ratings[RatingArea.ANALYSIS])
        }
    }

    @Test fun orderByAreaIsStableAndDescending() {
        val rec = cat.models
        assertEquals(rec, ModelRatings.order(cat.models, rec, null))
        val byGerman = ModelRatings.order(cat.models, rec, RatingArea.GERMAN)
        val scores = byGerman.map { it.ratings[RatingArea.GERMAN]?.score ?: -1 }
        assertEquals(scores.sortedDescending(), scores)
        assertEquals(rec.size, byGerman.size)
        // Gleichstand: Reihenfolge der Empfehlung bleibt
        val tied = byGerman.filter { it.ratings[RatingArea.GERMAN]?.score == scores.first() }
        assertEquals(rec.filter { it in tied }, tied)
    }

    @Test fun rowStates() {
        assertEquals(ModelRatings.RowState.ACTIVE, ModelRatings.rowState(true, true, null))
        assertEquals(ModelRatings.RowState.LOADED, ModelRatings.rowState(false, true, null))
        assertEquals(ModelRatings.RowState.LOADING, ModelRatings.rowState(false, false, DlStatus.RUNNING))
        assertEquals(ModelRatings.RowState.NOT_LOADED, ModelRatings.rowState(false, false, DlStatus.IDLE))
        assertEquals("Nicht geladen", ModelRatings.RowState.NOT_LOADED.label)
    }

    @Test fun measuredStatsAverageAndCodec() {
        var m = MeasuredStats.update(null, 20.0, 2000)
        assertEquals(10.0, m!!.avgSecPer1k, 1e-9)
        m = MeasuredStats.update(m, 60.0, 2000)
        assertEquals(2, m!!.runs); assertEquals(20.0, m.avgSecPer1k, 1e-9)
        assertEquals(m, MeasuredStats.decode(MeasuredStats.encode(m)))
        val keep = Measured(1, 1.0, 1.0, 1)
        assertEquals(keep, MeasuredStats.update(keep, 0.0, 100))
        assertNull(MeasuredStats.decode("kaputt"))
        assertTrue(MeasuredStats.describe(m).contains("gemessen"))
    }

    // ---------- Logo ----------

    private val res = File("src/main/res")

    @Test fun logoFilesExistAndNoOldVariantsRemain() {
        for (f in listOf("ic_logo_mark", "ic_launcher_fg", "ic_launcher_mono", "ic_launcher_bg", "ic_stat")) {
            val t = File(res, "drawable/$f.xml").also { assertTrue("fehlt: $it", it.isFile) }.readText()
            assertTrue(t.contains("<vector"))
            assertFalse("kein Text im Logo", t.contains("<text") || t.contains("\"CL\""))
        }
        // Entwurf 2a ist das einzige Logo: keine Varianten a/b/c mehr, keine Lupen-Dateien
        assertTrue(File(res, "drawable").listFiles()!!.none { it.name.startsWith("logo_") })
        assertTrue(File(res, "drawable").listFiles()!!.none { it.name.contains("lupe", true) || it.name.contains("lens", true) })
    }

    @Test fun logoIsGradientBubbleWithThreeCutOutSparklesAndHasNoMagnifier() {
        val t = File(res, "drawable/ic_logo_mark.xml").readText()
        // drei vierzackige Sterne: je 4 quadratische Kurven
        assertEquals("12 Kurven fuer 3 Sterne", 12, Regex("Q").findAll(t).count())
        assertTrue("Sprechblase vorhanden", t.contains("M13,6H35A9,9 0 0 1 44,15V27"))
        assertTrue("Sterne ausgespart (evenOdd)", t.contains("android:fillType=\"evenOdd\""))
        assertTrue("Verlauf Tuerkis nach Violett", t.contains("#4DE3D0") && t.contains("#8B7CFF") && t.contains("gradient"))
        assertFalse("keine Lupe (Ring)", t.contains("a15,15"))
        assertFalse("kein Lupengriff", t.contains("M31,31L42,42"))
        // Vordergrund, Einfarb-Ebene und Statussymbol: gleiche Form, keine Lupe
        for (f in listOf("ic_launcher_fg", "ic_launcher_mono", "ic_stat")) {
            val x = File(res, "drawable/$f.xml").readText()
            assertEquals("$f: 12 Kurven", 12, Regex("Q").findAll(x).count())
            assertFalse(x.contains("M31,31L42,42"))
        }
        // Einfarbig: keine Verlaufsfarben, damit das Themed Icon das System einfaerbt
        assertFalse(File(res, "drawable/ic_launcher_mono.xml").readText().contains("gradient"))
        assertFalse(File(res, "drawable/ic_stat.xml").readText().contains("gradient"))
        // Die Vorlage der Zeichnung und das Skript nennen nur noch Entwurf 2a
        val script = File("tools/make_logo.py").takeIf { it.isFile } ?: File("../tools/make_logo.py")
        val py = script.readText()
        assertTrue(py.contains("Entwurf 2a") && !py.contains("--default") && !py.contains("def mark(v"))
    }

    @Test fun adaptiveIconHasThreeLayers() {
        val xml = File(res, "mipmap-anydpi-v26/ic_launcher.xml").readText()
        assertTrue(xml.contains("<background")); assertTrue(xml.contains("<foreground")); assertTrue(xml.contains("<monochrome"))
        assertTrue(File(res, "mipmap-anydpi-v26/ic_launcher_round.xml").let { !it.isFile || it.readText().contains("<monochrome") })
        val mono = File(res, "drawable/ic_launcher_mono.xml").readText()
        assertFalse("Monochrom hat nur eine Farbe", Regex("#[0-9A-Fa-f]{6,8}").findAll(mono).map { it.value.uppercase().takeLast(6) }.toSet().size > 1)
    }
}
