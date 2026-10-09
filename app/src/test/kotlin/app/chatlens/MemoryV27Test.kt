package app.chatlens

import app.chatlens.assist.Assist
import app.chatlens.core.ChatMessage
import app.chatlens.core.Direction
import app.chatlens.core.Kind
import app.chatlens.memory.ChatMemory
import app.chatlens.memory.Disc
import app.chatlens.memory.DiscLevel
import app.chatlens.memory.DiscProfile
import app.chatlens.memory.IchCat
import app.chatlens.memory.IchCodec
import app.chatlens.memory.IchLogic
import app.chatlens.memory.IchProfile
import app.chatlens.memory.MemFocus
import app.chatlens.memory.MemSection
import app.chatlens.memory.MemoryCodec
import app.chatlens.memory.MemoryUpdater
import app.chatlens.memory.SelfAnalysis
import app.chatlens.memory.SelfCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Memory, DISC, self profile, and self-analysis (0.2.7). JVM only. */
class MemoryV27Test {
    // ---------- DISC ----------

    @Test fun discConfidenceCapsByDataAmount() {
        assertEquals(DiscLevel.ZU_WENIG, Disc.levelFor(19))
        assertEquals(DiscLevel.NIEDRIG, Disc.levelFor(20))
        assertEquals(DiscLevel.NIEDRIG, Disc.levelFor(59))
        assertEquals(DiscLevel.MITTEL, Disc.levelFor(60))
        assertEquals(DiscLevel.MITTEL, Disc.levelFor(149))
        assertEquals(DiscLevel.HOCH, Disc.levelFor(150))
    }

    @Test fun discNormalizesToHundred() {
        for (t in listOf(listOf(40, 30, 20, 10), listOf(1, 1, 1, 0), listOf(33, 33, 33, 33), listOf(70, 70, 10, 5), listOf(3, 0, 0, 0))) {
            val n = Disc.normalize(t[0], t[1], t[2], t[3])!!
            assertEquals(100, n.sum())
            assertTrue(n.all { it >= 0 })
        }
        assertNull(Disc.normalize(0, 0, 0, 0))
    }

    @Test fun discParseReadsLineAndCapsConfidence() {
        val p = Disc.parse("D=40 I=30 S=20 C=10; Konfidenz: hoch; Begründung: knappe, direkte Sätze", 40, 5L)
        assertEquals(listOf(40, 30, 20, 10), listOf(p.d, p.i, p.s, p.c))
        assertEquals(DiscLevel.NIEDRIG, p.confidence)       // 40 messages: at most low
        assertTrue(p.reason.contains("direkte"))
        assertFalse(p.insufficient)
        assertEquals(DiscLevel.HOCH, Disc.parse("D=40 I=30 S=20 C=10; Konfidenz: hoch", 400, 0).confidence)
    }

    @Test fun discInsufficientNeverInventsValues() {
        val few = Disc.parse("D=40 I=30 S=20 C=10; Konfidenz: hoch", 10, 0)
        assertTrue(few.insufficient); assertEquals(0, few.d + few.i + few.s + few.c)
        assertEquals("DISC: zu wenig Daten", few.line())
        assertTrue(Disc.parse("zu wenig Daten", 500, 0).insufficient)
        assertTrue(Disc.parse("irgendwas", 500, 0).insufficient)
        assertTrue(Disc.DISCLAIMER.contains("keine Diagnose"))
    }

    @Test fun discMergeWeightsByBasis() {
        val a = DiscProfile(60, 20, 10, 10, DiscLevel.MITTEL, "", 100, 0)
        val b = DiscProfile(20, 60, 10, 10, DiscLevel.NIEDRIG, "", 100, 0)
        val m = Disc.merge(a, b)
        assertEquals(40, m.d); assertEquals(40, m.i); assertEquals(200, m.basis)
        assertEquals(100, m.d + m.i + m.s + m.c)
        val heavy = Disc.merge(a.copy(basis = 300), b)
        assertTrue(heavy.d > heavy.i)
        // too little new data: the old value stays
        val keep = Disc.merge(a, DiscProfile(basis = 5))
        assertEquals(60, keep.d)
        // too little old data: the new value applies
        assertEquals(20, Disc.merge(DiscProfile(basis = 5), b).d)
    }

