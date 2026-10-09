package app.chatlens

import app.chatlens.agent.ScrollPlan
import app.chatlens.agent.ScrollStopPolicy
import app.chatlens.core.Bounds
import app.chatlens.core.ChatMessage
import app.chatlens.core.Direction
import app.chatlens.core.Kind
import app.chatlens.core.PageItem
import app.chatlens.core.UiNode
import app.chatlens.llm.ContextBuilder
import app.chatlens.parse.ChatParser
import app.chatlens.parse.TranscriptMerger
import app.chatlens.profile.SelectorProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests fuer Randnachrichten, Zusammenfuehren, Schrittweite und Abbruchregel auf synthetischen Daten.
 * Sie pruefen die Logik und die Geometrie, nicht das echte WhatsApp-Verhalten.
 */
class ScrollAndEdgeTest {

    private val profile = SelectorProfile.parse(File("src/main/assets/profiles/whatsapp.json").readText())
    private val density = 3f
    private val parser = ChatParser(profile, density, false)

    // ---------- Hilfen fuer Seiten ----------

    private fun m(text: String, time: String, incomplete: Boolean = false, dir: Direction = Direction.IN, kind: Kind = Kind.TEXT) =
        PageItem(ChatMessage(kind, dir, null, text, time).also { it.incomplete = incomplete })

    // ---------- Merger ----------

    @Test
    fun clippedVersionIsReplacedByCompleteVersionOnNextPage() {
        val merger = TranscriptMerger()
        // Seite 0 (neueste): oberste Zeile ist am oberen Rand angeschnitten
        val p0 = listOf(m("Das ist eine laengere Nachricht", "10:03", incomplete = true), m("d", "10:04"), m("e", "10:05"))
        merger.add(p0)
        assertEquals(1, merger.incompleteCount())
        // Seite 1 (aelter): unterste Zeile ist unten angeschnitten ("d"), "c" jetzt vollstaendig
        val p1 = listOf(m("a", "10:01"), m("b", "10:02"), m("Das ist eine laengere Nachricht", "10:03"), m("d", "10:04", incomplete = true))
        val r = merger.add(p1)
        assertEquals(false, r.noOverlap)
        assertEquals(2, r.added)
        assertEquals(1, r.completed)
        assertEquals(2, r.overlapRows)
        assertEquals(listOf("a", "b", "Das ist eine laengere Nachricht", "d", "e"), merger.messages.map { it.text })
        assertEquals(0, merger.incompleteCount())
    }

    @Test
    fun prefixAndPartialTextCountAsSameMessageOnlyWhenOneSideIsClipped() {
        val full = ChatMessage(Kind.TEXT, Direction.IN, null, "Hallo wie geht es dir heute", "09:00")
        val clipped = ChatMessage(Kind.TEXT, Direction.IN, null, "Hallo wie ge", "09:00").also { it.incomplete = true }
        val suffix = ChatMessage(Kind.TEXT, Direction.IN, null, "es dir heute", "09:00").also { it.incomplete = true }
        assertTrue(TranscriptMerger.compatible(full, clipped))
        assertTrue(TranscriptMerger.compatible(clipped, full))
        assertTrue(TranscriptMerger.compatible(full, suffix))
        // zwei vollstaendige Fassungen muessen exakt gleich sein
        val complete = ChatMessage(Kind.TEXT, Direction.IN, null, "Hallo wie ge", "09:00")
        assertFalse(TranscriptMerger.compatible(full, complete))
        // andere Uhrzeit oder Richtung widerspricht auch bei angeschnittener Fassung
        val otherTime = ChatMessage(Kind.TEXT, Direction.IN, null, "Hallo wie ge", "09:01").also { it.incomplete = true }
        assertFalse(TranscriptMerger.compatible(full, otherTime))
        val otherDir = ChatMessage(Kind.TEXT, Direction.OUT, null, "Hallo wie ge", "09:00").also { it.incomplete = true }
        assertFalse(TranscriptMerger.compatible(full, otherDir))
        // fehlende Uhrzeit bei angeschnittener Fassung (Zeitknoten verdeckt) ist erlaubt
        val noTime = ChatMessage(Kind.SYSTEM, Direction.UNKNOWN, null, "Hallo wie ge", null).also { it.incomplete = true }
        assertTrue(TranscriptMerger.compatible(full, noTime))
    }

