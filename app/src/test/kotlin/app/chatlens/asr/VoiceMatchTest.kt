package app.chatlens.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class VoiceMatchTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 10, 3) // Saturday

    private fun ms(d: LocalDate, h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(d, LocalTime.of(h, m, s), zone).toInstant().toEpochMilli()
    private fun file(id: String, d: LocalDate, h: Int, m: Int, s: Int = 0) = VoiceFile(id, "PTT-$id.opus", ms(d, h, m, s), 5000)

    @Test
    fun dateLabelsResolve() {
        assertEquals(today, DateLabels.resolve("Heute", today))
        assertEquals(today.minusDays(1), DateLabels.resolve("gestern", today))
        assertEquals(LocalDate.of(2026, 10, 2), DateLabels.resolve("Freitag", today))
        assertEquals(LocalDate.of(2026, 9, 27), DateLabels.resolve("Sonntag", today))
        assertEquals(LocalDate.of(2026, 9, 26), DateLabels.resolve("Samstag", today)) // the same weekday means one week earlier
        assertEquals(LocalDate.of(2026, 3, 5), DateLabels.resolve("05.03.2026", today))
        assertEquals(LocalDate.of(2026, 3, 5), DateLabels.resolve("5. 3. 26", today))
        assertEquals(LocalDate.of(2026, 3, 25), DateLabels.resolve("25/03/2026", today))
        assertEquals(LocalDate.of(2026, 3, 5), DateLabels.resolve("2026-03-05", today))
        assertEquals(LocalDate.of(2025, 12, 24), DateLabels.resolve("24. Dez. 2025", today))
        assertEquals(LocalDate.of(2025, 10, 5), DateLabels.resolve("5 Oct 2025", today))
        assertEquals(LocalDate.of(2025, 10, 5), DateLabels.resolve("Oct 5, 2025", today))
        assertNull(DateLabels.resolve("irgendwas", today))
        assertNull(DateLabels.resolve("31.02.2026", today))
        assertNull(DateLabels.resolve("", today))
    }

    @Test
    fun durationAndTimeParsing() {
        assertEquals(23, DateLabels.durationSec("0:23"))
        assertEquals(65, DateLabels.durationSec("Sprachnachricht 1:05"))
        assertEquals(3723, DateLabels.durationSec("1:02:03"))
        assertNull(DateLabels.durationSec("kein Wert"))
        assertEquals(LocalTime.of(9, 5), DateLabels.time("09:05"))
        assertEquals(LocalTime.of(21, 5), DateLabels.time("9:05 PM"))
        assertEquals(LocalTime.of(0, 5), DateLabels.time("12:05 AM"))
        assertNull(DateLabels.time("abc"))
        assertNull(DateLabels.time(null))
    }

    @Test
    fun matchesByMinuteAndDuration() {
        val files = listOf(file("a", today, 14, 31, 10), file("b", today, 14, 31, 40), file("c", today, 9, 0))
        val dur = mapOf("a" to 23.0, "b" to 61.0, "c" to 5.0)
        val q = listOf(VoiceQuery(0, today, LocalTime.of(14, 31), 23), VoiceQuery(1, today, LocalTime.of(14, 31), 60))
        val r = VoiceMatcher.match(q, files, { dur[it.id] }, zone, 3).associateBy { it.key }
        assertEquals("a", r[0]!!.file!!.id)
        assertEquals("b", r[1]!!.file!!.id)
    }

    @Test
    fun ambiguousAndTooFarAreNotMatched() {
        val files = listOf(file("a", today, 14, 31, 10), file("b", today, 14, 31, 40))
        val q = VoiceQuery(0, today, LocalTime.of(14, 31), 20)
        val same = VoiceMatcher.match(listOf(q), files, { 20.0 }, zone, 3)
        assertNull(same[0].file); assertTrue(same[0].reason, same[0].reason.startsWith("mehrdeutig"))
        val zeroTol = VoiceMatcher.match(listOf(q), files, { 20.0 }, zone, 0).single()
        assertNull(zeroTol.file) // both in the same minute, same duration: ambiguous
        val far = VoiceMatcher.match(listOf(q), listOf(file("z", today, 15, 10)), { 20.0 }, zone, 3).single()
        assertNull(far.file)
    }

    @Test
    fun zeroToleranceStillWorksWithinTheMinuteAndRejectsOutside() {
        val q = VoiceQuery(0, today, LocalTime.of(14, 31), 20)
        val inside = VoiceMatcher.match(listOf(q), listOf(file("a", today, 14, 31, 59)), { 20.0 }, zone, 0).single()
        assertEquals("a", inside.file!!.id)
        val outside = VoiceMatcher.match(listOf(q), listOf(file("a", today, 14, 32, 1)), { 20.0 }, zone, 0).single()
        assertNull(outside.file); assertEquals("keine Datei im Zeitfenster", outside.reason)
        val tol = VoiceMatcher.match(listOf(q), listOf(file("a", today, 14, 33, 30)), { 20.0 }, zone, 3).single()
        assertEquals("a", tol.file!!.id)
    }

    @Test
    fun durationMismatchRejects() {
        val q = VoiceQuery(0, today, LocalTime.of(14, 31), 20)
        val r = VoiceMatcher.match(listOf(q), listOf(file("a", today, 14, 31)), { 35.0 }, zone, 3).single()
        assertNull(r.file); assertTrue(r.reason.startsWith("Dauer passt"))
        val ok = VoiceMatcher.match(listOf(q), listOf(file("a", today, 14, 31)), { 22.5 }, zone, 3).single()
        assertNotNull(ok.file)
    }

    @Test
    fun unknownDurationFallsBackToUniqueTime() {
        val q = VoiceQuery(0, today, LocalTime.of(14, 31), null)
        val one = VoiceMatcher.match(listOf(q), listOf(file("a", today, 14, 31)), { null }, zone, 3).single()
        assertEquals("a", one.file!!.id)
        val two = VoiceMatcher.match(listOf(q), listOf(file("a", today, 14, 31), file("b", today, 14, 31, 30)), { null }, zone, 3).single()
        assertNull(two.file)
        assertNull(VoiceMatcher.match(listOf(VoiceQuery(0, today, null, 5)), listOf(file("a", today, 14, 31)), { 5.0 }, zone, 3).single().file)
    }

    @Test
    fun dateIsRespectedAndUnknownDateUsesTimeOfDay() {
        val yesterday = today.minusDays(1)
        val files = listOf(file("old", yesterday, 14, 31), file("new", today, 14, 31))
        val withDate = VoiceMatcher.match(listOf(VoiceQuery(0, today, LocalTime.of(14, 31), 10)), files, { 10.0 }, zone, 3).single()
        assertEquals("new", withDate.file!!.id)
        // no date: both match by time of day, same duration, so ambiguous
        val noDate = VoiceMatcher.match(listOf(VoiceQuery(0, null, LocalTime.of(14, 31), 10)), files, { 10.0 }, zone, 3).single()
        assertNull(noDate.file)
        // no date, but different durations: unambiguous
        val byDur = VoiceMatcher.match(listOf(VoiceQuery(0, null, LocalTime.of(14, 31), 10)), files, { if (it.id == "old") 30.0 else 10.0 }, zone, 3).single()
        assertEquals("new", byDur.file!!.id)
    }

    @Test
    fun midnightBoundaryAndOneFileForOneMessage() {
        val d = LocalDate.of(2026, 10, 2)
        val f = file("a", today, 0, 1, 20)
        val q = VoiceQuery(0, d, LocalTime.of(23, 59), 12)
        assertEquals("a", VoiceMatcher.match(listOf(q), listOf(f), { 12.0 }, zone, 3).single().file!!.id)
        // two messages, one file: only the better match gets it
        val q1 = VoiceQuery(1, today, LocalTime.of(10, 0), 10); val q2 = VoiceQuery(2, today, LocalTime.of(10, 0), 12)
        val r = VoiceMatcher.match(listOf(q1, q2), listOf(file("x", today, 10, 0)), { 10.0 }, zone, 3).associateBy { it.key }
        assertEquals("x", r[1]!!.file!!.id)
        assertNull(r[2]!!.file); assertTrue(r[2]!!.reason.contains("bereits"))
    }
}