    @Test fun discJsonRoundTrip() {
        val a = DiscProfile(40, 30, 20, 10, DiscLevel.MITTEL, "Grund", 90, 7L)
        assertEquals(a, Disc.fromJson(Disc.toJson(a)))
        assertNull(Disc.fromJson(null))
    }

    // ---------- Profile ----------

    private val fullOutput = """
STECKBRIEF: Kollegin aus dem Vertrieb
BEZIEHUNG: freundlich, geschäftlich
THEMEN: Projekt Alpha; Urlaub
TON: locker, kurze Sätze
OFFEN: Angebot schicken
FAKTEN: Termin 12.03. Besprechung
VORLIEBEN: mag Kaffee
STIMMUNG: gut gelaunt
VERLAUF: vorher angespannt
DISC: D=40 I=30 S=20 C=10; Konfidenz: mittel; Begründung: direkte Wortwahl
""".trim()

    @Test fun updaterParsesAllNineSectionsAndDisc() {
        val m = MemoryUpdater.apply(null, fullOutput, "Anna", 1_000L, listOf("x"), "10:00", 80, partnerMessages = 80, discEnabled = true)
        assertEquals("Kollegin aus dem Vertrieb", m.profile)
        assertEquals("Projekt Alpha; Urlaub", m.topics)
        assertEquals("locker, kurze Sätze", m.tone)
        assertEquals("Angebot schicken", m.openTopics)
        assertTrue(m.facts.contains("12.03."))
        assertEquals("mag Kaffee", m.preferences)
        assertEquals("vorher angespannt", m.moodHistory)
        assertEquals(40, m.disc!!.d)
        assertEquals(DiscLevel.MITTEL, m.disc!!.confidence)
        assertEquals(80, m.messagesSeen)
    }

    @Test fun discIgnoredWhenDisabledOrGroup() {
        val m = MemoryUpdater.apply(null, fullOutput, "Anna", 0L, emptyList(), "", 80, partnerMessages = 80, discEnabled = false)
        assertNull(m.disc)
    }

    @Test fun updaterMergeKeepsSectionsMissingInOutput() {
        val first = MemoryUpdater.apply(null, fullOutput, "Anna", 1L, emptyList(), "", 10)
        val second = MemoryUpdater.apply(first.copy(userNote = "Meine Notiz"), "OFFEN: Rückruf\nSTIMMUNG: müde", "Anna", 2L, emptyList(), "", 5)
        assertEquals("Rückruf", second.openTopics)
        assertEquals("Kollegin aus dem Vertrieb", second.profile)   // stayed
        assertEquals("mag Kaffee", second.preferences)
        assertEquals("Meine Notiz", second.userNote)                 // user text is never overwritten by the model
        assertEquals(15, second.messagesSeen)
        assertTrue(second.moodHistory.contains("müde"))              // history was missing; the mood is appended with a date
        assertTrue(second.moodHistory.contains("vorher angespannt"))
    }

    @Test fun updaterCapsSectionsByWeightAndTotalBudget() {
        val long = "x".repeat(5000)
        val out = MemSection.entries.joinToString("\n") { it.key + ": " + long }
        val m = MemoryUpdater.apply(null, out, "A", 0L, emptyList(), "", 1)
        assertTrue(m.generatedLength <= ChatMemory.DEFAULT_MAX)
        assertEquals(6000, ChatMemory.DEFAULT_MAX)
        val small = MemoryUpdater.apply(null, out, "A", 0L, emptyList(), "", 1, maxChars = 1500)
        assertTrue(small.generatedLength <= 1500)
        val big = MemoryUpdater.apply(null, out, "A", 0L, emptyList(), "", 1, maxChars = 12000)
        assertTrue(big.generatedLength in 6001..12000)
        // the upper bound is clamped to 1500..12000
        assertTrue(MemoryUpdater.apply(null, out, "A", 0L, emptyList(), "", 1, maxChars = 99999).generatedLength <= 12000)
        // larger sections (facts) get more room than mood
        assertTrue(m.facts.length > m.mood.length)
    }