    @Test
    fun noOverlapWithoutGapLeavesCollectionUntouched() {
        val merger = TranscriptMerger()
        merger.add(listOf(m("x", "10:03"), m("y", "10:04")))
        val r = merger.add(listOf(m("a", "09:00"), m("b", "09:01")), allowGap = false)
        assertTrue(r.noOverlap)
        assertEquals(0, r.added)
        assertEquals(listOf("x", "y"), merger.messages.map { it.text })
        val r2 = merger.add(listOf(m("a", "09:00"), m("b", "09:01")), allowGap = true)
        assertTrue(r2.gapInserted)
        assertEquals(Kind.GAP, merger.messages[2].kind)
    }

    @Test
    fun identicalPageAddsNothing() {
        val merger = TranscriptMerger()
        val page = listOf(m("a", "10:01"), m("b", "10:02"), m("c", "10:03"))
        merger.add(page)
        val r = merger.add(listOf(m("a", "10:01"), m("b", "10:02"), m("c", "10:03")))
        assertEquals(0, r.added)
        assertEquals(3, merger.messages.size)
    }

    @Test
    fun trimIncompleteHeadKeepsTarget() {
        val merger = TranscriptMerger()
        merger.add(listOf(m("a", "10:01", incomplete = true), m("b", "10:02"), m("c", "10:03")))
        assertEquals(0, merger.trimIncompleteHead(3))
        assertEquals(3, merger.messages.size)
        assertEquals(1, merger.trimIncompleteHead(2))
        assertEquals(listOf("b", "c"), merger.messages.map { it.text })
    }

    @Test
    fun absorbKeepsLongerTextWhenBothClipped() {
        val a = ChatMessage(Kind.TEXT, Direction.IN, null, "Hallo", "09:00").also { it.incomplete = true }
        val b = ChatMessage(Kind.TEXT, Direction.IN, null, "Hallo wie geht", "09:00").also { it.incomplete = true }
        assertFalse(a.absorb(b))
        assertEquals("Hallo wie geht", a.text)
        assertTrue(a.incomplete)
    }

    // ---------- Parser: Rand und "Mehr lesen" ----------

    private fun tv(text: String, l: Int, t: Int, r: Int, b: Int, visible: Boolean = true) =
        UiNode("android.widget.TextView", null, text, null, if (visible) Bounds(l, t, r, b) else Bounds(0, 0, 0, 0), visible = visible)

    private fun list(vararg rows: UiNode) = UiNode(
        "android.widget.FrameLayout", null, null, null, Bounds(0, 0, 1080, 2400),
        children = listOf(
            UiNode("androidx.recyclerview.widget.RecyclerView", null, null, null, Bounds(0, 200, 1080, 2000), scrollable = true, children = rows.toList()),
        ),
    )

    @Test
    fun rowCutAtBottomEdgeIsMarkedIncompleteAndHiddenTimeIsRecovered() {
        // Zeile reicht von 1900 bis zum Listenende 2000; Text angeschnitten, Uhrzeit ausgeblendet
        val row = UiNode(
            "android.view.ViewGroup", null, null, null, Bounds(0, 1900, 1080, 2000),
            children = listOf(tv("Ich komme gleich vorbei und bringe alles mit", 40, 1920, 560, 2000), tv("11:20", 0, 0, 0, 0, visible = false)),
        )
        val page = parser.parse(list(row))
        val msg = page.items.single().message
        assertEquals(Kind.TEXT, msg.kind)
        assertEquals("11:20", msg.time)
        assertTrue(msg.incomplete)
    }

    @Test
    fun fullyVisibleRowIsNotIncompleteEvenIfRowTouchesEdge() {
        // Zeilenrand liegt genau am Listenrand, der Inhalt hat aber Abstand: vollstaendig
        val row = UiNode(
            "android.view.ViewGroup", null, null, null, Bounds(0, 1800, 1080, 2000),
            children = listOf(tv("Alles gut", 40, 1830, 400, 1890), tv("11:21", 460, 1920, 540, 1960)),
        )
        val msg = parser.parse(list(row)).items.single().message
        assertFalse(msg.incomplete)
        assertEquals("11:21", msg.time)
    }

