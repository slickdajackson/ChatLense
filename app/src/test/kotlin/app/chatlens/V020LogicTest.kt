package app.chatlens

import app.chatlens.assist.Assist
import app.chatlens.assist.NoopTranscriber
import app.chatlens.assist.SendPolicy
import app.chatlens.auto.AutoQueue
import app.chatlens.auto.AutoQueueRunner
import app.chatlens.auto.FatalAutoException
import app.chatlens.auto.ItemStatus
import app.chatlens.auto.LlmGate
import app.chatlens.auto.QueueKind
import app.chatlens.core.Bounds
import app.chatlens.core.ChatMessage
import app.chatlens.core.Direction
import app.chatlens.core.Kind
import app.chatlens.core.UiNode
import app.chatlens.llm.ContextBuilder
import app.chatlens.match.ChatListEntry
import app.chatlens.match.ChatListSelector
import app.chatlens.match.ChatTimeRank
import app.chatlens.match.NameMatcher
import app.chatlens.data.AppSettings
import app.chatlens.match.PinnedMode
import app.chatlens.memory.AesGcmCrypto
import app.chatlens.memory.ChatMemory
import app.chatlens.memory.CryptoException
import app.chatlens.memory.MemoryStore
import app.chatlens.memory.MemoryUpdater
import app.chatlens.parse.ChatListParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import javax.crypto.KeyGenerator