    @Test fun promptBlockRespectsBudgetAndPicksRelevantSections() {
        val m = MemoryUpdater.apply(null, fullOutput.replace("Termin 12.03. Besprechung", "F".repeat(400)), "Anna", 0L, emptyList(), "", 80, partnerMessages = 80, discEnabled = true)
        val full = m.toPromptBlock()
        assertTrue(full.contains("Offene Punkte")); assertTrue(full.contains("DISC: D 40"))
        val small = m.toPromptBlock(budget = 260, focus = MemFocus.REPLY)
        assertTrue(small.length <= 270)
        assertTrue("Offene Punkte kommen zuerst", small.contains("Offene Punkte: Angebot schicken"))
        assertFalse("Verlauf passt nicht mehr", small.contains("Verlauf der Stimmung"))
        val noDisc = m.toPromptBlock(includeDisc = false)
        assertFalse(noDisc.contains("DISC"))
        assertEquals("Zusatzinfo vom Nutzer: Hallo", m.copy(userNote = "Hallo").toPromptBlock(budget = 1000).lines().first())
    }

    @Test fun codecRoundTripAndOldFormat() {
        val m = MemoryUpdater.apply(null, fullOutput, "Anna", 1_000L, listOf("a"), "10:00", 80, partnerMessages = 80, discEnabled = true)
        val back = MemoryCodec.fromJson(MemoryCodec.toJson(m))
        assertEquals(m, back)
        val old = """{"v":1,"key":"anna","name":"Anna","profile":"P","relationship":"R","open":"O","mood":"M","note":"N","updated":5,"anchor":["x"],"anchorTime":"t","seen":3}"""
        val o = MemoryCodec.fromJson(old)
        assertEquals("P", o.profile); assertEquals("", o.topics); assertNull(o.disc); assertEquals("N", o.userNote)
    }

    @Test fun moodHistoryDropsOldestFirst() {
        var h = ""
        for (i in 1..30) h = MemoryUpdater.appendMood(h, "%02d.01.".format(i), "Stimmung Nummer $i", 200)
        assertTrue(h.length <= 200)
        assertTrue(h.contains("Nummer 30")); assertFalse(h.contains("Nummer 1 "))
        assertEquals(h, MemoryUpdater.appendMood(h, "31.01.", "Stimmung Nummer 30", 200))
    }

    // ---------- Self profile ----------

    private val blocked = IchLogic.blockedFrom(listOf("Anna Müller", "Dr. Weber", "Familie Schmidt"))

    private fun merge(p: IchProfile, found: Map<IchCat, List<String>>, key: String = "chat1", texts: List<String> = emptyList(), now: Long = 1L, max: Int = IchLogic.MAX_CHARS) =
        IchLogic.merge(p, found, null, key, blocked, texts, now, max)

    @Test fun leakageNothingChatSpecificReachesTheIchProfile() {
        val chatMem = MemoryUpdater.apply(
            null,
            "STECKBRIEF: Anna Müller zieht nach Hamburg\nTHEMEN: Umzug nach Hamburg, Wohnungssuche\nFAKTEN: Besichtigung am 12.03. um 15 Uhr, Praxis Dr. Weber\nOFFEN: Kaution überweisen",
            "Anna Müller", 0L, emptyList(), "", 50,
        )
        val candidates = mapOf(
            IchCat.STYLE to listOf("schreibt kurze Sätze", "erwähnt Anna Müller oft", "Besichtigung am 12.03. um 15 Uhr", "https://beispiel.de/wohnung", "schreibt an Weber wegen der Praxis"),
            IchCat.INTERESTS to listOf("plant den Umzug nach Hamburg", "Wohnungssuche in Hamburg", "mag Fahrradtouren"),
            IchCat.TONE to listOf("direkt und freundlich", "Mail an mich@beispiel.de", "Familie Schmidt hilft beim Umzug"),
        )
        val p = merge(IchProfile(), candidates, texts = IchLogic.chatTextsOf(chatMem))
        val all = p.entries.joinToString(" | ") { it.text }
        for (bad in listOf("Anna", "Müller", "Weber", "Schmidt", "12.03", "15 Uhr", "http", "Hamburg", "Umzug", "Wohnungssuche", "Kaution", "@"))
            assertFalse("Durchgesickert: $bad in [$all]", all.contains(bad, ignoreCase = true))
        assertEquals(setOf("schreibt kurze Sätze", "mag Fahrradtouren", "direkt und freundlich"), p.entries.map { it.text }.toSet())
        // the prompt block contains none of it either
        val block = p.toPromptBlock()
        assertFalse(block.contains("Hamburg")); assertFalse(block.contains("Anna"))
    }

