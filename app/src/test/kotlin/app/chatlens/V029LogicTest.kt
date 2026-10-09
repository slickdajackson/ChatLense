package app.chatlens

import app.chatlens.agent.ChatNotFoundException
import app.chatlens.agent.ForegroundGuard
import app.chatlens.agent.ForegroundProbe
import app.chatlens.agent.NavigationException
import app.chatlens.agent.RunProgress
import app.chatlens.agent.ScreenInsets
import app.chatlens.agent.StepKind
import app.chatlens.agent.SwipeSafety
import app.chatlens.asr.VoicePlan
import app.chatlens.auto.AutoQueue
import app.chatlens.auto.AutoQueueRunner
import app.chatlens.auto.PausedAutoException
import app.chatlens.auto.QueueKind
import app.chatlens.core.Bounds
import app.chatlens.core.TaskMode
import app.chatlens.data.AppSettings
import app.chatlens.llm.ContextPlanner
import app.chatlens.llm.RememberedLevel
import app.chatlens.memory.SelfAnalysis
import app.chatlens.wizard.StartPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Tests der Version 0.2.9: Kontextstufen, Wischgeometrie, Vordergrundwaechter, Warteschlange, Sprachschritt, Benachrichtigungen, Overlay, Standardwerte. */
class V029LogicTest {
    private fun src(path: String): String {
        val roots = listOf("src/main/kotlin/app/chatlens/", "app/src/main/kotlin/app/chatlens/")
        for (r in roots) File(r + path).takeIf { it.isFile }?.let { return it.readText() }
        fail("Quelle nicht gefunden: $path")
        return ""
    }

    // ---------- Kontext ----------

    @Test fun contextAutoPicksLargestThatFitsRam() {
        val big = ContextPlanner.plan(32768, 0, 6000, 12000, 16000, RememberedLevel(), 0L)
        assertEquals(32768, big.candidates.first())
        val small = ContextPlanner.plan(32768, 0, 6000, 6500, 8000, RememberedLevel(), 0L)
        assertTrue(small.candidates.first() < 32768)
        assertTrue(small.candidates.zipWithNext().all { (a, b) -> a > b })
    }

    @Test fun contextManualIsUpperBoundAndFallsBackDownwards() {
        val p = ContextPlanner.plan(32768, 16384, 6000, 99999, 99999, RememberedLevel(), 0L)
        assertEquals(listOf(16384, 8192, 4096), p.candidates)
    }

    @Test fun contextRemembersSuccessAndRetestsHigherOnlyAfterInterval() {
        val t0 = 1_000_000L
        val r = ContextPlanner.afterSuccess(RememberedLevel(), 16384, 32768, t0)
        val soon = ContextPlanner.plan(32768, 0, 100, 99999, 99999, r.copy(failedLevel = 32768), t0 + 1000)
        assertEquals(16384, soon.candidates.first())
        val later = ContextPlanner.plan(32768, 0, 100, 99999, 99999, r.copy(failedLevel = 32768), t0 + ContextPlanner.PROBE_INTERVAL_MS + 1)
        assertEquals(32768, later.candidates.first())
    }

    @Test fun contextFailureLowersRememberedAndCharBudgetFollowsTokens() {
        val f = ContextPlanner.afterFailure(RememberedLevel(okLevel = 16384), 16384, 5L)
        assertEquals(0, f.okLevel)
        assertEquals(16384, f.failedLevel)
        assertEquals((16384 - 1500) * 3, ContextPlanner.charsFor(16384))
        assertTrue(ContextPlanner.charsFor(32768) > ContextPlanner.charsFor(8192))
        assertEquals(ContextPlanner.charsFor(8192), ContextPlanner.effectiveChars(0, 8192))
        assertEquals(5000, ContextPlanner.effectiveChars(5000, 8192))
    }

    @Test fun contextMigrationTurnsOldDefaultsIntoAutomatic() {
        assertEquals(0, ContextPlanner.loadTokens(8192, false))
        assertEquals(8192, ContextPlanner.loadTokens(8192, true))
        assertEquals(0, ContextPlanner.loadChars(12_000, false))
        assertEquals(0, AppSettings().localMaxTokens)
    }

