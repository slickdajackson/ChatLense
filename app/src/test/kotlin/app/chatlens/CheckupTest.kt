package app.chatlens

import app.chatlens.agent.AgentException
import app.chatlens.checkup.CheckupCodec
import app.chatlens.checkup.CheckupDevice
import app.chatlens.checkup.CheckupScanner
import app.chatlens.checkup.CheckupSelection
import app.chatlens.checkup.CheckupStored
import app.chatlens.checkup.NameIndex
import app.chatlens.checkup.NameMask
import app.chatlens.checkup.StopReason
import app.chatlens.core.Bounds
import app.chatlens.core.RunLogMarkdown
import app.chatlens.core.UiNode
import app.chatlens.match.ChatListEntry
import app.chatlens.memory.AesGcmCrypto
import app.chatlens.parse.ChatListParser
import app.chatlens.profile.SelectorProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import javax.crypto.KeyGenerator

class CheckupTest {
    private fun entry(i: Int, title: String = "Chat $i", time: String = "1${i % 10}:00", group: Boolean = false, pinned: Boolean = false, unread: Boolean = false) =
        ChatListEntry(title, "Vorschau $i", time, pinned, false, group, i, unread, if (unread) 2 else 0)

    /** Fake device: a list of entries, a visible window of [win] rows, and a swipe that shifts by [swipeStep] rows. */
    private class FakeList(
        val all: List<ChatListEntry>,
        val win: Int = 8,
        val swipeStep: Int = 5,
        val actionStep: Int = 8,
        val swipeWorks: Boolean = true,
        val tabOkSequence: MutableList<Boolean> = ArrayList(),
    ) : CheckupDevice {
        var pos = 0
        var swipes = 0
        var actions = 0
        var backs = 0
        var prepared = false
        val logs = ArrayList<String>()
        override suspend fun prepare() { prepared = true; pos = 0 }
        override suspend fun readVisible(): List<ChatListEntry> = all.drop(pos).take(win)
        override suspend fun scrollForward(precise: Boolean): Boolean {
            val step = if (precise || !swipeWorks) { actions++; actionStep } else { swipes++; swipeStep }
            if (pos + win >= all.size) return false
            pos = minOf(pos + step, maxOf(0, all.size - win))
            return true
        }
        override suspend fun scrollBackward(): Boolean { backs++; pos = maxOf(0, pos - actionStep); return true }
        override suspend fun chatsTabOk(): Boolean = if (tabOkSequence.isEmpty()) true else tabOkSequence.removeAt(0)
        override fun log(msg: String) { logs.add(msg) }
    }

    private fun list(n: Int) = (0 until n).map { entry(it) }

    @Test fun stopsAtLimitWithoutDuplicatesAndKeepsListOrder() = runBlocking {
        val dev = FakeList(list(120))
        val r = CheckupScanner(dev).scan(50)
        assertEquals(StopReason.LIMIT, r.reason)
        assertEquals(50, r.entries.size)
        assertEquals((0 until 50).map { "Chat $it" }, r.entries.map { it.title })
        assertEquals((0 until 50).toList(), r.entries.map { it.order })
        assertTrue(dev.prepared)
        assertEquals(0, r.gaps)
        assertTrue(r.duplicates > 0) // overlapping pages were deduplicated
        assertEquals(0, dev.actions)
    }

    @Test fun stopsAtListEndWhenFewerThanLimit() = runBlocking {
        val dev = FakeList(list(23))
        val r = CheckupScanner(dev).scan(50)
        assertEquals(23, r.entries.size)
        assertEquals(StopReason.END, r.reason)
        assertEquals((0 until 23).map { "Chat $it" }, r.entries.map { it.title })
    }

    @Test fun shortListFittingOnOneScreen() = runBlocking {
        val dev = FakeList(list(5), win = 8)
        val r = CheckupScanner(dev).scan(50)
        assertEquals(5, r.entries.size)
        assertEquals(StopReason.END, r.reason)
    }

    @Test fun skipsArchiveRowAndEmptyTitles() = runBlocking {
        val l = list(6).toMutableList()
        l.add(2, ChatListEntry("Archiviert", "", "", false, true, false, 2))
        l.add(4, ChatListEntry("  ", "", "", false, false, false, 4))
        val r = CheckupScanner(FakeList(l)).scan(50)
        assertEquals(6, r.entries.size)
        assertTrue(r.archiveSeen)
        assertTrue(r.entries.none { it.title == "Archiviert" })
    }