    @Test fun leakageAlsoBlocksPartialNamesCaseInsensitive() {
        assertNull(IchLogic.clean("schreibt wie anna", blocked, emptyList()))
        assertNull(IchLogic.clean("Treffen mit WEBER", blocked, emptyList()))
        assertNotNull(IchLogic.clean("formuliert knapp", blocked, emptyList()))
        assertNull(IchLogic.clean("", blocked, emptyList()))
        assertNull(IchLogic.clean("x".repeat(IchLogic.MAX_ENTRY + 1), blocked, emptyList()))
    }

    @Test fun selfAnalysisPartialsAreFilteredToo() {
        val out = "ICH-STIL: kurze Sätze; erwähnt Anna ständig\nICH-HUMOR: trocken; Termin am 3.4.\nICH-DISC: D=50 I=20 S=20 C=10; Konfidenz: mittel; Begründung: knapp"
        val p = SelfAnalysis.partialFrom(out, blocked, emptyList(), 100, "chat-a", 0L)
        assertEquals(listOf("kurze Sätze"), p.found[IchCat.STYLE])
        assertEquals(listOf("trocken"), p.found[IchCat.HUMOR])
        assertEquals(50, p.disc!!.d)
        assertFalse(p.chatHash.contains("chat-a"))
    }

    @Test fun mergeDeduplicatesAndCountsChatsByHash() {
        var p = merge(IchProfile(), mapOf(IchCat.STYLE to listOf("kurze Sätze")), key = "a")
        p = merge(p, mapOf(IchCat.STYLE to listOf("Kurze Sätze", "ironisch")), key = "b")
        p = merge(p, mapOf(IchCat.STYLE to listOf("ironisch")), key = "b")
        assertEquals(2, p.entries.size)
        assertEquals(2, p.chatCount)
        assertEquals(3, p.runs)
        assertTrue(p.chatHashes.none { it == "a" || it == "b" })
    }

    @Test fun pinnedSurviveCapAndOldestUnpinnedFallFirst() {
        var p = merge(IchProfile(), mapOf(IchCat.STYLE to listOf("alt eins aaaaaaaaaa")), now = 1)
        p = IchLogic.pin(p, p.entries[0], true)
        p = merge(p, mapOf(IchCat.TONE to listOf("alt zwei bbbbbbbbbb")), now = 2)
        p = merge(p, mapOf(IchCat.HUMOR to listOf("neu drei cccccccccc")), now = 3, max = 40)
        val texts = p.entries.map { it.text }
        assertTrue(texts.contains("alt eins aaaaaaaaaa"))
        assertFalse(texts.contains("alt zwei bbbbbbbbbb"))
        assertTrue(texts.contains("neu drei cccccccccc"))
        assertTrue(p.length <= 40)
    }

    @Test fun deleteLeavesTombstoneAndUserEntryWinsBack() {
        var p = merge(IchProfile(), mapOf(IchCat.STYLE to listOf("benutzt Ausrufezeichen")))
        p = IchLogic.delete(p, p.entries[0])
        assertTrue(p.entries.isEmpty())
        p = merge(p, mapOf(IchCat.STYLE to listOf("Benutzt Ausrufezeichen")), key = "c2")
        assertTrue("geloeschter Eintrag kommt nicht zurueck", p.entries.isEmpty())
        p = IchLogic.addByUser(p, IchCat.STYLE, "benutzt Ausrufezeichen", 9)
        assertEquals(1, p.entries.size); assertTrue(p.entries[0].pinned)
        assertTrue(p.suppressed.isEmpty())
        assertEquals(p, IchLogic.addByUser(p, IchCat.STYLE, "  benutzt   Ausrufezeichen ", 10))
    }

