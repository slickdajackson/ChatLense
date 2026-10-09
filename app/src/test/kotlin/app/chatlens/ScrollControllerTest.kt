package app.chatlens

import app.chatlens.agent.PageRead
import app.chatlens.agent.ScrollConfig
import app.chatlens.agent.ScrollController
import app.chatlens.agent.ScrollDevice
import app.chatlens.agent.ScrollHooks
import app.chatlens.core.Bounds
import app.chatlens.core.Kind
import app.chatlens.core.UiNode
import app.chatlens.data.ScrollMethod
import app.chatlens.parse.ChatParser
import app.chatlens.parse.TranscriptMerger
import app.chatlens.profile.SelectorProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Simulation of the device: a virtual chat, a window onto it, and swipe gestures that, depending on the scenario, run reliably,
 * with follow-through (a random factor), or with occasional overshoot. What is checked is the controller, not WhatsApp.
 */
class ScrollControllerTest {

    private val profile = SelectorProfile.parse(File("src/main/assets/profiles/whatsapp.json").readText())
    private val parser = ChatParser(profile, 3f, false)

    private class Msg(val text: String, val time: String?, val out: Boolean, val h: Int)

    private val listTop = 200
    private val listH = 1800

    private class Chat(var msgs: List<Msg>, var tops: List<Int>, var total: Int) {
        var viewTop = 0

        /** Older messages are prepended at the top; the visible slice stays unchanged. */
        fun prepend(older: List<Msg>) {
            val add = older.sumOf { it.h }
            msgs = older + msgs
            val t = ArrayList<Int>()
            var y = 0
            for (m in msgs) { t.add(y); y += m.h }
            tops = t
            total = y
            viewTop += add
        }
    }

    private fun makeChat(n: Int, withNotice: Boolean = true, first: Int = 0): Chat {
        val msgs = ArrayList<Msg>()
        if (withNotice) msgs.add(Msg("Nachrichten und Anrufe sind Ende-zu-Ende-verschluesselt. Niemand ausserhalb dieses Chats kann sie lesen.", null, false, 200))
        for (i in first until first + n) {
            msgs.add(Msg("Nachricht Nummer ${1000 + i} mit etwas Text dahinter", "%02d:%02d".format(8 + i / 60, i % 60), i % 3 == 0, 150 + (i * 53) % 260))
        }
        val tops = ArrayList<Int>()
        var y = 0
        for (m in msgs) { tops.add(y); y += m.h }
        val c = Chat(msgs, tops, y)
        c.viewTop = (y - listH).coerceAtLeast(0)
        return c
    }

    private fun clip(t: Int, b: Int, lt: Int, lb: Int): Pair<Int, Int>? {
        val a = maxOf(t, lt)
        val c = minOf(b, lb)
        return if (c > a) a to c else null
    }

    private fun tv(text: String, l: Int, t: Int, r: Int, b: Int, visible: Boolean = true) =
        UiNode("android.widget.TextView", null, text, null, if (visible) Bounds(l, t, r, b) else Bounds(0, 0, 0, 0), visible = visible)

    private fun tree(c: Chat, progress: Boolean = false): UiNode {
        val lb = listTop + listH
        val rows = ArrayList<UiNode>()
        for (i in c.msgs.indices) {
            val mg = c.msgs[i]
            val rt = listTop + c.tops[i] - c.viewTop
            val rb = rt + mg.h
            val rowC = clip(rt, rb, listTop, lb) ?: continue
            val tNat = rt + 20
            val tEnd = if (mg.time != null) rb - 70 else rb - 20
            val tc = clip(tNat, tEnd, listTop, lb)
            val l = if (mg.out) 520 else 40
            val r = if (mg.out) 1040 else 560
            val kids = ArrayList<UiNode>()
            kids.add(if (tc != null) tv(mg.text, l, tc.first, r, tc.second) else tv(mg.text, 0, 0, 0, 0, visible = false))
            if (mg.time != null) {
                val ti = clip(rb - 60, rb - 20, listTop, lb)
                val tl = if (mg.out) 940 else 460
                val tr = if (mg.out) 1020 else 540
                kids.add(if (ti != null) tv(mg.time, tl, ti.first, tr, ti.second) else tv(mg.time, 0, 0, 0, 0, visible = false))
            }
            rows.add(UiNode("android.view.ViewGroup", null, null, null, Bounds(0, rowC.first, 1080, rowC.second), children = kids))
        }
        val kids = ArrayList<UiNode>()
        kids.add(UiNode("androidx.recyclerview.widget.RecyclerView", null, null, null, Bounds(0, listTop, 1080, lb), scrollable = true, children = rows))
        if (progress) kids.add(UiNode("android.widget.ProgressBar", null, null, null, Bounds(500, 120, 580, 190)))
        return UiNode("android.widget.FrameLayout", null, null, null, Bounds(0, 0, 1080, 2400), children = kids)
    }