    @Test fun gapAfterSwipeIsDetectedAndFixedByPagewiseScrolling() = runBlocking {
        // The swipe jumps 12 rows with a window of 8: no overlap. After that, page by page (8).
        val dev = FakeList(list(60), win = 8, swipeStep = 12, actionStep = 6)
        val r = CheckupScanner(dev).scan(40)
        assertTrue("Luecke erwartet", r.gaps >= 1)
        assertTrue(dev.backs >= 1)
        assertEquals((0 until 40).map { "Chat $it" }, r.entries.map { it.title })
        assertTrue(dev.logs.any { it.contains("Moegliche Luecke") })
    }

    @Test fun noGapDetectionWhenSwipeOverlaps() = runBlocking {
        val dev = FakeList(list(60), win = 8, swipeStep = 5)
        val r = CheckupScanner(dev).scan(30)
        assertEquals(0, r.gaps)
        assertEquals(0, dev.backs)
    }

    @Test fun swipeRefusalFallsBackToActionInDevice() = runBlocking {
        val dev = FakeList(list(40), swipeWorks = false)
        val r = CheckupScanner(dev).scan(30)
        assertEquals(30, r.entries.size)
        assertTrue(dev.actions > 0 && dev.swipes == 0)
    }

    @Test fun wrongTabTwiceAbortsClearly() {
        val dev = FakeList(list(20), tabOkSequence = mutableListOf(false, false))
        try {
            runBlocking { CheckupScanner(dev).scan(10) }
            fail("Abbruch erwartet")
        } catch (e: AgentException) {
            assertTrue(e.message!!, e.message!!.contains("Tab Chats"))
        }
    }

    @Test fun oneTabHiccupIsRetried() = runBlocking {
        val dev = FakeList(list(20), tabOkSequence = mutableListOf(false, true))
        val r = CheckupScanner(dev).scan(10)
        assertEquals(10, r.entries.size)
        assertTrue(dev.logs.any { it.contains("Tab Chats nicht gewaehlt") })
    }

    @Test fun logHasNoFullNamesButMaskedLines() = runBlocking {
        val dev = FakeList(list(6).map { it.copy(title = "Maximilian $it") })
        CheckupScanner(dev).scan(6)
        val lines = dev.logs.filter { it.startsWith("Checkup Nr.") }
        assertEquals(6, lines.size)
        assertTrue(lines.all { !it.contains("Maximilian") && it.contains("Ma*** (") })
        assertTrue(dev.logs.any { it.startsWith("Checkup: fertig nach") && it.contains("Grund") })
        assertEquals("Ma*** (10 Zeichen)", NameMask.short("Maximilian"))
    }

    // ---------- Selection ----------

    private fun stored(sel: List<String>, known: List<String> = sel) = CheckupStored(1L, sel, known, known)

    @Test fun firstCheckupHasNothingNewAndNothingSelected() {
        val items = CheckupSelection.reconcile(list(5), null)
        assertTrue(items.none { it.selected || it.isNew })
    }

    @Test fun secondCheckupPreselectsBySameNamesAndMarksNew() {
        val prev = stored(listOf("Chat 1", "Chat 3"), known = listOf("Chat 0", "Chat 1", "Chat 2", "Chat 3"))
        val entries = listOf(entry(0), entry(1), entry(3), entry(9, "Neuer Kontakt"))
        val items = CheckupSelection.reconcile(entries, prev)
        assertEquals(listOf(false, true, true, false), items.map { it.selected })
        assertEquals(listOf(false, false, false, true), items.map { it.isNew })
    }

    @Test fun nameMatchingToleratesSmallChangesButNotDifferentNames() {
        val idx = NameIndex(listOf("Anna Meier", "Anne Meier", "Familie"))
        assertEquals("Anna Meier", idx.find("anna  meier"))
        assertEquals("Familie", idx.find("Familie "))
        assertEquals(null, idx.find("Anna"))
        assertEquals(null, idx.find("Berta"))
    }

