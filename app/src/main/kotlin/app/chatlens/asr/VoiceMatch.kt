package app.chatlens.asr

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs

/** Eine Audiodatei im freigegebenen Ordner. [id] ist eine quellenspezifische Kennung (bei SAF die Dokument-URI). */
data class VoiceFile(val id: String, val name: String, val lastModified: Long, val size: Long)

/** Eine Sprachnachricht aus dem Chat: Datum aus dem letzten Datumstrenner (falls bekannt), Uhrzeit und angezeigte Dauer. */
data class VoiceQuery(val key: Int, val date: LocalDate?, val time: LocalTime?, val durationSec: Int?)

class VoiceMatch(val key: Int, val file: VoiceFile?, val reason: String)

/** Wandelt Datumstrenner von WhatsApp (heute, gestern, Wochentag, Datum) in ein Datum. */
object DateLabels {
    private val dow = mapOf(
        "montag" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY, "dienstag" to DayOfWeek.TUESDAY, "tuesday" to DayOfWeek.TUESDAY,
        "mittwoch" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY, "donnerstag" to DayOfWeek.THURSDAY, "thursday" to DayOfWeek.THURSDAY,
        "freitag" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY, "samstag" to DayOfWeek.SATURDAY, "saturday" to DayOfWeek.SATURDAY,
        "sonntag" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY,
    )
    private val months = listOf("jan", "feb", "mär", "apr", "mai", "jun", "jul", "aug", "sep", "okt", "nov", "dez")
    private val monthsEn = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private fun year(y: Int) = if (y < 100) 2000 + y else y

    private fun monthOf(w: String): Int? {
        val p = w.take(3)
        val i = months.indexOf(p).takeIf { it >= 0 } ?: monthsEn.indexOf(p).takeIf { it >= 0 } ?: return null
        return i + 1
    }