    @Test
    fun rowCutAtTopEdgeIsMarkedIncomplete() {
        val row = UiNode(
            "android.view.ViewGroup", null, null, null, Bounds(0, 200, 1080, 320),
            children = listOf(tv("Ende der Nachricht", 40, 200, 560, 260), tv("08:15", 460, 280, 540, 320)),
        )
        assertTrue(parser.parse(list(row)).items.single().message.incomplete)
    }

    @Test
    fun readMoreIsDetectedAndRemovedFromText() {
        val row = UiNode(
            "android.view.ViewGroup", null, null, null, Bounds(0, 400, 1080, 900),
            children = listOf(
                tv("Das ist der sichtbare Anfang einer sehr langen Nachricht", 40, 420, 1000, 700),
                tv("Mehr lesen", 40, 720, 300, 770),
                tv("12:00", 940, 800, 1040, 850),
            ),
        )
        val msg = parser.parse(list(row)).items.single().message
        assertTrue(msg.truncated)
        assertEquals("Das ist der sichtbare Anfang einer sehr langen Nachricht", msg.text)
        assertFalse(msg.incomplete)
        val line = ContextBuilder.line(msg, "Anna", null)
        assertTrue(line, line.contains("[gekürzt"))
    }

    @Test
    fun readMoreSuffixInsideTextIsStripped() {
        assertEquals("Anfang des Textes", profile.stripReadMoreSuffix("Anfang des Textes … Mehr lesen"))
        assertEquals(null, profile.stripReadMoreSuffix("Mehr lesen"))
        assertTrue(profile.isReadMore("… Mehr lesen"))
        assertTrue(profile.isReadMore("Read more"))
        assertFalse(profile.isReadMore("Ich will mehr lesen als das"))
    }

    @Test
    fun contextMarksIncompleteAndCountsInNotes() {
        val a = ChatMessage(Kind.TEXT, Direction.IN, null, "abgeschnitten", "10:00").also { it.incomplete = true }
        val b = ChatMessage(Kind.TEXT, Direction.OUT, null, "lang", "10:01").also { it.truncated = true }
        val ctx = ContextBuilder.build(listOf(a, b), "Anna", 10_000, false, 0)
        assertTrue(ctx.transcript.contains("[angeschnitten"))
        assertTrue(ctx.transcript.contains("[gekürzt"))
        assertEquals(2, ctx.notes.count { it.contains("markiert") })
    }

    @Test
    fun chatStartNoticeIsRecognised() {
        assertTrue(profile.isChatStartNotice("Nachrichten und Anrufe sind Ende-zu-Ende-verschlüsselt. Niemand außerhalb dieses Chats kann sie lesen."))
        assertTrue(profile.isChatStartNotice("Messages and calls are end-to-end encrypted."))
        assertFalse(profile.isChatStartNotice("Bis morgen"))
    }

    @Test
    fun signatureChangesWithScrollPositionButNotWithSameView() {
        fun row(top: Int, text: String) = UiNode(
            "android.view.ViewGroup", null, null, null, Bounds(0, top, 1080, top + 140),
            children = listOf(tv(text, 40, top + 10, 560, top + 70), tv("10:00", 460, top + 90, 540, top + 130)),
        )
        val a = parser.signature(list(row(300, "eins"), row(500, "zwei")))!!
        val same = parser.signature(list(row(300, "eins"), row(500, "zwei")))!!
        val moved = parser.signature(list(row(420, "eins"), row(620, "zwei")))!!
        val other = parser.signature(list(row(300, "drei"), row(500, "zwei")))!!
        assertEquals(a, same)
        assertEquals(a.content, moved.content)
        assertNotEquals(a.layout, moved.layout)
        assertNotEquals(a.content, other.content)
    }

    // ---------- Schrittweite ----------