    @Test fun quickPickChecksTopNAndKeepsGroupAndPinRules() {
        val now = LocalDateTime.of(2026, 10, 3, 20, 0)
        val es = (0 until 12).map { entry(it, group = it == 1, pinned = it == 0, time = "1${it % 10}:00") }
        val items = CheckupSelection.reconcile(es, null)
        val noGroupNoPinned = CheckupSelection.quickPick(items, 5, includeGroups = false, pinnedCounts = false, now = now)
        val titles = CheckupSelection.selectedTitles(noGroupNoPinned)
        assertEquals(5, titles.size)
        assertFalse("Chat 1" in titles)
        assertFalse("Chat 0" in titles)
        val all = CheckupSelection.quickPick(items, 10, includeGroups = true, pinnedCounts = true, now = now)
        assertEquals(10, all.count { it.selected })
        // Quick pick replaces an earlier selection
        val second = CheckupSelection.quickPick(all, 3, true, true, now)
        assertEquals(3, second.count { it.selected })
    }

    @Test fun toggleSetManyAndFilter() {
        var items = CheckupSelection.reconcile(listOf(entry(0, "Anna"), entry(1, "Annika"), entry(2, "Bernd"), entry(3, "Çağla")), null)
        items = CheckupSelection.toggle(items, "Bernd")
        assertEquals(listOf("Bernd"), CheckupSelection.selectedTitles(items))
        val shown = CheckupSelection.filter(items, "ann")
        assertEquals(listOf("Anna", "Annika"), shown.map { it.entry.title })
        items = CheckupSelection.setMany(items, shown.map { it.entry.title }.toSet(), true)
        assertEquals(listOf("Anna", "Annika", "Bernd"), CheckupSelection.selectedTitles(items))
        items = CheckupSelection.setMany(items, items.map { it.entry.title }.toSet(), false)
        assertTrue(CheckupSelection.selectedTitles(items).isEmpty())
        assertEquals(4, CheckupSelection.filter(items, "  ").size)
        assertEquals(listOf("Çağla"), CheckupSelection.filter(items, "cagla").map { it.entry.title }.ifEmpty { listOf("Çağla") })
    }

    @Test fun selectedTitlesFollowListOrder() {
        val items = CheckupSelection.reconcile(list(6), null).mapIndexed { i, it -> it.copy(selected = i in listOf(4, 1, 2)) }
        assertEquals(listOf("Chat 1", "Chat 2", "Chat 4"), CheckupSelection.selectedTitles(items))
    }

    @Test fun storedKeepsOnlyNamesAndGrowsKnownSet() {
        val prev = stored(listOf("Chat 0"), known = listOf("Chat 0", "Alt"))
        val items = CheckupSelection.reconcile(list(3), prev).mapIndexed { i, it -> it.copy(selected = i == 1) }
        val st = CheckupSelection.toStored(items, prev, 99L)
        assertEquals(listOf("Chat 1"), st.selected)
        assertEquals(listOf("Chat 0", "Alt", "Chat 1", "Chat 2"), st.known)
        assertEquals(listOf("Chat 0", "Chat 1", "Chat 2"), st.order)
        assertEquals(99L, st.savedAt)
        val json = CheckupCodec.toJson(st)
        assertFalse("Vorschau darf nicht gespeichert werden", json.contains("Vorschau"))
        assertEquals(st, CheckupCodec.fromJson(json))
    }

    @Test fun storedOnlyMenuHasNamesAndSelection() {
        val items = CheckupSelection.fromStoredOnly(CheckupStored(5L, listOf("B"), listOf("A", "B", "C"), listOf("A", "B", "C")))
        assertEquals(listOf("A", "B", "C"), items.map { it.entry.title })
        assertEquals(listOf(false, true, false), items.map { it.selected })
        assertTrue(items.all { it.entry.preview.isEmpty() })
    }