    @Test fun codecRoundTrip() {
        var p = merge(IchProfile(), mapOf(IchCat.STYLE to listOf("kurze Sätze"), IchCat.LANGS to listOf("Deutsch; Englisch")))
        p = IchLogic.pin(p, p.entries[0], true)
        p = IchLogic.delete(p, p.entries[1]).copy(disc = DiscProfile(40, 30, 20, 10, DiscLevel.MITTEL, "g", 100, 3))
        assertEquals(p, IchCodec.fromJson(IchCodec.toJson(p)))
    }

    @Test fun parseOutputReadsIchLines() {
        val (m, disc) = IchLogic.parseOutput("ICH-STIL: kurze Sätze; Emojis selten\nICH-TON: keine\nICH-DISC: D=1 I=2 S=3 C=4\nSonstiges: egal")
        assertEquals(listOf("kurze Sätze", "Emojis selten"), m[IchCat.STYLE])
        assertNull(m[IchCat.TONE])
        assertTrue(disc!!.startsWith("D=1"))
    }

    @Test fun ichBlockFlowsIntoAdviserAndSuggestPromptsButStaysShort() {
        var p = merge(IchProfile(), mapOf(IchCat.STYLE to listOf("kurze Sätze", "direkt"), IchCat.HUMOR to listOf("trocken")))
        val block = p.toPromptBlock()
        assertTrue(block.length <= IchLogic.PROMPT_MAX)
        assertTrue(Assist.adviserUser("Ziel", null, null, ich = block).contains("kurze Sätze"))
        assertTrue(Assist.suggestUser("", null, "Anna: hi", ich = block).contains("Stil von Ich"))
        assertFalse(Assist.suggestUser("", null, "Anna: hi", ich = "").contains("Stil von Ich"))
        assertTrue(IchProfile().toPromptBlock().isEmpty())
        p = p.copy(entries = emptyList()); assertTrue(p.isEmpty())
    }

    // ---------- Self-analysis ----------

    private fun msg(dir: Direction, text: String, kind: Kind = Kind.TEXT, sender: String? = null, time: String? = "12:00") = ChatMessage(kind, dir, sender, text, time)

    @Test fun requestParsedFromFreeTask() {
        val r = SelfAnalysis.parseRequest("Scanne 20 Chats, je letzte 200 Nachrichten, analysiere meine Persönlichkeit und meinen Stil")
        assertEquals(20, r.chats); assertEquals(200, r.perChat)
        assertTrue(r.focus.contains("Persönlichkeit"))
        val d = SelfAnalysis.parseRequest("Analysiere mich")
        assertEquals(SelfAnalysis.DEFAULT_CHATS, d.chats); assertEquals(SelfAnalysis.DEFAULT_PER_CHAT, d.perChat)
        val big = SelfAnalysis.parseRequest("999 Chats mit 99999 Nachrichten")
        assertEquals(SelfAnalysis.MAX_CHATS, big.chats); assertEquals(SelfAnalysis.MAX_PER_CHAT, big.perChat)
        assertEquals(10, SelfAnalysis.parseRequest("3 Nachrichten").perChat)
    }

    @Test fun titlesOnlyFromCheckupList() {
        val all = listOf("A", "B", "C", "D")
        assertEquals(listOf("A", "B"), SelfAnalysis.pickTitles(emptyList(), all, 2, fromSelection = false))
        assertEquals(listOf("B", "D"), SelfAnalysis.pickTitles(listOf("D", "B", "Fremd"), all, 5, fromSelection = true))
        assertEquals(listOf("B"), SelfAnalysis.pickTitles(listOf("B", "D"), all, 1, fromSelection = true))
        assertTrue(SelfAnalysis.pickTitles(listOf("Fremd"), all, 5, true).isEmpty())
        assertTrue(SelfAnalysis.pickTitles(emptyList(), emptyList(), 5, false).isEmpty())
    }

