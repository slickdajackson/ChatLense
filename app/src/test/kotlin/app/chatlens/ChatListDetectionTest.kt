package app.chatlens

import app.chatlens.agent.AgentException
import app.chatlens.agent.ChatListEnsurer
import app.chatlens.agent.ListDevice
import app.chatlens.agent.ListScreen
import app.chatlens.agent.ScreenState
import app.chatlens.agent.TabBar
import app.chatlens.core.Bounds
import app.chatlens.core.UiNode
import app.chatlens.parse.ChatListParser
import app.chatlens.profile.SelectorProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

class ChatListDetectionTest {
    private val profile = SelectorProfile.parse(File("src/main/assets/profiles/whatsapp.json").readText())
    private val now = LocalDateTime.of(2026, 10, 3, 19, 0)
    private val NAME = "com.whatsapp:id/conversations_row_contact_name"
    private val CONT = "com.whatsapp:id/contact_row_container"

    private fun n(cls: String, b: Bounds, id: String? = null, text: String? = null, kids: List<UiNode> = emptyList(), editable: Boolean = false,
                  scroll: Boolean = false, focused: Boolean = false, clickable: Boolean = false) =
        UiNode(cls, id, text, null, b, clickable = clickable, scrollable = scroll, editable = editable, visible = true, children = kids, focused = focused)

    private fun txt(b: Bounds, t: String, id: String? = null) = n("android.widget.TextView", b, id, t)

    /** One row of the chat list with a container id, nested as in a real app (container, layout, texts). */
    private fun row(top: Int, title: String, time: String, preview: String, withContainerId: Boolean = true, nested: Boolean = true): UiNode {
        val b = Bounds(0, top, 1080, top + 190)
        val texts = listOf(
            txt(Bounds(200, top + 20, 800, top + 80), title, NAME),
            txt(Bounds(880, top + 20, 1050, top + 80), time),
            txt(Bounds(200, top + 100, 1000, top + 160), preview),
        )
        val inner = if (nested) n("android.widget.LinearLayout", b, kids = listOf(n("android.widget.LinearLayout", b, kids = texts))) else n("android.widget.LinearLayout", b, kids = texts)
        return n("android.view.ViewGroup", b, id = if (withContainerId) CONT else null, kids = listOf(inner), clickable = true)
    }

    private val screen = Bounds(0, 0, 1080, 2400)

    /** Chat list: a quiet search bar at the top (editable, without focus), and a list with one wrapper node. */
    private fun listScreen(rowsN: Int = 6, withContainerIds: Boolean = true): UiNode {
        val rows = (0 until rowsN).map { row(300 + it * 200, "Person $it", "1$it:00", "Nachricht $it", withContainerIds) }
        val searchPill = n("android.widget.EditText", Bounds(40, 120, 1040, 240), id = "com.whatsapp:id/search_pill", editable = true)
        val list = n("androidx.recyclerview.widget.RecyclerView", Bounds(0, 260, 1080, 2300), scroll = true, kids = rows)
        return n("android.widget.FrameLayout", screen, kids = listOf(searchPill, list))
    }

    private fun searchScreen(): UiNode = n(
        "android.widget.FrameLayout", screen,
        kids = listOf(
            n("android.widget.EditText", Bounds(100, 100, 1000, 220), id = "com.whatsapp:id/search_src_text", editable = true, focused = true),
            n("androidx.recyclerview.widget.RecyclerView", Bounds(0, 260, 1080, 1400), scroll = true, kids = listOf(row(300, "Treffer", "", "x"))),
        ),
    )

    private fun chatScreen(): UiNode = n(
        "android.widget.FrameLayout", screen,
        kids = listOf(
            n("androidx.recyclerview.widget.RecyclerView", Bounds(0, 200, 1080, 2000), scroll = true, kids = listOf(txt(Bounds(10, 300, 900, 360), "Hallo"))),
            n("android.widget.EditText", Bounds(100, 2100, 900, 2250), id = "com.whatsapp:id/entry", editable = true),
        ),
    )

    private fun unknownScreen(): UiNode = n("android.widget.FrameLayout", screen, kids = listOf(txt(Bounds(0, 500, 500, 560), "Irgendein Bildschirm")))

    @Test fun listWithQuietSearchPillIsListNotSearch() {
        assertEquals(ScreenState.LIST, ListScreen.classify(listScreen(), profile, imeVisible = false))
    }