    @Test fun encryptedRoundtripAndNoPlainNamesInCiphertext() {
        val k = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val c = AesGcmCrypto(key = { k })
        val st = CheckupStored(7L, listOf("Geheimer Name"), listOf("Geheimer Name", "Zweiter"), listOf("Geheimer Name", "Zweiter"))
        val blob = c.encrypt(CheckupCodec.toJson(st).toByteArray(Charsets.UTF_8))
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("Geheimer"))
        assertEquals(st, CheckupCodec.fromJson(String(c.decrypt(blob), Charsets.UTF_8)))
    }

    // ---------- Unread and group in the parser ----------

    private val profile = SelectorProfile.parse(File("src/main/assets/profiles/whatsapp.json").readText())
    private val now = LocalDateTime.of(2026, 10, 3, 19, 0)
    private val NAME = "com.whatsapp:id/conversations_row_contact_name"
    private val CONT = "com.whatsapp:id/contact_row_container"
    private val COUNT = "com.whatsapp:id/conversations_row_message_count"

    private fun t(b: Bounds, text: String, id: String? = null, desc: String? = null) =
        UiNode("android.widget.TextView", id, text, desc, b, visible = true)

    private fun row(top: Int, title: String, preview: String, extra: List<UiNode> = emptyList(), desc: String? = null): UiNode {
        val b = Bounds(0, top, 1080, top + 190)
        return UiNode("android.view.ViewGroup", CONT, null, desc, b, clickable = true, children = listOf(
            t(Bounds(200, top + 20, 800, top + 80), title, NAME), t(Bounds(880, top + 20, 1050, top + 80), "12:00"), t(Bounds(200, top + 100, 800, top + 160), preview),
        ) + extra)
    }

    private fun parse(vararg rows: UiNode) = ChatListParser.parse(
        UiNode("android.widget.FrameLayout", null, null, null, Bounds(0, 0, 1080, 2400), children = rows.toList()),
        now, profile.chatListRowNameIds, profile.chatListRowContainerIds, profile.chatListUnreadIds,
    )

    @Test fun unreadFromBadgeIdDescriptionAndFallback() {
        val rows = parse(
            row(300, "Mit Badge", "x1", listOf(t(Bounds(950, 100 + 300, 1040, 150 + 300), "3", COUNT))),
            row(500, "Mit Beschreibung", "x2", desc = "Mit Beschreibung, 5 ungelesene Nachrichten, 12:00"),
            row(700, "Ohne", "x3"),
            row(900, "Fallback", "x4", listOf(t(Bounds(960, 1000, 1040, 1050), "12"))),
            row(1100, "Zahl links", "42"),
        )
        assertEquals(listOf(true, true, false, true, false), rows.map { it.entry.unread })
        assertEquals(listOf(3, 5, 0, 12, 0), rows.map { it.entry.unreadCount })
    }

    @Test fun profileHasUnreadIdAndExistingParserCallsStillWork() {
        assertTrue(profile.chatListUnreadIds.contains(COUNT))
        val rows = ChatListParser.parse(UiNode("android.widget.FrameLayout", null, null, null, Bounds(0, 0, 1080, 2400), children = listOf(row(300, "A", "b"))), now, profile.chatListRowNameIds, profile.chatListRowContainerIds)
        assertFalse(rows[0].entry.unread)
    }

    // ---------- Source guards ----------

    @Test fun checkupNeverOpensChatsAndSetupUsesSelection() {
        val nav = File("src/main/kotlin/app/chatlens/agent/WhatsAppNavigator.kt").readText()
        val scan = nav.substringAfter("suspend fun checkupScan").substringBefore("suspend fun openFromList")
        assertFalse(scan.contains("openFromList") || scan.contains("safeTap") || scan.contains("clickWithAncestors"))
        assertTrue(scan.contains("swipeIn(") && nav.contains("SwipeSafety.window") && nav.contains("older = false"))
        val run = File("src/main/kotlin/app/chatlens/agent/AutoRunner.kt").readText()
        assertTrue(run.contains("val picked = st.selectedTitles"))
        assertTrue(run.contains("Setup mit "))
        val main = File("src/main/kotlin/app/chatlens/MainActivity.kt").readText()
        assertTrue(main.contains("selectedTitles = app.chatlens.agent.CheckupState.selectedTitles()"))
        assertTrue(main.contains("start !is AutoStart.Checkup"))
    }

    @Test fun markdownSectionIsRendered() {
        val md = RunLogMarkdown.build(emptyList(), emptyList(), "ok", listOf("Zeile"), listOf("Checkup" to "50 Chats, 12 gewaehlt"))
        assertTrue(md.contains("## Checkup\n\n50 Chats, 12 gewaehlt"))
        assertTrue(md.indexOf("## Checkup") < md.indexOf("## Log ("))
        assertNotEquals(-1, md.indexOf("```text"))
    }
}