    private enum class Behavior { RELIABLE, FLING, OVERSHOOT, NOT_EXECUTED, INEFFECTIVE, FLICKER }

    private inner class SimDevice(
        val chat: Chat,
        val behavior: Behavior,
        seed: Int = 1,
        val actionFraction: Double = 0.85,
        val reportsCan: Boolean = true,
        /** Page scrolls that do nothing (the action reports success but does not move). */
        val actionNoop: Boolean = false,
        /** Older messages that are loaded in loadDelayMs after the first swipe at the top end. */
        val loadMore: List<Msg>? = null,
        val loadDelayMs: Long = 150,
        /** After every swipe toward older messages the tree briefly shows a foreign page (cannot be aligned). */
        val glitch: Boolean = false,
    ) : ScrollDevice {
        private val rnd = Random(seed)
        private var loadReadyAt = 0L
        private var loaded = false
        private var glitchOn = false
        private val foreign = makeChat(5, withNotice = false, first = 900).also { it.viewTop = 0 }
        private val flickerBase = chat.viewTop
        private var flickerState = false
        val pending: Boolean get() = loadMore != null && !loaded && loadReadyAt > 0

        private fun maybeLoad() {
            if (loadMore != null && !loaded && loadReadyAt > 0 && System.currentTimeMillis() >= loadReadyAt) {
                chat.prepend(loadMore)
                loaded = true
            }
        }

        var swipes = 0
        var actions = 0
        var counterSwipes = 0
        override fun snapshot(): UiNode {
            maybeLoad()
            return if (glitchOn) tree(foreign) else tree(chat, progress = pending)
        }
        override fun canScrollBack(list: Bounds?): Boolean? { maybeLoad(); return if (reportsCan) chat.viewTop > 0 else null }
        override fun canScrollForward(list: Bounds?): Boolean? { maybeLoad(); return if (reportsCan) chat.viewTop < (chat.total - listH).coerceAtLeast(0) else null }

        private fun move(deltaOlder: Int) {
            chat.viewTop = (chat.viewTop - deltaOlder).coerceIn(0, (chat.total - listH).coerceAtLeast(0))
        }

        override suspend fun swipe(list: Bounds, older: Boolean, distancePx: Int, durationMs: Long, holdMs: Long): Boolean {
            if (behavior == Behavior.NOT_EXECUTED) return false
            maybeLoad()
            glitchOn = false
            swipes++
            if (older && chat.viewTop == 0 && loadMore != null && loadReadyAt == 0L) loadReadyAt = System.currentTimeMillis() + loadDelayMs
            if (behavior == Behavior.FLICKER && older) {
                flickerState = !flickerState
                chat.viewTop = if (flickerState) (flickerBase + (0.1 * listH).toInt()).coerceAtMost((chat.total - listH).coerceAtLeast(0)) else flickerBase
                return true
            }
            if (!older) counterSwipes++
            if (behavior == Behavior.INEFFECTIVE && older) return true
            if (glitch && older) glitchOn = true
            val slop = 24
            var d = (distancePx - slop).coerceAtLeast(0).toDouble()
            when (behavior) {
                Behavior.FLING -> d *= 0.4 + rnd.nextDouble() * 2.6
                Behavior.OVERSHOOT -> if (older && swipes % 5 == 0) d = 2.5 * listH
                else -> {}
            }
            move(if (older) d.toInt() else -d.toInt())
            return true
        }

        override suspend fun action(list: Bounds, older: Boolean): Boolean {
            actions++
            maybeLoad()
            glitchOn = false
            if (actionNoop) return true
            val d = (actionFraction * listH).toInt()
            move(if (older) d else -d)
            return true
        }
    }

    private class Log : ScrollHooks {
        val lines = ArrayList<String>()
        override fun log(msg: String) { lines.add(msg) }
        override fun warn(msg: String) { lines.add("WARN " + msg) }
        override suspend fun ensureForeground() {}
        override suspend fun afterMerge(read: PageRead) {}
    }

    private class RunResult(val merger: TranscriptMerger, val ctl: ScrollController, val log: Log)

    private fun fastCfg(method: ScrollMethod) = ScrollConfig(
        method = method, pollMs = 1, settleMaxMs = 30, longWaitMs = 30,
        endWaitMs = 400, endNoHintWaitMs = 120, endChunkMs = 20,
    )