    @Test fun focusedOrKeyboardSearchIsSearchActive() {
        assertEquals(ScreenState.SEARCH_ACTIVE, ListScreen.classify(searchScreen(), profile, false))
        assertEquals(ScreenState.SEARCH_ACTIVE, ListScreen.classify(listScreen(), profile, imeVisible = true))
    }

    @Test fun chatAndUnknownAndNull() {
        assertEquals(ScreenState.CHAT, ListScreen.classify(chatScreen(), profile, true))
        assertEquals(ScreenState.OTHER, ListScreen.classify(unknownScreen(), profile, false))
        assertEquals(ScreenState.NOT_WHATSAPP, ListScreen.classify(null, profile, false))
    }

    @Test fun listIsRecognisedByIdsEvenWithoutContainerIdsOrScrollableList() {
        val one = n("android.widget.FrameLayout", screen, kids = listOf(row(300, "Nur eine", "12:00", "x", withContainerId = false)))
        assertEquals(ScreenState.LIST, ListScreen.classify(one, profile, false))
    }

    // ---------- Parser ----------

    @Test fun parserReadsAllRowsByContainerIdsInsteadOfThreeDirectChildren() {
        val rows = ChatListParser.parse(listScreen(7), now, profile.chatListRowNameIds, profile.chatListRowContainerIds)
        assertEquals(7, rows.size)
        assertEquals("Person 0", rows[0].entry.title)
        assertEquals("10:00", rows[0].entry.timeText)
        assertEquals("Nachricht 0", rows[0].entry.preview)
        assertEquals((0..6).map { "Person $it" }, rows.map { it.entry.title })
    }

    @Test fun parserFindsRowsByTitleIdsWhenContainerIdsAreMissing() {
        val rows = ChatListParser.parse(listScreen(5, withContainerIds = false), now, profile.chatListRowNameIds, profile.chatListRowContainerIds)
        assertEquals(5, rows.size)
        assertEquals("Nachricht 3", rows[3].entry.preview)
    }

    @Test fun parserHeuristicStillWorksWithoutIdsAndWithWrapperNode() {
        val inner = (0 until 4).map { row(300 + it * 200, "P$it", "1$it:00", "Text $it", withContainerId = false, nested = false).let { r -> r.copy(children = r.children[0].children.map { c -> c.copy(viewId = null) }, viewId = null) } }
        val wrapper = n("android.view.ViewGroup", Bounds(0, 260, 1080, 2300), kids = inner)
        val list = n("androidx.recyclerview.widget.RecyclerView", Bounds(0, 260, 1080, 2300), scroll = true, kids = listOf(wrapper))
        val rows = ChatListParser.parse(n("android.widget.FrameLayout", screen, kids = listOf(list)), now)
        assertEquals(4, rows.size)
    }

    // ---------- Back rules ----------

    private class Fake(val profile: SelectorProfile, val screens: List<UiNode?>, val backMoves: Boolean = true, val leavesOnBack: Boolean = false) : ListDevice {
        var idx = 0
        var backs = 0
        var dumps = 0
        var left = false
        var ime = false
        val navLog = ArrayList<String>()
        override suspend fun ensureForeground() {}
        override fun snapshot(): UiNode? = if (left) null else screens[idx.coerceAtMost(screens.size - 1)]
        override fun imeVisible() = ime
        override fun isForeground() = !left
        override fun describe() = "Paket com.whatsapp"
        override fun back(): Boolean {
            backs++
            if (leavesOnBack) left = true else if (backMoves) idx++
            return true
        }
        override suspend fun pause(ms: Long) {}
        override fun dumpTree(): String? { dumps++; return "tree-auto-test.txt" }
        override fun nav(msg: String) { navLog.add(msg) }
        var tabClicks = 0
        var onTab: (Fake) -> Unit = {}
        override fun openChatsTab(): Boolean { tabClicks++; onTab(this); return true }
    }

    private fun ensurer(f: Fake) = ChatListEnsurer(f, profile)

    @Test fun onTheListNoBackIsPressedAndNothingIsTapped() = runBlocking {
        val f = Fake(profile, listOf(listScreen()))
        ensurer(f).run {}
        assertEquals(0, f.backs)
        assertEquals(0, f.dumps)
    }

    @Test fun fromChatOneBackReachesList() = runBlocking {
        val f = Fake(profile, listOf(chatScreen(), listScreen()))
        ensurer(f).run {}
        assertEquals(1, f.backs)
    }

    @Test fun fromActiveSearchAndChatTwoBacks() = runBlocking {
        val f = Fake(profile, listOf(chatScreen(), searchScreen(), listScreen()))
        ensurer(f).run {}
        assertEquals(2, f.backs)
    }