    @Test
    fun swipeStepNeverExceedsSeventyPercentAndStaysInsideList() {
        val list = Bounds(0, 200, 1080, 2000)
        for (f in listOf(0.1, 0.3, 0.6, 0.7, 0.9, 2.0)) {
            for (sw in listOf(ScrollPlan.older(list, f), ScrollPlan.newer(list, f))) {
                assertTrue("f=$f ${sw.distance}", sw.distance <= (list.height * 0.70).toInt() + 1)
                assertTrue(sw.fromY in list.t..list.b)
                assertTrue(sw.toY in list.t..list.b)
                assertTrue(sw.x in list.l..list.r)
            }
        }
        val s = ScrollPlan.older(list, 0.6)
        assertEquals((1800 * 0.6).toInt(), s.distance)
        assertTrue(s.toY > s.fromY) // Finger nach unten = aeltere Nachrichten
        assertTrue(ScrollPlan.newer(list, 0.6).toY < ScrollPlan.newer(list, 0.6).fromY)
    }

    @Test
    fun everyMessageUpToThirtyPercentOfListHeightIsFullyVisibleAtLeastOnce() {
        val h = 1800
        val step = (h * 0.70).toInt()
        // Nachrichten mit Hoehen von 60 bis 540 (30 Prozent von 1800), lueckenlos gestapelt
        val heights = (0 until 80).map { 60 + (it * 37) % 481 }
        val tops = ArrayList<Int>()
        var y = 0
        for (hh in heights) { tops.add(y); y += hh }
        val total = y
        var viewTop = total - h
        val seen = BooleanArray(heights.size)
        while (true) {
            val vt = viewTop.coerceAtLeast(0)
            for (i in heights.indices) if (tops[i] >= vt && tops[i] + heights[i] <= vt + h) seen[i] = true
            if (vt == 0) break
            viewTop -= step
        }
        assertTrue("nicht komplett gesehen: " + seen.indices.filter { !seen[it] }, seen.all { it })
    }

    // ---------- Abbruchregel ----------

    @Test
    fun stopsOnlyAfterThreeFailuresAndAStartSignal() {
        val p = ScrollStopPolicy()
        p.record(false); p.record(false)
        assertEquals(ScrollStopPolicy.Decision.CONTINUE, p.decide(true))
        p.record(false)
        assertTrue(p.wantsLongWait())
        assertEquals(ScrollStopPolicy.Decision.CONTINUE, p.decide(false))
        assertEquals(ScrollStopPolicy.Decision.CHAT_START, p.decide(true))
    }

    @Test
    fun successResetsFailuresAndHardLimitMeansStuckNotChatStart() {
        val p = ScrollStopPolicy()
        p.record(false); p.record(false); p.record(true)
        assertEquals(0, p.failures)
        repeat(5) { p.record(false) }
        assertEquals(ScrollStopPolicy.Decision.CONTINUE, p.decide(false))
        p.record(false)
        assertEquals(ScrollStopPolicy.Decision.STUCK, p.decide(false))
    }

    // ---------- Gesamtablauf auf simuliertem Chat ----------

    private class Msg(val text: String, val time: String, val out: Boolean, val h: Int)

    private fun clip(t: Int, b: Int, lt: Int, lb: Int): Pair<Int, Int>? {
        val a = maxOf(t, lt)
        val c = minOf(b, lb)
        return if (c > a) a to c else null
    }