    private fun run(chat: Chat, dev: SimDevice, method: ScrollMethod = ScrollMethod.AUTO, maxAttempts: Int = 300, cfgIn: ScrollConfig? = null): RunResult {
        val merger = TranscriptMerger()
        val snap = dev.snapshot()
        val page = parser.parse(snap)
        val res = merger.add(page.items, true)
        val cfg = cfgIn ?: fastCfg(method)
        val hooks = Log()
        val ctl = ScrollController(dev, parser, merger, { merger.messages.take(6).any { it.kind == Kind.SYSTEM && profile.isChatStartNotice(it.text) } }, cfg, hooks, PageRead(res, page, snap))
        runBlocking {
            var n = 0
            while (n++ < maxAttempts && ctl.scrollOnce()) { /* weiter */ }
        }
        println("SIM " + ctl.summary())
        return RunResult(merger, ctl, hooks)
    }

    private fun assertComplete(chat: Chat, r: RunResult, label: String) {
        assertEquals("$label: Reihenfolge und Vollstaendigkeit\n" + r.log.lines.takeLast(15).joinToString("\n"), chat.msgs.map { it.text }, r.merger.messages.map { it.text })
        assertTrue("$label: keine Luecke", r.merger.messages.none { it.kind == Kind.GAP })
        assertEquals("$label: nichts mehr angeschnitten", 0, r.merger.incompleteCount())
        assertTrue("$label: Chatanfang erkannt", r.ctl.atStart)
    }

    @Test
    fun reliableSwipeReachesChatStartWithoutLossAndWithTargetOverlap() {
        val chat = makeChat(80)
        val dev = SimDevice(chat, Behavior.RELIABLE)
        val r = run(chat, dev)
        assertComplete(chat, r, "zuverlaessig")
        assertEquals(0, r.ctl.stats.losses)
        // measured travel about 65 percent of the list height after settling
        val avg = r.ctl.stats.measuredSum / r.ctl.stats.measuredCount
        assertTrue("mittlerer Weg $avg px", avg in (listH * 0.45).toInt()..(listH * 0.80).toInt())
        assertTrue(r.log.lines.any { it.contains("gemessen") && it.contains("Ueberlappung") && it.contains("Zeilen") })
    }

    @Test
    fun randomFlingFactorsAreRegulatedOrRecoveredWithoutGaps() {
        for (seed in 1..6) {
            val chat = makeChat(80)
            val dev = SimDevice(chat, Behavior.FLING, seed)
            val r = run(chat, dev)
            assertComplete(chat, r, "Nachschwung seed=$seed")
        }
    }

    @Test
    fun overshootIsRecoveredByCounterStepsInsteadOfInsertingGap() {
        val chat = makeChat(80)
        val dev = SimDevice(chat, Behavior.OVERSHOOT, 3)
        val r = run(chat, dev)
        assertComplete(chat, r, "Ueberschwingen")
        assertTrue("Verluste erwartet", r.ctl.stats.losses > 0)
        assertTrue("Gegenschritte erwartet", r.ctl.stats.counterSteps > 0)
        assertEquals(0, r.ctl.stats.gapsAccepted)
    }

    @Test
    fun repeatedLossSwitchesAutoToGenauAndStillCompletes() {
        val chat = makeChat(80)
        val dev = SimDevice(chat, Behavior.OVERSHOOT, 5)
        val r = run(chat, dev)
        assertComplete(chat, r, "Wechsel")
        assertTrue("Wechsel zu Genau erwartet: " + r.ctl.summary(), r.ctl.stats.switchesToGenau >= 1)
    }

    @Test
    fun genauModeIsGaplessEvenWithRandomFling() {
        val chat = makeChat(60)
        val dev = SimDevice(chat, Behavior.FLING, 9)
        val r = run(chat, dev, ScrollMethod.GENAU)
        assertComplete(chat, r, "Genau")
    }

    @Test
    fun actionOnlyModeUsesPageScrollsAndCompletes() {
        val chat = makeChat(80)
        val dev = SimDevice(chat, Behavior.RELIABLE, actionFraction = 0.85)
        val r = run(chat, dev, ScrollMethod.ACTION)
        assertComplete(chat, r, "ACTION")
        assertEquals(0, dev.swipes - dev.counterSwipes)
    }

    @Test
    fun autoSwitchesToActionWhenSwipeIsNotExecuted() {
        val chat = makeChat(60)
        val dev = SimDevice(chat, Behavior.NOT_EXECUTED, actionFraction = 0.85)
        val r = run(chat, dev)
        assertComplete(chat, r, "Swipe nicht ausgefuehrt")
        assertTrue(r.ctl.stats.switchesToAction >= 1)
    }

    @Test
    fun autoSwitchesToActionWhenSwipeHasNoEffect() {
        val chat = makeChat(60)
        val dev = SimDevice(chat, Behavior.INEFFECTIVE, actionFraction = 0.85)
        val r = run(chat, dev)
        assertComplete(chat, r, "Swipe wirkungslos")
        assertTrue(r.ctl.stats.switchesToAction >= 1)
    }

