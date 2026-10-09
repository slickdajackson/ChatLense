package app.chatlens

import app.chatlens.agent.ListHow
import app.chatlens.agent.ListLocator
import app.chatlens.checkup.CheckupDevice
import app.chatlens.checkup.CheckupScan
import app.chatlens.checkup.CheckupScanner
import app.chatlens.checkup.ScrollTry
import app.chatlens.checkup.StopReason
import app.chatlens.core.Bounds
import app.chatlens.core.UiNode
import app.chatlens.match.ChatListEntry
import app.chatlens.parse.ChatListParser
import app.chatlens.profile.SelectorProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/** Listenwahl (0.2.8): nachgebildete Baeume zum Fehler "Checkup scrollt nicht" aus 0.2.7, plus Quelltext-Waechter gegen waagerechtes Scrollen. */
class ListLocatorTest {
    private val profile = SelectorProfile.parse(File("src/main/assets/profiles/whatsapp.json").readText())
    private val NAME = "com.whatsapp:id/conversations_row_contact_name"
    private val CONT = "com.whatsapp:id/contact_row_container"
    private val screen = Bounds(0, 0, 1080, 2400)
    private val listB = Bounds(0, 260, 1080, 2300)

    private fun n(cls: String, b: Bounds, id: String? = null, text: String? = null, kids: List<UiNode> = emptyList(), scroll: Boolean = false,
                  up: Boolean = false, down: Boolean = false, horiz: Boolean = false, generic: Boolean = false) =
        UiNode(cls, id, text, null, b, scrollable = scroll, children = kids, scrollUp = up, scrollDown = down, scrollHoriz = horiz, scrollGeneric = generic)

    private fun row(i: Int): UiNode {
        val top = 300 + i * 200
        val b = Bounds(0, top, 1080, top + 190)
        return n("android.view.ViewGroup", b, CONT, kids = listOf(
            n("android.widget.TextView", Bounds(200, top + 20, 800, top + 80), NAME, "Person $i"),
            n("android.widget.TextView", Bounds(880, top + 20, 1050, top + 80), null, "12:00"),
            n("android.widget.TextView", Bounds(200, top + 100, 1000, top + 160), null, "Nachricht $i"),
        ))
    }

    private fun rows(k: Int = 9) = (0 until k).map { row(it) }

    private fun rowBounds(root: UiNode) = ChatListParser.parse(root, LocalDateTime.of(2026, 10, 3, 19, 0), profile.chatListRowNameIds, profile.chatListRowContainerIds).map { it.bounds }

    private fun choose(root: UiNode) = ListLocator.choose(root, rowBounds(root))

    /** Der Baum aus dem Fehlerlog: ViewPager mit scrollbarer Hille, Zeilen darunter, keine senkrechte Aktion irgendwo. */
    @Test fun listInsideViewPagerWithoutVerticalActionUsesRowGeometryAndNeverThePager() {
        val pager = n("androidx.viewpager.widget.ViewPager", screen, scroll = true, horiz = true, kids = listOf(n("android.view.ViewGroup", listB, kids = rows())))
        val root = n("android.widget.FrameLayout", screen, kids = listOf(pager))
        val ch = choose(root)
        assertEquals(ListHow.ROW_GEOMETRY, ch.how)
        assertNull(ch.node)
        assertNotNull(ch.bounds)
        assertTrue(ch.bounds!!.t >= 300 && ch.bounds!!.b <= 2300)
        assertTrue(ch.skipped.any { it.contains("ViewPager") })
    }

    @Test fun realRecyclerViewInsideViewPagerIsChosenByVerticalAction() {
        val rv = n("androidx.recyclerview.widget.RecyclerView", listB, scroll = true, down = true, generic = true, kids = rows())
        val pager = n("androidx.viewpager.widget.ViewPager", screen, scroll = true, horiz = true, generic = true, kids = listOf(rv))
        val ch = choose(n("android.widget.FrameLayout", screen, kids = listOf(pager)))
        assertEquals(ListHow.VERTICAL_ACTION, ch.how)
        assertEquals("androidx.recyclerview.widget.RecyclerView", ch.node!!.className)
        assertFalse(ch.allowGeneric)
    }

    @Test fun unusualClassWithOnlyGenericActionsIsTheRowAncestor() {
        val odd = n("com.whatsapp.conversationslist.ConversationsFastScroller", listB, scroll = true, generic = true, kids = rows())
        val pager = n("androidx.viewpager.widget.ViewPager", screen, scroll = true, horiz = true, generic = true, kids = listOf(odd))
        val ch = choose(n("android.widget.FrameLayout", screen, kids = listOf(pager)))
        assertEquals(ListHow.ROW_ANCESTOR, ch.how)
        assertEquals(odd.className, ch.node!!.className)
        assertTrue(ch.allowGeneric)
    }