    /** Baut den Baum fuer einen Ausschnitt der Chat-Inhaltskoordinaten [viewTop, viewTop + H). Randknoten werden wie in Android auf die Liste beschnitten. */
    private fun frame(msgs: List<Msg>, tops: List<Int>, viewTop: Int, prefixClip: Boolean): UiNode {
        val lt = 200
        val H = 1800
        val lb = lt + H
        val rows = ArrayList<UiNode>()
        for (i in msgs.indices) {
            val mg = msgs[i]
            val rt = lt + tops[i] - viewTop
            val rb = rt + mg.h
            val rowC = clip(rt, rb, lt, lb) ?: continue
            val tNat = rt + 20
            val tEnd = rb - 70
            val timeNat = rb - 60
            val timeEnd = rb - 20
            val l = if (mg.out) 520 else 40
            val r = if (mg.out) 1040 else 560
            val tl = if (mg.out) 940 else 460
            val tr = if (mg.out) 1020 else 540
            val tc = clip(tNat, tEnd, lt, lb)
            val ti = clip(timeNat, timeEnd, lt, lb)
            var text = mg.text
            if (prefixClip && tc != null && (tc.second - tc.first) < (tEnd - tNat)) {
                val frac = (tc.second - tc.first).toDouble() / (tEnd - tNat)
                text = mg.text.take((mg.text.length * frac).toInt().coerceAtLeast(3))
            }
            val textNode = if (tc != null) tv(text, l, tc.first, r, tc.second) else tv(mg.text, 0, 0, 0, 0, visible = false)
            val timeNode = if (ti != null) tv(mg.time, tl, ti.first, tr, ti.second) else tv(mg.time, 0, 0, 0, 0, visible = false)
            rows.add(UiNode("android.view.ViewGroup", null, null, null, Bounds(0, rowC.first, 1080, rowC.second), children = listOf(textNode, timeNode)))
        }
        return list(*rows.toTypedArray())
    }

    private fun simulate(stepFraction: Double, prefixClip: Boolean): Triple<List<Msg>, TranscriptMerger, Int> {
        val msgs = (0 until 60).map { i ->
            Msg("Nachricht Nummer ${1000 + i} mit etwas Text dahinter", "%02d:%02d".format(8 + i / 60, i % 60), i % 3 == 0, 150 + (i * 53) % 260)
        }
        val tops = ArrayList<Int>()
        var y = 0
        for (mg in msgs) { tops.add(y); y += mg.h }
        val total = y
        val merger = TranscriptMerger()
        val step = (1800 * stepFraction).toInt()
        var viewTop = total - 1800
        var noOverlaps = 0
        var first = true
        while (true) {
            val vt = viewTop.coerceAtLeast(0)
            val page = parser.parse(frame(msgs, tops, vt, prefixClip))
            val r = merger.add(page.items, allowGap = false)
            if (r.noOverlap && !first) noOverlaps++
            first = false
            if (vt == 0) break
            viewTop -= step
        }
        return Triple(msgs, merger, noOverlaps)
    }

    @Test
    fun simulatedScrollWithSixtyPercentStepCapturesEveryMessageOnceInOrder() {
        for (prefix in listOf(false, true)) {
            val (msgs, merger, noOverlaps) = simulate(0.6, prefix)
            assertEquals("prefix=$prefix", 0, noOverlaps)
            assertEquals("prefix=$prefix", msgs.map { it.text }, merger.messages.map { it.text })
            assertEquals("prefix=$prefix", msgs.map { it.time }, merger.messages.map { it.time })
            assertEquals("prefix=$prefix", 0, merger.incompleteCount())
            assertTrue(merger.messages.none { it.kind == Kind.GAP })
            assertNotNull(merger.messages.firstOrNull())
        }
    }

    @Test
    fun fullPageStepKeepsOrderAndUnionOfOppositeClipsCompletesStraddlingMessages() {
        // Bei 100 Prozent Schrittweite gibt es keine gemeinsame vollstaendige Sicht. Ab 0.1.3 ergeben aber die zwei Anschnitte
        // (oben auf der einen, unten auf der anderen Seite) zusammen die ganze Nachricht, solange der Text gleich ist.
        val (msgs, merger, _) = simulate(1.0, false)
        assertEquals(msgs.map { it.text }, merger.messages.map { it.text })
        assertTrue(merger.messages.none { it.kind == Kind.GAP })
        assertEquals(0, merger.incompleteCount())
    }

    @Test
    fun fullPageStepWithPrefixClipLeavesStraddlingMessagesIncomplete() {
        // Zeigt der Anschnitt nur einen Textanfang (Prefix), ist die Vereinigung nicht moeglich: Nachricht bleibt markiert.
        val (msgs, merger, _) = simulate(1.0, true)
        assertEquals(msgs.map { it.text }.size, merger.messages.size)
        assertTrue("erwartet angeschnittene Nachrichten", merger.incompleteCount() > 0)
    }
}