    @Test
    fun chatStartWithoutNoticeIsDetectedBySignalFromListState() {
        val chat = makeChat(40, withNotice = false)
        val dev = SimDevice(chat, Behavior.RELIABLE)
        val r = run(chat, dev)
        assertEquals(chat.msgs.map { it.text }, r.merger.messages.map { it.text })
        assertTrue(r.ctl.atStart)
    }

    // ---------- Version 0.1.4: end of the loaded history, loop protection ----------

    @Test
    fun staticPageWithoutNoticeAndWithoutScrollStateEndsCleanlyAsLoadedEnd() {
        val chat = makeChat(40, withNotice = false)
        val dev = SimDevice(chat, Behavior.RELIABLE, reportsCan = false)
        val r = run(chat, dev)
        assertEquals(chat.msgs.map { it.text }, r.merger.messages.map { it.text })
        assertEquals(ScrollController.EndKind.LOADED_END, r.ctl.endKind)
        assertTrue(r.ctl.atStart)
        assertEquals(0, r.ctl.stats.losses)
        assertEquals(0, r.ctl.stats.switchesToGenau + r.ctl.stats.switchesToAction)
        assertTrue("Wartezeit erwartet", r.ctl.stats.endWaits >= 1)
        assertTrue("Versuche begrenzt: ${r.ctl.stats.attempts}", dev.swipes <= 20)
        assertTrue(r.log.lines.any { it.contains("Anfang des geladenen Verlaufs") })
    }

    @Test
    fun delayedLoadingOfOlderMessagesIsAwaitedAndReadWithNextSwipe() {
        val older = makeChat(25, withNotice = false, first = 500).msgs
        val chat = makeChat(40, withNotice = false)
        val dev = SimDevice(chat, Behavior.RELIABLE, reportsCan = false, loadMore = older, loadDelayMs = 100)
        val r = run(chat, dev)
        assertEquals(older.map { it.text } + makeChat(40, withNotice = false).msgs.map { it.text }, r.merger.messages.map { it.text })
        assertEquals(ScrollController.EndKind.LOADED_END, r.ctl.endKind)
        assertEquals(0, r.merger.messages.count { it.kind == Kind.GAP })
        assertTrue(r.log.lines.any { it.contains("Ladehinweis gesehen") })
    }

    @Test
    fun actionThatReportsSuccessButMovesNothingDoesNotLoop() {
        val chat = makeChat(40, withNotice = false)
        val dev = SimDevice(chat, Behavior.RELIABLE, reportsCan = false, actionNoop = true)
        val r = run(chat, dev, ScrollMethod.ACTION)
        assertEquals(ScrollController.EndKind.LOADED_END, r.ctl.endKind)
        assertTrue("Aktionen begrenzt: ${dev.actions}", dev.actions <= 6)
        assertEquals(0, r.ctl.stats.losses)
    }

    @Test
    fun counterStepRestoringSamePageIsNotARecoveryAndDoesNotSwitchMode() {
        val chat = makeChat(5, withNotice = false)
        val dev = SimDevice(chat, Behavior.RELIABLE, reportsCan = false, glitch = true)
        val r = run(chat, dev)
        assertEquals(ScrollController.EndKind.LOADED_END, r.ctl.endKind)
        assertEquals(0, r.ctl.stats.losses)
        assertEquals(0, r.ctl.stats.recoveredByCounterStep)
        assertEquals(0, r.ctl.stats.switchesToGenau + r.ctl.stats.switchesToAction)
        assertEquals(ScrollController.Mode.ADAPTIV, r.ctl.mode)
        assertEquals(chat.msgs.map { it.text }, r.merger.messages.map { it.text })
    }

    @Test
    fun flickerBetweenKnownPagesStopsAfterFourStepsWithoutNewRows() {
        val chat = makeChat(80, withNotice = false)
        chat.viewTop = chat.total / 2
        val dev = SimDevice(chat, Behavior.FLICKER, reportsCan = false)
        val r = run(chat, dev)
        assertEquals(ScrollController.EndKind.STUCK, r.ctl.endKind)
        assertTrue("Schritte begrenzt: ${dev.swipes}", dev.swipes <= 6)
        assertTrue(r.merger.messages.none { it.kind == Kind.GAP })
    }

    @Test
    fun switchOffKeepsGoingUntilFourStepsWithoutNewRows() {
        val chat = makeChat(40, withNotice = false)
        val dev = SimDevice(chat, Behavior.RELIABLE, reportsCan = false)
        val r = run(chat, dev, cfgIn = fastCfg(ScrollMethod.AUTO).copy(endOnStatic = false))
        assertEquals(ScrollController.EndKind.STUCK, r.ctl.endKind)
        assertEquals(0, r.ctl.stats.endWaits)
        assertEquals(4, r.ctl.policy.failures)
    }
}