    fun resolve(label: String, today: LocalDate): LocalDate? {
        val t = label.trim().lowercase(Locale.ROOT)
        if (t.isEmpty()) return null
        when (t) {
            "heute", "today" -> return today
            "gestern", "yesterday" -> return today.minusDays(1)
        }
        dow[t]?.let { d ->
            var diff = (today.dayOfWeek.value - d.value + 7) % 7
            if (diff == 0) diff = 7
            return today.minusDays(diff.toLong())
        }
        Regex("""^(\d{1,2})\.\s?(\d{1,2})\.\s?(\d{2,4})$""").find(t)?.let { return safe(year(it.groupValues[3].toInt()), it.groupValues[2].toInt(), it.groupValues[1].toInt()) }
        Regex("""^(\d{1,2})/(\d{1,2})/(\d{2,4})$""").find(t)?.let {
            val a = it.groupValues[1].toInt(); val b = it.groupValues[2].toInt()
            // Tag/Monat; ist die erste Zahl kein Tag-Wert nur als Monat moeglich (Monat/Tag)
            return if (a > 12) safe(year(it.groupValues[3].toInt()), b, a) else if (b > 12) safe(year(it.groupValues[3].toInt()), a, b) else null
        }
        Regex("""^(\d{4})-(\d{2})-(\d{2})$""").find(t)?.let { return safe(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
        Regex("""^(\d{1,2})\.?\s+([a-zäöü]+)\.?\s+(\d{2,4})$""").find(t)?.let { m ->
            val mo = monthOf(m.groupValues[2]) ?: return null
            return safe(year(m.groupValues[3].toInt()), mo, m.groupValues[1].toInt())
        }
        Regex("""^([a-zäöü]+)\.?\s+(\d{1,2}),?\s+(\d{2,4})$""").find(t)?.let { m ->
            val mo = monthOf(m.groupValues[1]) ?: return null
            return safe(year(m.groupValues[3].toInt()), mo, m.groupValues[2].toInt())
        }
        return null
    }

    private fun safe(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    /** Angezeigte Dauer einer Sprachnachricht ("0:23", "1:05", "1:02:03") in Sekunden, null wenn keine gefunden. */
    fun durationSec(text: String): Int? {
        val m = Regex("""(?<!\d)(\d{1,2}):(\d{2})(?::(\d{2}))?(?!\d)""").find(text) ?: return null
        val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); val c = m.groupValues[3]
        return if (c.isEmpty()) a * 60 + b else a * 3600 + b * 60 + c.toInt()
    }

    /** Uhrzeit "HH:mm" (auch 12-Stunden-Angabe mit AM/PM) oder null. */
    fun time(text: String?): LocalTime? {
        if (text == null) return null
        val m = Regex("""^\s*(\d{1,2})[:.](\d{2})\s*([AaPp])?\.?[Mm]?\.?\s*$""").find(text) ?: return null
        var h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        val ap = m.groupValues[3].lowercase()
        if (ap == "p" && h < 12) h += 12
        if (ap == "a" && h == 12) h = 0
        return if (h in 0..23 && mi in 0..59) LocalTime.of(h, mi) else null
    }
}

/**
 * Ordnet Sprachnachrichten des Chats den Audiodateien zu. WhatsApp zeigt in der Nachricht nur Uhrzeit und Dauer, nicht den Dateinamen.
 * Eine Datei passt, wenn ihr Aenderungsdatum in der Minute der Nachricht (plus Toleranz) liegt und die Dauer (falls beide bekannt) um hoechstens
 * [durationTolSec] abweicht. Mehrdeutige oder doppelt belegte Treffer bleiben ohne Zuordnung. Das ist ein Verfahren, keine Gewissheit.
 */
object VoiceMatcher {
    private const val AMBIGUOUS_GAP = 0.5

    private class Cand(val q: VoiceQuery, val f: VoiceFile, val score: Double)

    fun match(
        queries: List<VoiceQuery>, files: List<VoiceFile>, durationOf: (VoiceFile) -> Double?,
        zone: ZoneId, toleranceMin: Int, durationTolSec: Double = 3.0,
    ): List<VoiceMatch> {
        val tolMs = toleranceMin * 60_000L
        val durCache = HashMap<String, Double?>()
        fun dur(f: VoiceFile) = durCache.getOrPut(f.id) { durationOf(f) }
        val result = LinkedHashMap<Int, VoiceMatch>()
        val bestPerQuery = ArrayList<Cand>()
        for (q in queries) {
            if (q.time == null) { result[q.key] = VoiceMatch(q.key, null, "Uhrzeit der Nachricht unbekannt"); continue }
            val inTime = files.filter { inWindow(q, it, zone, tolMs) }
            if (inTime.isEmpty()) { result[q.key] = VoiceMatch(q.key, null, "keine Datei im Zeitfenster"); continue }
            val cands = ArrayList<Cand>()
            for (f in inTime) {
                val d = dur(f)
                val tdMin = timeDiffMs(q, f, zone) / 60_000.0
                val score = if (d != null && q.durationSec != null) {
                    val dd = abs(d - q.durationSec)
                    if (dd > durationTolSec) continue
                    dd + tdMin * 0.05
                } else 10.0 + tdMin
                cands.add(Cand(q, f, score))
            }
            if (cands.isEmpty()) { result[q.key] = VoiceMatch(q.key, null, "Dauer passt zu keiner Datei im Zeitfenster"); continue }
            cands.sortBy { it.score }
            if (cands.size > 1 && cands[1].score - cands[0].score < AMBIGUOUS_GAP) {
                result[q.key] = VoiceMatch(q.key, null, "mehrdeutig (${cands.count { it.score - cands[0].score < AMBIGUOUS_GAP }} Dateien passen)")
                continue
            }
            bestPerQuery.add(cands[0])
        }
        val taken = HashSet<String>()
        for (c in bestPerQuery.sortedBy { it.score }) {
            if (taken.add(c.f.id)) result[c.q.key] = VoiceMatch(c.q.key, c.f, "ok")
            else result[c.q.key] = VoiceMatch(c.q.key, null, "Datei bereits einer anderen Nachricht zugeordnet")
        }
        return queries.map { result[it.key] ?: VoiceMatch(it.key, null, "nicht zugeordnet") }
    }

    private fun startMs(q: VoiceQuery, f: VoiceFile, zone: ZoneId): Long? {
        val date = q.date ?: return null
        return ZonedDateTime.of(date, q.time!!.withSecond(0).withNano(0), zone).toInstant().toEpochMilli()
    }

    private fun inWindow(q: VoiceQuery, f: VoiceFile, zone: ZoneId, tolMs: Long): Boolean = timeDiffMs(q, f, zone) <= tolMs

    /** Abstand der Datei vom Minutenfenster der Nachricht in Millisekunden (0 innerhalb der Minute). Ohne Datum nur Tageszeit, zyklisch. */
    private fun timeDiffMs(q: VoiceQuery, f: VoiceFile, zone: ZoneId): Long {
        val start = startMs(q, f, zone)
        if (start != null) {
            val end = start + 59_999L
            return when { f.lastModified < start -> start - f.lastModified; f.lastModified > end -> f.lastModified - end; else -> 0L }
        }
        val dayMs = 86_400_000L
        val fileTod = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(f.lastModified), zone).toLocalTime().toNanoOfDay() / 1_000_000L
        val s = q.time!!.withSecond(0).withNano(0).toNanoOfDay() / 1_000_000L
        val e = s + 59_999L
        val d = when { fileTod < s -> s - fileTod; fileTod > e -> fileTod - e; else -> 0L }
        return minOf(d, dayMs - d)
    }
}