    @Test fun mapReducePacksLongHistoryIntoBoundedChunks() {
        val blocks = (1..200).map { "Block $it " + "x".repeat(300) }
        val chunks = SelfAnalysis.packChunks(blocks, 12_000, 4)
        assertTrue(chunks.size in 2..4)
        assertTrue(chunks.all { it.first.length <= 12_000 + 400 })
        val one = SelfAnalysis.packChunks(blocks.take(5), 12_000, 4)
        assertEquals(1, one.size)
    }

    // ---------- Wischgeometrie ----------

    private val ins = ScreenInsets(1440, 3200, 100, 80)

    @Test fun olderSwipesNeverStartInTopFifteenPercentAndStayInWindow() {
        val list = Bounds(0, 300, 1440, 3000)
        val (top, bottom) = SwipeSafety.window(list, ins)
        assertTrue(top >= (3200 * 0.15).toInt())
        val plan = SwipeSafety.plan(list, ins, older = true, distancePx = 2000)
        assertTrue(plan.isNotEmpty())
        for (s in plan) {
            assertTrue("Start unter 15 Prozent: ${s.fromY}", s.fromY >= (3200 * 0.15).toInt())
            assertTrue(s.fromY >= top + ((bottom - top) * 0.25).toInt() - 1)
            assertTrue(s.toY <= bottom)
            assertTrue(s.toY > s.fromY)
            assertTrue(SwipeSafety.pointSafe(s.x, s.fromY, ins) && SwipeSafety.pointSafe(s.x, s.toY, ins))
        }
        assertTrue(plan.size <= 2)
    }

    // ---------- Vordergrundwaechter ----------

    private class Fake : ForegroundProbe {
        var clock = 0L
        var root: String? = "com.whatsapp"
        var dismisses = 0
        var onDismiss: (Fake) -> Unit = {}
        override fun dismissSystemUi(): Boolean { dismisses++; onDismiss(this); return true }
        override fun rootPackage() = root
        override fun eventPackage(): String? = null
        override fun eventAgeMs() = Long.MAX_VALUE
        override fun activity(): String? = null
        override fun windowsSummary() = "Fenster/$root"
        override fun launch(): Boolean = false
    }

    private fun guard(f: Fake, log: MutableList<String> = ArrayList()) =
        ForegroundGuard(f, "com.whatsapp", "app.chatlens", { log.add(it) }, { f.clock += it }, { f.clock })

    @Test fun systemUiInFrontIsReportedByPackageNotAsChatLens() {
        val f = Fake().apply { root = "com.android.systemui" }
        val e = try { runBlocking { guard(f).ensure(false, "Wisch") }; null } catch (x: NavigationException) { x }
        assertNotNull(e)
        assertTrue(e!!.message!!.contains("com.android.systemui"))
        assertFalse(e.message!!.contains("ChatLens selbst"))
        assertFalse(e.message!!.contains("ChatLens (app.chatlens)"))
        assertTrue("Schliessen wurde versucht", f.dismisses >= 1)
    }

    @Test fun manualModeDismissesSystemUiFirstThenContinues() {
        val f = Fake().apply { root = "com.android.systemui" }
        f.onDismiss = { it.root = "com.whatsapp" }
        runBlocking { guard(f).ensure(false, "Chat schon geoeffnet") }
        assertEquals(1, f.dismisses)
    }

    @Test fun recoverAfterReportsIntervention() {
        val f = Fake().apply { root = "com.android.systemui" }
        f.onDismiss = { it.root = "com.whatsapp" }
        val did = runBlocking { guard(f).recoverAfter("Wisch von (720,476) nach (720,1206)", false) }
        assertTrue("Eingriff wird gemeldet, damit der Schritt wiederholt und nicht als unveraendert gezaehlt wird", did)
        val f2 = Fake()
        assertFalse(runBlocking { guard(f2).recoverAfter("Wisch", false) })
    }

    @Test fun scrollDeviceRepeatsSegmentAfterIntervention() {
        val t = src("agent/AndroidScrollDevice.kt")
        assertTrue(t.contains("recoverAfter"))
        assertTrue(t.contains("MAX_REPEATS"))
    }