    @Test fun unknownViewNeverPressesBackWritesTreeAndAbortsClearly() {
        val f = Fake(profile, listOf(unknownScreen()))
        try {
            runBlocking { ensurer(f).run {} }
            fail("Abbruch erwartet")
        } catch (e: AgentException) {
            assertTrue(e.message!!, e.message!!.contains("Chatliste nicht erkannt"))
            assertTrue(e.message!!.contains("tree-auto-test.txt"))
            assertTrue(e.message!!.contains("Tab Debug"))
        }
        assertEquals(0, f.backs)
        assertEquals(1, f.dumps)
    }

    @Test fun leavingWhatsAppOnBackAbortsAfterTwoLeaves() {
        // Every back press leaves WhatsApp; recovery (ensureForeground) is not played out here
        val f = object {
            var left = false
        }
        val dev = object : ListDevice {
            var backs = 0
            var fg = true
            override suspend fun ensureForeground() { fg = true }
            override fun snapshot(): UiNode? = chatScreen()
            override fun imeVisible() = false
            override fun isForeground() = fg
            override fun describe() = "x"
            override fun back(): Boolean { backs++; fg = false; return true }
            override suspend fun pause(ms: Long) {}
            override fun dumpTree(): String? = null
            override fun nav(msg: String) {}
            fun backsDone() = backs
        }
        try {
            runBlocking { ChatListEnsurer(dev, profile).run {} }
            fail("Abbruch erwartet")
        } catch (e: AgentException) {
            assertTrue(e.message!!, e.message!!.contains("wiederholt verlassen"))
        }
        assertEquals(ChatListEnsurer.MAX_LEAVES, dev.backsDone())
    }

    @Test fun backLimitIsEnforced() {
        val f = Fake(profile, listOf(chatScreen()), backMoves = false)
        try {
            runBlocking { ensurer(f).run {} }
            fail("Abbruch erwartet")
        } catch (e: AgentException) {
            assertTrue(e.message!!.contains("Nach ${ChatListEnsurer.MAX_BACKS} Mal Zurueck"))
        }
        assertEquals(ChatListEnsurer.MAX_BACKS, f.backs)
        assertEquals(1, f.dumps)
    }

    @Test fun profileDefaultsAndJsonContainBothMarkerIds() {
        assertTrue(profile.chatListMarkerIds.contains(NAME))
        assertTrue(profile.chatListMarkerIds.contains(CONT))
    }

    // ---------- Chats tab ----------

    private fun tab(label: String, l: Int, selected: Boolean): UiNode =
        UiNode("android.widget.FrameLayout", null, null, label, Bounds(l, 2950, l + 270, 3150), clickable = true, selected = selected)

    private fun withTabs(base: UiNode, chatsSelected: Boolean, other: String = "Aktuelles"): UiNode {
        val bar = n("android.widget.FrameLayout", Bounds(0, 2930, 1080, 3170), kids = listOf(
            tab("Chats", 0, chatsSelected), tab(other, 270, !chatsSelected), tab("Communities", 540, false), tab("Anrufe", 810, false),
        ))
        return base.copy(bounds = Bounds(0, 0, 1080, 3200), children = base.children + bar)
    }

    @Test fun tabBarReadsSelectedTab() {
        val onUpdates = TabBar.read(withTabs(unknownScreen(), chatsSelected = false), profile)
        assertTrue(onUpdates.found)
        assertTrue(!onUpdates.chatsSelected)
        assertEquals("Aktuelles", onUpdates.selectedOther)
        val onChats = TabBar.read(withTabs(listScreen(), chatsSelected = true), profile)
        assertTrue(onChats.found && onChats.chatsSelected)
        assertTrue(!TabBar.read(listScreen(), profile).found)
    }

    @Test fun wrongTabIsFixedByTappingChatsTabNotByBack() = runBlocking {
        val wrong = withTabs(unknownScreen(), chatsSelected = false)
        val right = withTabs(listScreen(), chatsSelected = true)
        val f = Fake(profile, listOf(wrong, right))
        f.onTab = { it.idx++ }
        ensurer(f).run {}
        assertEquals(1, f.tabClicks)
        assertEquals(0, f.backs)
        assertEquals(0, f.dumps)
        assertTrue(f.navLog.any { it.startsWith("TAB: Anderer Tab erkannt") && it.contains("Aktuelles") })
        assertTrue(f.navLog.any { it.startsWith("TAB: Tab Chats gewaehlt") })
    }