    @Test fun ownTranscriptHasOnlyOwnMessagesWithBriefContextAndNoNamesOrTimes() {
        val msgs = listOf(
            msg(Direction.IN, "Hast du morgen Zeit? ".repeat(10), sender = "Anna Müller", time = "09:15"),
            msg(Direction.OUT, "Ja, passt gut"),
            msg(Direction.IN, "Super, dann bis später", sender = "Anna Müller"),
            msg(Direction.IN, "Noch etwas ohne Antwort", sender = "Anna Müller"),
            msg(Direction.OUT, "", kind = Kind.IMAGE),
            msg(Direction.OUT, "Bis dann", time = "09:20"),
            msg(Direction.IN, "nur fremd", sender = "Anna Müller"),
        )
        val (t, n) = SelfAnalysis.ownTranscript(msgs, 10_000)
        assertEquals(2, n)
        assertTrue(t.contains("Ich: Ja, passt gut")); assertTrue(t.contains("Ich: Bis dann"))
        assertFalse(t.contains("nur fremd")); assertFalse(t.contains("Anna")); assertFalse(t.contains("09:1")); assertFalse(t.contains("09:20"))
        assertFalse("Kontext ist gekuerzt", t.contains("Hast du morgen Zeit? ".repeat(5)))
        assertTrue(t.contains("Gegenüber (Kontext)"))
    }

    @Test fun ownTranscriptKeepsNewestWhenSpaceIsShort() {
        val msgs = (1..50).map { msg(Direction.OUT, "Nachricht Nummer $it ".padEnd(60, '.')) }
        val (t, n) = SelfAnalysis.ownTranscript(msgs, 500)
        assertTrue(t.length <= 500); assertTrue(n in 1..10)
        assertTrue(t.contains("Nummer 50")); assertFalse(t.contains("Nummer 1 "))
        assertEquals(0, SelfAnalysis.ownTranscript(listOf(msg(Direction.IN, "x")), 500).second)
    }

    @Test fun proposalFallbackNeedsTwoChatsForEntries() {
        val a = SelfAnalysis.partialFrom("ICH-STIL: kurze Sätze; ironisch\nICH-DISC: D=50 I=20 S=20 C=10; Konfidenz: mittel", blocked, emptyList(), 100, "a", 0L)
        val b = SelfAnalysis.partialFrom("ICH-STIL: Kurze Sätze; förmlich\nICH-DISC: D=30 I=40 S=20 C=10; Konfidenz: mittel", blocked, emptyList(), 100, "b", 0L)
        val p = SelfAnalysis.proposal(listOf(a, b), null, blocked, 5L)
        assertEquals(listOf("kurze Sätze"), p.entries.map { it.text })
        assertEquals(2, p.chatCount)
        assertEquals(40, p.disc!!.d)
        // a single chat: all entries
        assertEquals(2, SelfAnalysis.proposal(listOf(a), null, blocked, 5L).entries.size)
        // with a model reply for the merge
        val m = SelfAnalysis.proposal(listOf(a, b), "ICH-STIL: knapp\nICH-DISC: zu wenig Daten", blocked, 5L)
        assertEquals(listOf("knapp"), m.entries.map { it.text })
    }

    @Test fun adoptWritesProposalAfterConfirmation() {
        val prop = SelfAnalysis.proposal(listOf(SelfAnalysis.partialFrom("ICH-STIL: knapp", blocked, emptyList(), 50, "a", 0L)), null, blocked, 1L)
        val mine = IchLogic.addByUser(IchProfile(), IchCat.VALUES, "Verlässlichkeit", 0)
        val out = IchLogic.adopt(mine, prop, blocked, 2L)
        assertTrue(out.entries.any { it.text == "Verlässlichkeit" && it.pinned })
        assertTrue(out.entries.any { it.text == "knapp" })
        assertEquals(1, out.chatCount)
    }

    @Test fun selfCodecRoundTrip() {
        val a = SelfAnalysis.partialFrom("ICH-STIL: kurze Sätze\nICH-DISC: D=50 I=20 S=20 C=10; Konfidenz: mittel", blocked, emptyList(), 100, "a", 0L)
        val prop = SelfAnalysis.proposal(listOf(a), null, blocked, 5L)
        val (parts, p2) = SelfCodec.partialsFromJson(SelfCodec.partialsToJson(listOf(a), prop))
        assertEquals(1, parts.size)
        assertEquals(a.found, parts[0].found); assertEquals(a.chatHash, parts[0].chatHash); assertEquals(a.ownMessages, parts[0].ownMessages)
        assertEquals(prop, p2)
        assertNull(SelfCodec.partialsFromJson(SelfCodec.partialsToJson(listOf(a), null)).second)
    }
}