    // ---------- Warteschlange ----------

    @Test fun queuePausesOnlyOnConsecutiveFailuresOfSameKind() = runBlocking {
        val q = AutoQueue.of(QueueKind.SETUP, listOf("A", "B", "C", "D", "E"), 100, 1L, true)
        // A: nicht gefunden, B: Navigation, C: nicht gefunden, D: Navigation: nie zwei gleiche in Folge, also keine Pause
        AutoQueueRunner.run(q, { 5L }, {}, maxConsecutiveFailures = 2) { item ->
            when (item.title) {
                "A", "C" -> throw ChatNotFoundException("nicht gefunden")
                "B", "D" -> throw NavigationException("Navigation")
                else -> "ok"
            }
        }
        assertTrue(q.finished)
    }

    @Test fun queuePausesAfterTwoSameKind() {
        val q = AutoQueue.of(QueueKind.SETUP, listOf("A", "B", "C"), 100, 1L, true)
        try {
            runBlocking { AutoQueueRunner.run(q, { 5L }, {}, maxConsecutiveFailures = 2) { throw NavigationException("WhatsApp nicht vorn") } }
            fail("Pause erwartet")
        } catch (e: PausedAutoException) {
            assertTrue(e.message!!.contains("gleicher Ursache"))
        }
    }

    // ---------- Sprachschritt ----------

    @Test fun voicePlanSkipsWithReasonInsteadOfSilence() {
        assertTrue(VoicePlan.decide(true, 3, true, true, true).run)
        val off = VoicePlan.decide(false, 3, true, true, true)
        assertFalse(off.run); assertTrue(off.skipReason!!.contains("Schalter aus"))
        val none = VoicePlan.decide(true, 0, true, true, true)
        assertFalse(none.run); assertEquals(null, none.skipReason)
        val noFolder = VoicePlan.decide(true, 2, true, false, false)
        assertFalse(noFolder.run); assertTrue(noFolder.skipReason!!.contains("Ordner wählen"))
        val lost = VoicePlan.decide(true, 2, true, true, false)
        assertTrue(lost.skipReason!!.contains("Freigabe"))
        val noModel = VoicePlan.decide(true, 2, false, true, true)
        assertTrue(noModel.skipReason!!.contains("Parakeet"))
    }

    @Test fun voiceStepIsInPlanOfEveryTaskWhenEnabledAndHasCounter() {
        for (t in TaskMode.entries) assertTrue(StepKind.TRANSCRIBE in RunProgress.plan(t, false, false, true, true))
        val p = RunProgress.begin(RunProgress.plan(TaskMode.ANALYSE, false, false, true, true), 0L)
        val at = RunProgress.advance(p, StepKind.TRANSCRIBE, 5L).copy(detail = "Nachricht 2 von 4")
        assertEquals("Nachricht 2 von 4", at.detail)
        assertEquals("", RunProgress.advance(at, StepKind.LLM, 9L).detail)
        val r = src("agent/ChatRunner.kt")
        assertTrue(r.contains("VoicePlan.decide"))
        assertTrue(r.contains("stepDetail"))
    }

    // ---------- Benachrichtigungen ----------

    @Test fun notificationChannelsAreQuietAndNeverHeadsUp() {
        val ch = src("service/NotificationChannels.kt")
        assertFalse("kein Heads-up Kanal", Regex("IMPORTANCE_(HIGH|DEFAULT|MAX)").containsMatchIn(ch))
        assertTrue(Regex("IMPORTANCE_LOW").findAll(ch).count() >= 3)
        assertTrue(ch.contains("setSound(null, null)") && ch.contains("enableVibration(false)"))
        assertTrue("alte Kanaele werden entfernt", ch.contains("deleteNotificationChannel"))
        val svc = src("service/AgentForegroundService.kt")
        assertFalse(Regex("IMPORTANCE_(HIGH|DEFAULT|MAX)|PRIORITY_(HIGH|DEFAULT|MAX)").containsMatchIn(svc))
        assertTrue(svc.contains("setOnlyAlertOnce(true)"))
        assertTrue(svc.contains("setSilent(true)"))
        assertTrue(svc.contains("VISIBILITY_SECRET"))
        assertFalse(Regex("PRIORITY_(HIGH|DEFAULT|MAX)").containsMatchIn(src("service/DebugDumper.kt")))
    }

