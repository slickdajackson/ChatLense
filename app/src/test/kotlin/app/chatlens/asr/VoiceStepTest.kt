package app.chatlens.asr

import app.chatlens.core.ChatMessage
import app.chatlens.core.Direction
import app.chatlens.core.Kind
import app.chatlens.llm.ContextBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class VoiceStepTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 10, 3)

    private fun fixture() = javaClass.getResourceAsStream("/asr/tone-mono-6s.opus")!!.readBytes()
    private fun stamp(h: Int, m: Int, s: Int = 0, d: LocalDate = today) = ZonedDateTime.of(d, LocalTime.of(h, m, s), zone).toInstant().toEpochMilli()

    private fun voiceFile(dir: File, name: String, at: Long, bytes: ByteArray = fixture()): File {
        dir.mkdirs()
        return File(dir, name).also { it.writeBytes(bytes); it.setLastModified(at) }
    }

    private fun msg(kind: Kind, text: String, time: String?) = ChatMessage(kind, Direction.IN, "Anna", text, time)

    private class FakePipe(val text: String = "Hallo Welt", val failOn: Int = -1) : VoicePipeline {
        var decodes = 0; var recognizes = 0
        override fun decode(bytes: ByteArray): DecodedAudio { decodes++; return ConcentusOpusDecoder.decode(bytes) }
        override fun recognize(a: DecodedAudio): String { recognizes++; if (recognizes == failOn) throw IllegalStateException("boom"); return text + " " + recognizes }
    }

    private val cfg = VoiceStepConfig(maxPerChat = 20, maxSeconds = 180, toleranceMin = 3)

    @Test
    fun findsVoiceMessagesAndAssignsDatesFromSeparators() {
        val msgs = listOf(
            msg(Kind.VOICE, "0:06", "08:00"),
            msg(Kind.DATE, "Gestern", null),
            msg(Kind.VOICE, "0:06", "10:00"),
            msg(Kind.TEXT, "hi", "10:01"),
            msg(Kind.DATE, "Heute", null),
            msg(Kind.VOICE, "0:06", "11:00"),
        )
        val q = VoiceTranscriptionStep.queries(msgs, today)
        assertEquals(3, q.size)
        assertNull(q[0].second.date) // unknown before the first date separator
        assertEquals(today.minusDays(1), q[1].second.date)
        assertEquals(today, q[2].second.date)
        assertEquals(6, q[2].second.durationSec)
        assertEquals(LocalTime.of(11, 0), q[2].second.time)
    }

    @Test
    fun transcribesMatchedMessageAndAppearsInContext() {
        val dir = tmp.newFolder("voice")
        voiceFile(File(dir, "202640"), "PTT-20261003-WA0001.opus", stamp(11, 0, 20))
        voiceFile(File(dir, "202640"), "PTT-20261003-WA0002.opus", stamp(15, 0, 5), ByteArray(0)) // broken, different time
        File(dir, "202640/readme.txt").writeText("x")
        val m = msg(Kind.VOICE, "0:06", "11:00")
        val msgs = listOf(msg(Kind.DATE, "Heute", null), m)
        val rep = VoiceTranscriptionStep.run(msgs, cfg, FileVoiceSource(dir), FakePipe(), zone, today)
        assertEquals(1, rep.voiceMessages); assertEquals(1, rep.matched); assertEquals(1, rep.transcribed)
        assertEquals("Hallo Welt 1", m.transcript)
        assertNotNull(m.audioRef)
        assertEquals(2, rep.filesListed) // only .opus
        val ctx = ContextBuilder.build(msgs, "Anna", 10000, true, 1000).transcript
        assertTrue(ctx, ctx.contains("Transkript: Hallo Welt 1"))
        val s = rep.summary()
        assertTrue(s, s.contains("transkribiert 1"))
        assertFalse(s.contains("Hallo")); assertFalse(s.contains("PTT-"))
    }

    @Test
    fun cacheAvoidsSecondRecognition() {
        val dir = tmp.newFolder("v2")
        voiceFile(dir, "a.opus", stamp(11, 0, 20))
        val cache = MemoryVoiceCache()
        val pipe = FakePipe()
        val m1 = msg(Kind.VOICE, "0:06", "11:00")
        VoiceTranscriptionStep.run(listOf(msg(Kind.DATE, "Heute", null), m1), cfg, FileVoiceSource(dir), pipe, zone, today, cache)
        val m2 = msg(Kind.VOICE, "0:06", "11:00")
        val rep = VoiceTranscriptionStep.run(listOf(msg(Kind.DATE, "Heute", null), m2), cfg, FileVoiceSource(dir), pipe, zone, today, cache)
        assertEquals(1, pipe.recognizes)
        assertEquals(1, rep.fromCache)
        assertEquals(m1.transcript, m2.transcript)
    }

    @Test
    fun existingTranscriptIsKeptAndNothingIsDone() {
        val m = msg(Kind.VOICE, "0:06", "11:00").also { it.transcript = "schon da" }
        val src = object : VoiceFileSource { override val label = "x"; override fun list(): List<VoiceFile> = throw AssertionError("do not call"); override fun read(f: VoiceFile) = ByteArray(0) }
        val rep = VoiceTranscriptionStep.run(listOf(m), cfg, src, FakePipe(), zone, today)
        assertEquals("schon da", m.transcript)
        assertEquals(1, rep.voiceMessages); assertEquals(0, rep.considered)
    }

    @Test
    fun limitTakesNewestFirstAndCountsSkipped() {
        val dir = tmp.newFolder("v3")
        val msgs = ArrayList<ChatMessage>()
        msgs.add(msg(Kind.DATE, "Heute", null))
        for (i in 0 until 4) {
            voiceFile(dir, "f$i.opus", stamp(9 + i, 0, 10))
            msgs.add(msg(Kind.VOICE, "0:06", "%02d:00".format(9 + i)))
        }
        val rep = VoiceTranscriptionStep.run(msgs, VoiceStepConfig(2, 180, 3), FileVoiceSource(dir), FakePipe(), zone, today)
        assertEquals(2, rep.skippedByLimit); assertEquals(2, rep.transcribed)
        assertNull(msgs[1].transcript); assertNull(msgs[2].transcript)
        assertNotNull(msgs[3].transcript); assertNotNull(msgs[4].transcript)
    }

    @Test
    fun tooLongUnmatchedAndFailuresAreCountedWithoutStopping() {
        val dir = tmp.newFolder("v4")
        voiceFile(dir, "long.opus", stamp(9, 0, 5))
        voiceFile(dir, "bad.opus", stamp(10, 0, 5))
        voiceFile(dir, "ok.opus", stamp(11, 0, 5))
        val msgs = listOf(
            msg(Kind.DATE, "Heute", null),
            msg(Kind.VOICE, "0:06", "09:00"), msg(Kind.VOICE, "0:06", "10:00"), msg(Kind.VOICE, "0:06", "11:00"), msg(Kind.VOICE, "0:06", "13:00"),
        )
        val rep = VoiceTranscriptionStep.run(msgs, VoiceStepConfig(20, 3, 3), FileVoiceSource(dir), FakePipe(failOn = 1), zone, today)
        // maxSeconds 3: the 6-second files are all too long
        assertEquals(3, rep.tooLong); assertEquals(1, rep.unmatched); assertEquals(0, rep.transcribed)
        val rep2 = VoiceTranscriptionStep.run(msgs, VoiceStepConfig(20, 180, 3), FileVoiceSource(dir), FakePipe(failOn = 1), zone, today)
        assertEquals(1, rep2.failed); assertEquals(2, rep2.transcribed); assertEquals(1, rep2.unmatched)
        assertTrue(rep2.reasons.keys.any { it.startsWith("Fehler") })
    }

    @Test
    fun folderErrorLeavesMessagesUntranscribed() {
        val src = object : VoiceFileSource { override val label = "x"; override fun list(): List<VoiceFile> = throw SecurityException("weg"); override fun read(f: VoiceFile) = ByteArray(0) }
        val m = msg(Kind.VOICE, "0:06", "11:00")
        val rep = VoiceTranscriptionStep.run(listOf(m), cfg, src, FakePipe(), zone, today)
        assertNull(m.transcript)
        assertTrue(rep.folderError!!.contains("SecurityException"))
        assertEquals(1, rep.unmatched)
        val ctx = ContextBuilder.build(listOf(m), "Anna", 10000, true, 1000).transcript
        assertTrue(ctx, ctx.contains("nicht transkribiert"))
    }

    @Test
    fun cancelStopsEarly() {
        val dir = tmp.newFolder("v5")
        voiceFile(dir, "a.opus", stamp(11, 0, 5))
        val m = msg(Kind.VOICE, "0:06", "11:00")
        val rep = VoiceTranscriptionStep.run(listOf(msg(Kind.DATE, "Heute", null), m), cfg, FileVoiceSource(dir), FakePipe(), zone, today, cancelled = { true })
        assertNull(m.transcript); assertEquals(0, rep.transcribed); assertTrue(rep.reasons.containsKey("abgebrochen"))
    }

    @Test
    fun fileCacheRoundTripAndClear() {
        val c = FileVoiceCache(File(tmp.root, "cache"))
        assertNull(c.get("k"))
        c.put("k", "Grüße aus Köln")
        assertEquals("Grüße aus Köln", c.get("k"))
        assertEquals(1, c.count())
        assertEquals(1, c.clear())
        assertNull(c.get("k"))
        val f = VoiceFile("id", "n.opus", 5, 6)
        assertEquals(VoiceTranscriptionStep.cacheKey(f), VoiceTranscriptionStep.cacheKey(f.copy()))
        assertTrue(VoiceTranscriptionStep.cacheKey(f) != VoiceTranscriptionStep.cacheKey(f.copy(lastModified = 6)))
    }
}