    @Test fun noScrollableFlagsAtAllFallsBackToRowGeometry() {
        val root = n("android.widget.FrameLayout", screen, kids = listOf(n("android.view.ViewGroup", listB, kids = rows())))
        val ch = choose(root)
        assertEquals(ListHow.ROW_GEOMETRY, ch.how)
        assertNull(ch.node)
        assertEquals(300, ch.bounds!!.t)
        assertEquals(300 + 8 * 200 + 190, ch.bounds!!.b)
    }

    @Test fun flaggedScrollableWithoutVerticalActionIsUsedAsAncestor() {
        val root = n("android.widget.FrameLayout", screen, kids = listOf(n("android.view.ViewGroup", listB, scroll = true, kids = rows())))
        val ch = choose(root)
        assertEquals(ListHow.ROW_ANCESTOR, ch.how)
        assertTrue(ch.allowGeneric)
    }

    @Test fun horizontalChipRowIsNeverChosen() {
        val chips = n("android.widget.HorizontalScrollView", Bounds(0, 120, 1080, 250), scroll = true, horiz = true)
        val bar = n("androidx.recyclerview.widget.RecyclerView", Bounds(0, 120, 1080, 250), scroll = true, horiz = true)
        val root = n("android.widget.FrameLayout", screen, kids = listOf(chips, bar, n("android.view.ViewGroup", listB, kids = rows())))
        val ch = choose(root)
        assertEquals(ListHow.ROW_GEOMETRY, ch.how)
        assertNull(ch.node)
    }

    @Test fun pagerThatIsNearestScrollableAncestorOnlyGivesGeometry() {
        val pager = n("androidx.viewpager2.widget.ViewPager2", listB, scroll = true, generic = true, down = true, kids = rows())
        val ch = choose(n("android.widget.FrameLayout", screen, kids = listOf(pager)))
        assertEquals(ListHow.ROW_GEOMETRY, ch.how)
        assertNull(ch.node)
    }

    @Test fun noRowsAndNoScrollablesIsNone() {
        val ch = ListLocator.choose(n("android.widget.FrameLayout", screen), emptyList())
        assertEquals(ListHow.NONE, ch.how)
        assertNull(ch.bounds)
    }

    @Test fun describeListsClassIdBoundsActionsAndParentChain() {
        val rv = n("androidx.recyclerview.widget.RecyclerView", listB, "com.whatsapp:id/conversations_list", scroll = true, generic = true, kids = rows(2))
        val pager = n("androidx.viewpager.widget.ViewPager", screen, "com.whatsapp:id/pager", scroll = true, horiz = true, kids = listOf(rv))
        val lines = ListLocator.describeScrollables(n("android.widget.FrameLayout", screen, kids = listOf(n("android.view.ViewGroup", screen, kids = listOf(pager)))))
        assertEquals(2, lines.size)
        val l = lines[1]
        assertTrue(l, l.contains("androidx.recyclerview.widget.RecyclerView") && l.contains("conversations_list") && l.contains("[0,260][1080,2300]"))
        assertTrue(l, l.contains("FORWARD/BACKWARD") && l.contains("FrameLayout > ViewGroup > ViewPager:pager"))
        assertTrue(lines[0], lines[0].contains("LEFT/RIGHT"))
    }

    // ---------- Scanner: kein falsches Listenende ----------

    private fun entry(i: Int) = ChatListEntry("Chat $i", "V$i", "12:00", false, false, false, i)

    private class Dev(val rowsOnPage: List<ChatListEntry>, val step: ScrollTry) : CheckupDevice {
        var steps = 0
        val logs = ArrayList<String>()
        override suspend fun prepare() {}
        override suspend fun readVisible() = rowsOnPage
        override suspend fun scrollForward(precise: Boolean) = step == ScrollTry.DONE
        override suspend fun scrollStep(precise: Boolean): ScrollTry { steps++; return step }
        override suspend fun scrollBackward() = true
        override suspend fun chatsTabOk() = true
        override fun log(msg: String) { logs.add(msg) }
    }