    @Test fun chatsTabSelectedMeansNoTabClick() = runBlocking {
        val f = Fake(profile, listOf(withTabs(listScreen(), chatsSelected = true)))
        ensurer(f).run {}
        assertEquals(0, f.tabClicks)
        assertTrue(f.navLog.none { it.startsWith("TAB:") })
    }

    @Test fun tabClicksAreLimitedThenClearAbort() {
        val f = Fake(profile, listOf(withTabs(unknownScreen(), chatsSelected = false)))
        try {
            runBlocking { ensurer(f).run {} }
            fail("Abbruch erwartet")
        } catch (e: AgentException) {
            assertTrue(e.message!!.contains("Chatliste nicht erkannt"))
        }
        assertEquals(ChatListEnsurer.MAX_TAB_CLICKS, f.tabClicks)
        assertEquals(0, f.backs)
    }

    @Test fun listMustBeSeenTwiceBeforeItCounts() = runBlocking {
        // First read is the list, the second is a transition state (unknown), then a stable list: two lists in a row are required
        var reads = 0
        val dev = object : ListDevice {
            override suspend fun ensureForeground() {}
            override fun snapshot(): UiNode? { reads++; return if (reads == 2) unknownScreen() else listScreen() }
            override fun imeVisible() = false
            override fun isForeground() = true
            override fun describe() = "x"
            override fun back() = true
            override suspend fun pause(ms: Long) {}
            override fun dumpTree(): String? = null
            override fun nav(msg: String) {}
        }
        ChatListEnsurer(dev, profile).run {}
        assertEquals(4, reads)
    }

    // ---------- Names ----------

    @Test fun shortNameGetsDiagnosisAndPrefersDescriptionName() {
        val b = Bounds(0, 300, 1080, 490)
        val title = txt(Bounds(200, 320, 800, 380), "fa", NAME)
        val time = txt(Bounds(880, 320, 1050, 380), "19:00")
        val prev = txt(Bounds(200, 400, 1000, 460), "Hallo zusammen")
        val rowNode = UiNode("android.view.ViewGroup", CONT, null, "Familie, Hallo zusammen, 19:00", b, clickable = true, children = listOf(title, time, prev))
        val rows = ChatListParser.parse(n("android.widget.FrameLayout", screen, kids = listOf(rowNode, rowNode.copy(bounds = Bounds(0, 500, 1080, 690)))), now, profile.chatListRowNameIds, profile.chatListRowContainerIds)
        assertEquals("Familie", rows[0].entry.title)
        assertTrue(rows[0].diag, rows[0].diag.contains("Rohtext=\"fa\""))
    }

    @Test fun realShortNameStaysAndIsLogged() {
        val b = Bounds(0, 300, 1080, 490)
        val rowNode = UiNode("android.view.ViewGroup", CONT, null, "Ali, ok, 19:00", b, clickable = true,
            children = listOf(txt(Bounds(200, 320, 800, 380), "Ali", NAME), txt(Bounds(880, 320, 1050, 380), "19:00"), txt(Bounds(200, 400, 1000, 460), "ok sehr gut")))
        val rows = ChatListParser.parse(n("android.widget.FrameLayout", screen, kids = listOf(rowNode)), now, profile.chatListRowNameIds, profile.chatListRowContainerIds)
        assertEquals("Ali", rows[0].entry.title)
        assertTrue(rows[0].diag.contains("Rohtext=\"Ali\""))
    }

    @Test fun longNamesHaveNoDiagnosis() {
        val rows = ChatListParser.parse(listScreen(2), now, profile.chatListRowNameIds, profile.chatListRowContainerIds)
        assertEquals("", rows[0].diag)
    }

    @Test fun topLeftTitleNodeWinsAmongSeveral() {
        val b = Bounds(0, 300, 1080, 490)
        val rowNode = UiNode("android.view.ViewGroup", CONT, null, null, b, clickable = true, children = listOf(
            txt(Bounds(900, 330, 1050, 380), "Zweit", NAME), txt(Bounds(200, 320, 800, 380), "Haupttitel", NAME), txt(Bounds(880, 400, 1050, 450), "19:00"), txt(Bounds(200, 400, 800, 460), "Text hier"),
        ))
        val rows = ChatListParser.parse(n("android.widget.FrameLayout", screen, kids = listOf(rowNode)), now, profile.chatListRowNameIds, profile.chatListRowContainerIds)
        assertEquals("Haupttitel", rows[0].entry.title)
    }
}