    // ---------- Overlay ----------

    @Test fun overlayBecomesTouchTransparentWhileReading() {
        val o = src("service/OverlayService.kt")
        assertTrue(o.contains("FLAG_NOT_TOUCHABLE"))
        assertTrue(o.contains("READING_PHASES"))
        assertTrue(o.contains("setReadingMode"))
        assertTrue(o.contains("PANEL_MAX_FRACTION"))
    }

    @Test fun overlayPanelHasBoundedHeightScrollCopyAndControls() {
        val u = src("ui/OverlayUi.kt")
        val panel = u.substring(u.indexOf("fun OverlayPanel"))
        assertTrue(panel.contains("heightIn(max = maxHeightDp.dp)"))
        assertTrue(panel.contains("verticalScroll(rememberScrollState())"))
        assertTrue(panel.contains("SelectionContainer"))
        for (w in listOf("Abbrechen", "Schließen", "Punkt entfernen", "Kopieren", "PromptChoiceCard", "RunStatus")) assertTrue(w, panel.contains(w))
    }

    // ---------- Standardwerte und Zustimmung ----------

    @Test fun defaultsAreConsentFirst() {
        val d = AppSettings()
        assertFalse(d.checkupOnStart)
        assertTrue(d.voiceTranscribe)
        assertFalse(d.wizardDone)
    }

    @Test fun migrationSwitchesOldSettingsOnce() {
        assertFalse("alter Bestand mit gespeichertem an", StartPolicy.checkupOnStartLoaded(true, false))
        assertTrue("nach der Migration zaehlt die bewusste Wahl", StartPolicy.checkupOnStartLoaded(true, true))
        assertFalse(StartPolicy.checkupOnStartLoaded(false, true))
        assertTrue(StartPolicy.voiceLoaded(false, false))
        assertFalse("nach der Migration bleibt eine bewusste Wahl aus", StartPolicy.voiceLoaded(false, true))
    }

    @Test fun autoCheckupNeedsWizardPrivacyAndSwitch() {
        assertTrue(StartPolicy.mayAutoCheckup(true, true, true))
        assertFalse(StartPolicy.mayAutoCheckup(false, true, true))
        assertFalse(StartPolicy.mayAutoCheckup(true, false, true))
        assertFalse(StartPolicy.mayAutoCheckup(true, true, false))
    }

    @Test fun noWhatsAppLaunchInAppStartPath() {
        val m = src("MainActivity.kt")
        // Der Start der App (onCreate, onResume, onNewIntent, handleIntent) startet weder WhatsApp noch einen Lauf
        for (name in listOf("override fun onCreate", "override fun onResume", "override fun onNewIntent", "private fun handleIntent")) {
            val i = m.indexOf(name)
            assertTrue(name, i >= 0)
            val body = m.substring(i, m.indexOf("\n    }\n", i))
            for (bad in listOf("openWhatsApp", "startAutoJob", "startRun", "getLaunchIntentForPackage", "AgentController.start")) assertFalse("$name enthaelt $bad", body.contains(bad))
        }
        // Der Auto-Checkup beim Start ist an die Richtlinie gebunden
        val i = m.indexOf("startAutoJob(AutoStart.Checkup(s0.checkupCount")
        assertTrue(i > 0)
        assertTrue(m.substring(maxOf(0, i - 1500), i).contains("StartPolicy.mayAutoCheckup("))
        // WhatsApp wird nur in zwei Handlern gestartet (Tipp auf einen Startknopf)
        assertEquals(2, Regex("getLaunchIntentForPackage").findAll(m).count())
        // Der Assistent selbst startet nie direkt etwas
        val w = src("ui/WizardUi.kt")
        for (bad in listOf("getLaunchIntentForPackage", "launchApp", "startActivity")) assertFalse("WizardUi enthaelt $bad", w.contains(bad))
        // Dienste starten WhatsApp nur ueber den Wächter innerhalb eines gestarteten Laufs
        assertFalse(src("service/OverlayService.kt").contains("getLaunchIntentForPackage"))
    }
}
