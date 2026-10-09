package app.chatlens

import app.chatlens.core.Bounds
import app.chatlens.core.ChatMessage
import app.chatlens.core.Direction
import app.chatlens.core.Kind
import app.chatlens.core.PageItem
import app.chatlens.core.TreeDump
import app.chatlens.core.UiNode
import app.chatlens.llm.ContextBuilder
import app.chatlens.parse.ChatParser
import app.chatlens.parse.TranscriptMerger
import app.chatlens.profile.SelectorProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests auf synthetischen Baeumen. Sie pruefen die Logik, nicht das echte WhatsApp-Layout.
 * Der echte Aufbau ist unbekannt und muss auf dem Geraet kalibriert werden.
 */
class ParserAndMergerTest {

    private val profile = SelectorProfile.parse(File("src/main/assets/profiles/whatsapp.json").readText())
    private val density = 3f

    private fun tv(text: String, l: Int, t: Int, r: Int, b: Int, id: String? = null) =
        UiNode("android.widget.TextView", id, text, null, Bounds(l, t, r, b))

    private fun row(l: Int, t: Int, r: Int, b: Int, vararg kids: UiNode) =
        UiNode("android.view.ViewGroup", null, null, null, Bounds(l, t, r, b), children = kids.toList())

    private fun screen(vararg rows: UiNode): UiNode {
        val list = UiNode(
            "androidx.recyclerview.widget.RecyclerView", "com.whatsapp:id/list", null, null,
            Bounds(0, 200, 1080, 2100), scrollable = true, children = rows.toList(),
        )
        val input = UiNode("android.widget.EditText", null, null, null, Bounds(100, 2200, 900, 2300), editable = true)
        return UiNode("android.widget.FrameLayout", null, null, null, Bounds(0, 0, 1080, 2400), children = listOf(list, input))
    }

    private fun incoming(text: String, time: String, top: Int) = row(
        0, top, 1080, top + 140,
        tv(text, 40, top + 10, 560, top + 70), tv(time, 480, top + 90, 560, top + 130),
    )

    private fun outgoing(text: String, time: String, top: Int) = row(
        0, top, 1080, top + 140,
        tv(text, 520, top + 10, 1040, top + 70), tv(time, 960, top + 90, 1040, top + 130),
    )

    @Test
    fun parsesDirectionTimeAndDate() {
        val root = screen(
            row(0, 210, 1080, 270, tv("Gestern", 480, 215, 600, 265)),
            incoming("Hallo, hast du Zeit?", "10:15", 300),
            outgoing("Ja, gleich.", "10:16", 460),
        )
        val page = ChatParser(profile, density, false).parse(root)
        assertTrue(page.listFound)
        assertEquals(3, page.items.size)
        assertEquals(Kind.DATE, page.items[0].message.kind)
        val a = page.items[1].message
        assertEquals(Kind.TEXT, a.kind); assertEquals(Direction.IN, a.direction)
        assertEquals("Hallo, hast du Zeit?", a.text); assertEquals("10:15", a.time)
        val b = page.items[2].message
        assertEquals(Direction.OUT, b.direction); assertEquals("10:16", b.time)
    }

    @Test
    fun detectsImageVoiceAndSystem() {
        val img = row(
            0, 300, 1080, 1000,
            UiNode("android.widget.ImageView", null, null, null, Bounds(40, 310, 700, 900)),
            tv("14:02", 620, 920, 700, 980),
        )
        val voice = row(
            0, 1020, 1080, 1160,
            UiNode("android.widget.SeekBar", null, null, null, Bounds(520, 1030, 900, 1090)),
            tv("0:12", 520, 1100, 600, 1140), tv("14:03", 960, 1100, 1040, 1140),
        )
        val sys = row(0, 1200, 1080, 1300, tv("Nachrichten sind Ende-zu-Ende verschluesselt", 100, 1210, 980, 1290))
        val page = ChatParser(profile, density, false).parse(screen(img, voice, sys))
        assertEquals(listOf(Kind.IMAGE, Kind.VOICE, Kind.SYSTEM), page.items.map { it.message.kind })
        assertTrue(page.items[0].imageFullyVisible)
        assertEquals(Direction.IN, page.items[0].message.direction)
        assertEquals(Direction.OUT, page.items[1].message.direction)
        assertEquals("14:03", page.items[1].message.time)
    }

    @Test
    fun groupSenderOnlyWhenEnabled() {
        val r = row(
            0, 300, 1080, 480,
            tv("Anna", 40, 305, 200, 345), tv("Treffen wir uns?", 40, 350, 560, 410), tv("09:00", 480, 430, 560, 470),
        )
        val off = ChatParser(profile, density, false).parse(screen(r)).items[0].message
        assertNull(off.sender); assertTrue(off.text.contains("Anna"))
        val on = ChatParser(profile, density, true).parse(screen(r)).items[0].message
        assertEquals("Anna", on.sender); assertEquals("Treffen wir uns?", on.text)
    }

    private fun msg(text: String, time: String) = PageItem(ChatMessage(Kind.TEXT, Direction.IN, null, text, time))

    @Test
    fun mergerPrependsOlderAndDeduplicates() {
        val m = TranscriptMerger()
        val p0 = listOf(msg("c", "10:03"), msg("d", "10:04"), msg("e", "10:05"))
        val r0 = m.add(p0)
        assertEquals(3, r0.added)
        val p1 = listOf(msg("a", "10:01"), msg("b", "10:02"), msg("c", "10:03"), msg("d", "10:04"))
        val r1 = m.add(p1)
        assertEquals(2, r1.added)
        assertEquals(listOf("a", "b", "c", "d", "e"), m.messages.map { it.text })
        // kanonisches Objekt fuer c ist das erste
        assertTrue(r1.canonical[2] === p0[0].message)
        val r2 = m.add(p1)
        assertEquals(0, r2.added)
        assertEquals(5, m.messages.size)
    }

    @Test
    fun mergerMarksGapWhenNoOverlap() {
        val m = TranscriptMerger()
        m.add(listOf(msg("x", "10:03"), msg("y", "10:04")))
        val r = m.add(listOf(msg("a", "09:00"), msg("b", "09:01")))
        assertTrue(r.gapInserted)
        assertEquals(Kind.GAP, m.messages[2].kind)
        assertEquals(5, m.messages.size)
    }

    @Test
    fun contextKeepsNewestWhenOverLimit() {
        val msgs = (1..50).map { ChatMessage(Kind.TEXT, Direction.IN, null, "Nachricht Nummer $it", "10:%02d".format(it)) }
        val ctx = ContextBuilder.build(msgs, "Anna", 400, false, 0)
        assertTrue(ctx.droppedOldest > 0)
        assertTrue(ctx.transcript.endsWith("Nachricht Nummer 50"))
        assertFalse(ctx.transcript.contains("Nummer 1\n"))
    }

    @Test
    fun maskKeepsTimeAndDate() {
        assertEquals("14:35", TreeDump.mask("14:35", profile))
        assertEquals("Heute", TreeDump.mask("Heute", profile))
        assertEquals("Xxxx Xxxxx 99", TreeDump.mask("Hans Meyer 12", profile))
    }

    @Test
    fun profileLoads() {
        assertEquals("com.whatsapp", profile.packageName)
        assertEquals("NOT_CALIBRATED", profile.calibrationStatus)
        assertNotNull(profile.searchButtonIds)
        assertTrue(profile.isTimeText("9:05"))
        assertTrue(profile.isTimeText("9:05 PM"))
        assertTrue(profile.isDateLabel("12. März 2026"))
        assertTrue(profile.isDateLabel("03.10.2026"))
    }
}