    @Test fun noWayToScrollIsAFailureNotAListEnd() = runBlocking {
        val dev = Dev((0 until 9).map { entry(it) }, ScrollTry.NO_WAY)
        val r = CheckupScanner(dev).scan(50)
        assertEquals(StopReason.SCROLL_FAILED, r.reason)
        assertEquals(9, r.entries.size)
        assertEquals(1, r.pages)
        assertEquals(0, r.scrollAttempts)
        assertFalse(r.endProven)
        assertTrue(r.warning(50)!!.contains("Scrollen nicht moeglich"))
        assertTrue(dev.logs.any { it.contains("kein Listenende") })
    }

    @Test fun androidEndSignalAfterAnAttemptIsTheListEnd() = runBlocking {
        val r = CheckupScanner(Dev((0 until 9).map { entry(it) }, ScrollTry.END_SIGNAL)).scan(50)
        assertEquals(StopReason.END, r.reason)
        assertEquals(1, r.scrollAttempts)
        assertTrue(r.endProven)
    }

    @Test fun scrollsThatChangeNothingEndAfterTwoEmptyRounds() = runBlocking {
        val dev = Dev((0 until 9).map { entry(it) }, ScrollTry.DONE)
        val r = CheckupScanner(dev).scan(50)
        assertEquals(StopReason.END, r.reason)
        assertTrue(r.scrollAttempts >= 2)
        assertTrue(r.endProven)
    }

    @Test fun warningOnlyForFewRowsWithoutProofAndNeverAtLimit() {
        val few = CheckupScan((0 until 9).map { entry(it) }, StopReason.END, 1, 0, 0, false, 0)
        assertTrue(few.warning(50)!!.contains("nicht erwiesen"))
        val proven = CheckupScan((0 until 9).map { entry(it) }, StopReason.END, 2, 0, 0, false, 3)
        assertTrue(proven.warning(50)!!.contains("9 Zeilen"))
        val many = CheckupScan((0 until 40).map { entry(it) }, StopReason.END, 5, 0, 0, false, 5)
        assertNull(many.warning(50))
        val lim = CheckupScan((0 until 10).map { entry(it) }, StopReason.LIMIT, 2, 0, 0, false, 2)
        assertNull(lim.warning(10))
    }

    // ---------- Quelltext-Waechter ----------

    private val nav = File("src/main/kotlin/app/chatlens/agent/WhatsAppNavigator.kt").readText()
    private val locator = File("src/main/kotlin/app/chatlens/agent/ListLocator.kt").readText()

    @Test fun sourceNeverScrollsHorizontally() {
        assertFalse(nav.contains("SCROLL_LEFT") || nav.contains("SCROLL_RIGHT") || nav.contains("SCROLL_TO_POSITION"))
        // Allgemeines FORWARD/BACKWARD nur in listAction und nur hinter allowGeneric
        val outside = nav.replace(nav.substringAfter("private fun listAction").substringBefore("/** Ein Scrollschritt aufwaerts"), "")
        assertFalse(outside.contains("ACTION_SCROLL_FORWARD") || outside.contains("ACTION_SCROLL_BACKWARD"))
        val la = nav.substringAfter("private fun listAction").substringBefore("/** Ein Scrollschritt aufwaerts")
        assertTrue(la.contains("ch.allowGeneric && generic in ids"))
        // die Wischgeste hat nur eine x-Koordinate: senkrecht
        val svc = File("src/main/kotlin/app/chatlens/service/ChatAccessibilityService.kt").readText()
        assertTrue(svc.contains("suspend fun swipe(x: Int, fromY: Int, toY: Int, durationMs: Long, holdMs: Long)"))
        assertFalse(svc.contains("fromX") || svc.contains("toX"))
        // Seitenwechsler und waagerechte Knoten sind aus der Listenwahl ausgeschlossen
        assertTrue(locator.contains("\"Pager\"") && locator.contains("HorizontalScrollView") && locator.contains("val usable"))
        assertTrue(File("src/main/kotlin/app/chatlens/agent/AndroidScrollDevice.kt").readText().contains("\"ViewPager\""))
    }

    @Test fun sourceNeverReportsEndWithoutAttemptAndLogsRefusals() {
        val cu = File("src/main/kotlin/app/chatlens/checkup/Checkup.kt").readText()
        assertTrue(cu.contains("ScrollTry.NO_WAY ->") && cu.contains("StopReason.SCROLL_FAILED"))
        assertTrue(nav.contains("Wischgeste verweigert") && nav.contains("logScrollables"))
        val run = File("src/main/kotlin/app/chatlens/agent/AutoRunner.kt").readText()
        assertTrue(run.contains("StopReason.SCROLL_FAILED") && run.contains("throw AgentException(\"Checkup unvollstaendig"))
    }
}