/** Tests for the new logic from 0.2.0 (without Android). */
class V020LogicTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- Name matching ----------

    @Test
    fun exactMatchIsIgnoringCaseAndEdgeSpaces() {
        assertTrue(NameMatcher.isExact("  Anna Meier ", "anna meier"))
        assertFalse(NameMatcher.isExact("Anna Meier", "Anna Meyer"))
    }

    @Test
    fun normalizeStripsAccentsEmojiPunctuationAndCase() {
        assertEquals("jose muller strasse", NameMatcher.normalize("  José  MÜLLER-Straße "))
        assertEquals("anna", NameMatcher.normalize("Anna \uD83D\uDE00"))
    }

    @Test
    fun similarityIs100OnlyForEqualNormalizedText() {
        assertEquals(100, NameMatcher.similarity("Anna Meier", "ANNA  meier"))
        assertEquals(100, NameMatcher.similarity("Jose", "José"))
        assertTrue(NameMatcher.similarity("Anna Meier", "Anna Meyer") in 80..99)
        assertTrue(NameMatcher.similarity("Anna Meier", "Meier Anna") >= 90)
        assertTrue(NameMatcher.similarity("Anna", "Anna Meier") in 60..99)
        assertTrue(NameMatcher.similarity("Anna Meier", "Zoltan Quax Ypsilon") < 40)
        assertEquals(0, NameMatcher.similarity("", "x"))
    }

    @Test
    fun bestPicksHighestAndFirstOnTie() {
        val r = NameMatcher.best("Mutter", listOf("Hans", "Mutti", "Mutter", "Mutter"))!!
        assertEquals("Mutter", r.text)
        assertEquals(2, r.index)
        assertEquals(100, r.percent)
        // a substring hit beats a similar, shorter name
        assertEquals("Mutti Handy", NameMatcher.best("Mutti", listOf("Hans", "Mutter", "Mutti Handy"))!!.text)
        assertNull(NameMatcher.best("x", emptyList()))
    }

    // ---------- Chat list ----------

    private val now = LocalDateTime.of(2026, 10, 3, 17, 0) // Saturday

    @Test
    fun timeRankOrdersTodayYesterdayWeekdayDate() {
        val today = ChatTimeRank.rank("14:32", now)
        val earlier = ChatTimeRank.rank("08:05", now)
        val yest = ChatTimeRank.rank("Gestern", now)
        val fri = ChatTimeRank.rank("Freitag", now) // yesterday is Friday; a weekday means the week before that
        val wed = ChatTimeRank.rank("Mittwoch", now)
        val date = ChatTimeRank.rank("01.09.2026", now)
        assertTrue(today > earlier)
        assertTrue(earlier > yest)
        assertTrue(yest > wed)
        assertTrue(wed > date)
        assertTrue(fri < yest)
        assertEquals(ChatTimeRank.UNKNOWN, ChatTimeRank.rank("Quatsch", now))
    }

    private fun e(title: String, time: String, order: Int, pinned: Boolean = false, group: Boolean = false, archive: Boolean = false) =
        ChatListEntry(title, "", time, pinned, archive, group, order)

    @Test
    fun selectorTakesNewestExcludesArchiveGroupsAndDuplicates() {
        val list = listOf(
            e("Archiviert", "", 0, archive = true),
            e("Alt", "01.01.2026", 1),
            e("Neu", "16:50", 2),
            e("Familie", "16:55", 3, group = true),
            e("Neu", "10:00", 4),
            e("Gestern-Chat", "Gestern", 5),
        )
        val noGroups = ChatListSelector.select(list, 10, includeGroups = false, PinnedMode.COUNT_NORMALLY, now).map { it.title }
        assertEquals(listOf("Neu", "Gestern-Chat", "Alt"), noGroups)
        val withGroups = ChatListSelector.select(list, 2, includeGroups = true, PinnedMode.COUNT_NORMALLY, now).map { it.title }
        assertEquals(listOf("Familie", "Neu"), withGroups)
    }

    @Test
    fun pinnedChatsAreOrderedByTheirRealRecencyAndCanBeExcluded() {
        val list = listOf(
            e("Angeheftet alt", "01.03.2026", 0, pinned = true),
            e("Frisch", "16:59", 1),
            e("Mittel", "Gestern", 2),
        )
        val normal = ChatListSelector.select(list, 2, true, PinnedMode.COUNT_NORMALLY, now).map { it.title }
        assertEquals(listOf("Frisch", "Mittel"), normal)
        val excl = ChatListSelector.select(list, 5, true, PinnedMode.EXCLUDE, now).map { it.title }
        assertEquals(listOf("Frisch", "Mittel"), excl)
        assertEquals(3, ChatListSelector.select(list, 5, true, PinnedMode.COUNT_NORMALLY, now).size)
    }

    private fun tv(text: String, l: Int, t: Int, r: Int, b: Int, desc: String? = null) =
        UiNode("android.widget.TextView", null, text, desc, Bounds(l, t, r, b))

    private fun row(top: Int, title: String, time: String, preview: String, pinned: Boolean = false): UiNode {
        val kids = mutableListOf(tv(title, 200, top + 10, 800, top + 60), tv(time, 900, top + 10, 1040, top + 60), tv(preview, 200, top + 70, 800, top + 120))
        if (pinned) kids.add(UiNode("android.widget.ImageView", null, null, "Angeheftet", Bounds(1000, top + 70, 1040, top + 110)))
        return UiNode("android.view.ViewGroup", null, null, null, Bounds(0, top, 1080, top + 140), clickable = true, children = kids)
    }

    @Test
    fun chatListParserReadsTitleTimePreviewPinGroupAndArchive() {
        val rows = listOf(
            row(300, "Archiviert", "", "3"),
            row(440, "Anna Meier", "16:40", "Bis gleich", pinned = true),
            row(580, "Familie", "Gestern", "Papa: Bin unterwegs"),
            row(720, "Du-Chat", "Montag", "Du: Danke"),
        )
        val root = UiNode(
            "android.widget.FrameLayout", null, null, null, Bounds(0, 0, 1080, 2400),
            children = listOf(UiNode("androidx.recyclerview.widget.RecyclerView", null, null, null, Bounds(0, 200, 1080, 2300), scrollable = true, children = rows)),
        )
        val parsed = ChatListParser.parse(root, now).map { it.entry }
        assertEquals(listOf("Archiviert", "Anna Meier", "Familie", "Du-Chat"), parsed.map { it.title })
        assertTrue(parsed[0].archiveRow)
        assertTrue(parsed[1].pinned)
        assertEquals("16:40", parsed[1].timeText)
        assertEquals("Bis gleich", parsed[1].preview)
        assertTrue(parsed[2].likelyGroup)
        assertFalse(parsed[3].likelyGroup)
    }

    // ---------- Auto queue ----------

    private fun titles(vararg t: String) = AutoQueue.of(QueueKind.SETUP, t.toList(), 100, 1L, true)

    @Test
    fun queueRunsSeriallySkipsFailuresAndReports() = runBlocking {
        val q = titles("A", "B", "C")
        val order = ArrayList<String>()
        var running = 0
        var maxRunning = 0
        AutoQueueRunner.run(q, { 5L }, {}) { item ->
            running++; maxRunning = maxOf(maxRunning, running)
            order.add(item.title)
            delay(5)
            running--
            if (item.title == "B") throw IllegalStateException("Chat nicht gefunden")
            "ok ${item.title}"
        }
        assertEquals(listOf("A", "B", "C"), order)
        assertEquals(1, maxRunning)
        assertEquals(listOf(ItemStatus.FERTIG, ItemStatus.FEHLER, ItemStatus.FERTIG), q.items.map { it.status })
        assertEquals("Chat nicht gefunden", q.items[1].error)
        assertTrue(q.finished)
        assertEquals(3, q.done)
    }

    @Test
    fun cancelledQueueKeepsRunningItemPendingAndResumesAfterReload() = runBlocking {
        val q = titles("A", "B", "C")
        val job = launch {
            AutoQueueRunner.run(q, { 5L }, {}) { item ->
                if (item.title == "B") delay(10_000)
                "ok"
            }
        }
        delay(200)
        job.cancel()
        job.join()
        assertEquals(listOf(ItemStatus.FERTIG, ItemStatus.WARTET, ItemStatus.WARTET), q.items.map { it.status })
        // save, load, resume
        val back = AutoQueue.fromJson(q.toJson())
        val done = ArrayList<String>()
        AutoQueueRunner.run(back, { 9L }, {}) { done.add(it.title); "ok" }
        assertEquals(listOf("B", "C"), done)
        assertEquals(listOf(ItemStatus.FERTIG, ItemStatus.FERTIG, ItemStatus.FERTIG), back.items.map { it.status })
    }

    @Test
    fun loadingTurnsRunningItemIntoPendingAndRetryFailedWorks() {
        val q = titles("A", "B")
        q.items[0].status = ItemStatus.LAEUFT
        q.items[1].status = ItemStatus.FEHLER
        q.items[1].error = "x"
        val back = AutoQueue.fromJson(q.toJson())
        assertEquals(ItemStatus.WARTET, back.items[0].status)
        assertEquals(1, back.retryFailed())
        assertEquals(ItemStatus.WARTET, back.items[1].status)
        assertEquals("", back.items[1].error)
    }

    @Test
    fun fatalErrorStopsQueueButKeepsItemPending() = runBlocking {
        val q = titles("A", "B")
        var thrown: Throwable? = null
        try {
            AutoQueueRunner.run(q, { 1L }, {}) { throw FatalAutoException("Bedienungshilfe aus") }
        } catch (e: FatalAutoException) {
            thrown = e
        }
        assertNotNull(thrown)
        assertEquals(ItemStatus.WARTET, q.items[0].status)
        assertEquals(ItemStatus.WARTET, q.items[1].status)
    }

    @Test
    fun llmGateAllowsOnlyOneCallAtATime() = runBlocking {
        var active = 0
        var maxActive = 0
        (1..6).map {
            async(kotlinx.coroutines.Dispatchers.Default) {
                LlmGate.exclusive {
                    active++; maxActive = maxOf(maxActive, active)
                    delay(10)
                    active--
                }
            }
        }.awaitAll()
        assertEquals(1, maxActive)
    }

    // ---------- Memory ----------

    private fun crypto(): AesGcmCrypto {
        val k = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return AesGcmCrypto { k }
    }

    @Test
    fun encryptionRoundTripAndTamperDetection() {
        val c = crypto()
        val blob = c.encrypt("Hallo Welt".toByteArray())
        assertEquals("Hallo Welt", String(c.decrypt(blob)))
        assertNotEquals(blob.toList(), c.encrypt("Hallo Welt".toByteArray()).toList()) // fresh IV
        val bad = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        var failed = false
        try { c.decrypt(bad) } catch (e: CryptoException) { failed = true }
        assertTrue(failed)
        val other = crypto()
        failed = false
        try { other.decrypt(blob) } catch (e: CryptoException) { failed = true }
        assertTrue("falscher Schluessel", failed)
    }

    @Test
    fun storeSavesEncryptedLoadsListsExportsAndDeletes() {
        val dir = tmp.newFolder("mem")
        val store = MemoryStore(dir, crypto())
        val a = ChatMemory("anna meier", "Anna Meier", profile = "Kollegin, Marketing", userNote = "Geburtstag 5. Mai", updatedAt = 10, anchor = listOf("x|1|i"))
        val b = ChatMemory("hans", "Hans", profile = "Nachbar", updatedAt = 20)
        store.save(a); store.save(b)
        // plaintext must not appear in the files
        val raw = dir.listFiles()!!.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertFalse(raw.contains("Anna"))
        assertFalse(raw.contains("Kollegin"))
        assertEquals("Kollegin, Marketing", store.load("anna meier")!!.profile)
        assertEquals("Geburtstag 5. Mai", store.loadByTitle("Anna  MEIER")!!.userNote)
        assertEquals(listOf("Hans", "Anna Meier"), store.list().map { it.displayName })
        assertTrue(store.exportJson("hans").contains("Nachbar"))
        assertFalse(store.exportJson("hans").contains("Kollegin"))
        assertTrue(store.exportJson().contains("Kollegin"))
        assertTrue(store.delete("hans"))
        assertNull(store.load("hans"))
        assertEquals(1, store.deleteAll())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun storeWithWrongKeyReturnsNothingInsteadOfGarbage() {
        val dir = tmp.newFolder("mem2")
        MemoryStore(dir, crypto()).save(ChatMemory("k", "K", profile = "geheim"))
        assertNull(MemoryStore(dir, crypto()).load("k"))
    }

    private fun msg(text: String, time: String, out: Boolean = false, incomplete: Boolean = false) =
        ChatMessage(Kind.TEXT, if (out) Direction.OUT else Direction.IN, null, text, time).also { it.incomplete = incomplete }

    @Test
    fun anchorFindsNewMessagesOnly() {
        val all = (1..10).map { msg("Nachricht $it", "10:%02d".format(it), it % 2 == 0) }
        val anchor = MemoryUpdater.anchorOf(all.take(6))
        assertEquals(3, anchor.size)
        val s = MemoryUpdater.messagesSince(all, anchor)
        assertTrue(s.found)
        assertEquals(listOf("Nachricht 7", "Nachricht 8", "Nachricht 9", "Nachricht 10"), s.newer.map { it.text })
        assertTrue(MemoryUpdater.anchorReached(all.drop(3), anchor))
        assertFalse(MemoryUpdater.anchorReached(all.drop(6), anchor))
        val nf = MemoryUpdater.messagesSince(all.drop(7), anchor)
        assertFalse(nf.found)
        assertEquals(3, nf.newer.size)
    }

    @Test
    fun anchorIgnoresIncompleteMessages() {
        val all = listOf(msg("a", "1"), msg("b", "2"), msg("c halb", "3", incomplete = true))
        assertEquals(2, MemoryUpdater.anchorOf(all).size)
    }

    private val modelOut = """
STECKBRIEF: Kollegin aus dem Marketing, plant Umzug nach Köln.
BEZIEHUNG: Freundlich, kollegial.
OFFEN: Wartet auf Rückmeldung zum Termin am Freitag.
STIMMUNG: Gut gelaunt.
"""

    @Test
    fun updateParsesFieldsKeepsOldOnesAndSetsAnchor() {
        val first = MemoryUpdater.apply(null, modelOut, "Anna Meier", 100L, listOf("a|1|i"), "10:05", 12)
        assertEquals("anna meier", first.chatKey)
        assertTrue(first.profile.startsWith("Kollegin"))
        assertEquals("Gut gelaunt.", first.mood)
        assertEquals(12, first.messagesSeen)
        val note = first.copy(userNote = "mag Kaffee")
        val second = MemoryUpdater.apply(note, "OFFEN: Termin bestätigt.\nSTIMMUNG: Erleichtert.", "Anna Meier", 200L, listOf("b|2|o"), "11:00", 3)
        assertEquals("Termin bestätigt.", second.openTopics)
        assertEquals(first.profile, second.profile) // not mentioned: it stays
        assertEquals("mag Kaffee", second.userNote) // user field untouched
        assertEquals(15, second.messagesSeen)
        assertEquals(listOf("b|2|o"), second.anchor)
        assertEquals(200L, second.updatedAt)
    }

    @Test
    fun updateCapsLengthAndHandlesFreeTextAndEmptyOutput() {
        val long = "STECKBRIEF: " + "x".repeat(3000) + "\nBEZIEHUNG: " + "y".repeat(3000) + "\nOFFEN: " + "z".repeat(3000)
        val m = MemoryUpdater.apply(null, long, "T", 1L, emptyList(), "", 0)
        assertTrue(m.generatedLength <= ChatMemory.MAX_GENERATED)
        val free = MemoryUpdater.apply(null, "Nur Freitext ohne Kopfzeilen.", "T", 1L, emptyList(), "", 0)
        assertEquals("Nur Freitext ohne Kopfzeilen.", free.profile)
        val keep = MemoryUpdater.apply(m, "   ", "T", 2L, emptyList(), "", 0)
        assertEquals(m, keep)
    }

    @Test
    fun updatePromptContainsMemoryOnlyWhenPresentAndIsMarkedIncremental() {
        val none = MemoryUpdater.user(null, "[10:00] A: Hi", false, "A")
        assertTrue(none.contains("noch kein Gedächtnis"))
        val mem = ChatMemory("a", "A", profile = "Freund", userNote = "Zusatz")
        val inc = MemoryUpdater.user(mem, "[10:00] A: Hi", true, "A")
        assertTrue(inc.contains("Steckbrief: Freund"))
        assertTrue(inc.contains("Zusatzinfo vom Nutzer: Zusatz"))
        assertTrue(inc.contains("Neue Nachrichten seit dem letzten Stand"))
    }

    // ---------- Suggestions and the safety rule ----------

    @Test
    fun suggestionsAreParsedFromNumberedOutput() {
        val out = "1. Klar, passt mir gut.\n2) Können wir auf 18 Uhr schieben?\n   Dann habe ich Zeit.\nVorschlag 3: \"Ich melde mich später.\""
        val s = Assist.parseSuggestions(out)
        assertEquals(3, s.size)
        assertEquals("Klar, passt mir gut.", s[0])
        assertEquals("Können wir auf 18 Uhr schieben? Dann habe ich Zeit.", s[1])
        assertEquals("Ich melde mich später.", s[2])
        assertEquals(listOf("Einfach ein Satz."), Assist.parseSuggestions("Einfach ein Satz."))
        assertTrue(Assist.parseSuggestions("  ").isEmpty())
    }

    @Test
    fun sendPolicyNeverSendsByDefaultOrWithoutExactConfirmation() {
        assertFalse(SendPolicy.maySend(false, "Hallo", "Hallo"))
        assertFalse(SendPolicy.maySend(true, null, "Hallo"))
        assertFalse(SendPolicy.maySend(true, "Hallo", "Hallo!"))
        assertFalse(SendPolicy.maySend(true, "", ""))
        assertTrue(SendPolicy.maySend(true, "Hallo", "Hallo"))
        assertFalse(app.chatlens.data.AppSettings().experimentalSend)
    }

    private val srcDir = File("src/main/kotlin/app/chatlens")

    @Test
    fun senderIsReachableOnlyFromTheUiAndNeverFromAgentAutoOrServices() {
        val callers = srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("ExperimentalSender") }.map { it.name }.toSet()
        assertEquals(setOf("ReplyActions.kt", "MainActivity.kt"), callers)
        for (dir in listOf("agent", "auto", "service", "llm", "memory", "match")) {
            val files = File(srcDir, dir).listFiles().orEmpty().filter { it.extension == "kt" }
            assertTrue("$dir darf den Sender nicht kennen", files.none { it.readText().contains("ExperimentalSender") })
        }
        // The only call happens with the confirmed text and the settings switch
        val main = File(srcDir, "MainActivity.kt").readText()
        assertTrue(main.contains("send(text, confirmedText = text, experimentalEnabled = settings.experimentalSend)"))
        // The send-button click exists only in ReplyActions, behind SendPolicy
        val clickFiles = srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("sendButtonDescriptions") }.map { it.name }.toSet()
        assertEquals(setOf("ReplyActions.kt", "SelectorProfile.kt"), clickFiles)
        val actions = File(srcDir, "assist/ReplyActions.kt").readText()
        val sender = actions.substringAfter("class ExperimentalSender")
        assertTrue(sender.indexOf("SendPolicy.maySend") in 0 until sender.indexOf("ACTION_CLICK"))
    }

    @Test
    fun experimentalSendIsOffByDefaultAndOverlayHasNoSendPath() {
        assertFalse(AppSettings().experimentalSend)
        assertFalse(AppSettings().overlayEnabled)
        val overlay = File(srcDir, "service/OverlayService.kt").readText() + File(srcDir, "ui/OverlayUi.kt").readText()
        assertFalse(overlay.contains("ExperimentalSender"))
        assertFalse(overlay.contains("ACTION_CLICK"))
        assertTrue(overlay.contains("FLAG_NOT_FOCUSABLE"))
    }

    @Test
    fun shorterQueryDropsTrailingWords() {
        assertEquals("Anna", NameMatcher.shorterQuery("Anna Mueller Berlin"))
        assertEquals("Fami", NameMatcher.shorterQuery("Familie"))
        assertNull(NameMatcher.shorterQuery("Mo"))
    }

    @Test
    fun replyActionsOnlyOffersSetTextAndNoClick() {
        val t = File(srcDir, "assist/ReplyActions.kt").readText()
        assertTrue(t.contains("ACTION_SET_TEXT"))
        // ReplyInserter must not contain a click
        val inserter = t.substringAfter("class ReplyInserter").substringBefore("class ExperimentalSender")
        assertFalse(inserter.contains("ACTION_CLICK"))
        assertFalse(inserter.contains("performClick"))
    }

    // ---------- Voice-message preparation ----------

    @Test
    fun voiceLineMentionsMissingOrPresentTranscript() {
        val v = ChatMessage(Kind.VOICE, Direction.IN, null, "0:23", "10:00")
        assertTrue(ContextBuilder.line(v, "A", null).contains("nicht transkribiert"))
        v.transcript = "Ich komme später."
        assertTrue(ContextBuilder.line(v, "A", null).contains("Transkript: Ich komme später."))
        assertFalse(NoopTranscriber.available)
    }
}
