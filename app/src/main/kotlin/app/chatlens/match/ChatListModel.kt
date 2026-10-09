package app.chatlens.match

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** One entry of the WhatsApp chat list (read from the accessibility tree). */
data class ChatListEntry(
    val title: String,
    val preview: String,
    val timeText: String,
    val pinned: Boolean,
    /** Row "Archiviert": not a chat, but the entry into the archive. */
    val archiveRow: Boolean,
    /** Heuristic: the preview starts with "Name: " (typical of groups). Not checked on a device. */
    val likelyGroup: Boolean,
    val order: Int,
    /** Hint of unread messages (badge, description). false means no hint was recognized, not "certainly read". Not checked on a device. */
    val unread: Boolean = false,
    /** Number on the badge, 0 if unknown. */
    val unreadCount: Int = 0,
)

enum class PinnedMode { COUNT_NORMALLY, EXCLUDE }

/** Turn chat-list time labels ("14:32", "Gestern", "Montag", "01.10.2026") into a comparable number (larger = newer). */
object ChatTimeRank {
    private val weekdays = mapOf(
        "montag" to DayOfWeek.MONDAY, "dienstag" to DayOfWeek.TUESDAY, "mittwoch" to DayOfWeek.WEDNESDAY,
        "donnerstag" to DayOfWeek.THURSDAY, "freitag" to DayOfWeek.FRIDAY, "samstag" to DayOfWeek.SATURDAY, "sonntag" to DayOfWeek.SUNDAY,
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY,
    )
    private val hm = Regex("""^(\d{1,2})[:.](\d{2})(\s?(am|pm))?$""", RegexOption.IGNORE_CASE)
    private val dmy = Regex("""^(\d{1,2})[./](\d{1,2})[./](\d{2,4})$""")

    const val UNKNOWN = Long.MIN_VALUE

    fun rank(timeText: String, now: LocalDateTime): Long {
        val t = timeText.trim().lowercase()
        if (t.isEmpty()) return UNKNOWN
        val today = now.toLocalDate()
        fun day(d: LocalDate, minute: Int) = d.toEpochDay() * 1440L + minute
        hm.matchEntire(t)?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            val ap = m.groupValues[4].lowercase()
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            return day(today, h * 60 + min)
        }
        if (t == "gestern" || t == "yesterday") return day(today.minusDays(1), 720)
        if (t == "heute" || t == "today") return day(today, 720)
        weekdays[t]?.let { wd ->
            // WhatsApp shows weekdays from the day before yesterday; "Gestern" means yesterday
            var d = today.minusDays(2)
            repeat(7) { if (d.dayOfWeek != wd) d = d.minusDays(1) }
            return day(d, 720)
        }
        dmy.matchEntire(t)?.let { m ->
            val dd = m.groupValues[1].toInt()
            val mm = m.groupValues[2].toInt()
            var yy = m.groupValues[3].toInt()
            if (yy < 100) yy += 2000
            return runCatching { day(LocalDate.of(yy, mm, dd), 720) }.getOrDefault(UNKNOWN)
        }
        return UNKNOWN
    }
}

/** Picks the newest N chats from the chat list that was read. */
object ChatListSelector {
    private val archiveTitles = setOf("archiviert", "archived")

    fun isArchiveRow(title: String): Boolean = NameMatcher.normalize(title) in archiveTitles

    fun select(
        entries: List<ChatListEntry>,
        n: Int,
        includeGroups: Boolean,
        pinnedMode: PinnedMode,
        now: LocalDateTime,
    ): List<ChatListEntry> {
        val seen = HashSet<String>()
        val filtered = entries
            .filter { !it.archiveRow && !isArchiveRow(it.title) }
            .filter { includeGroups || !it.likelyGroup }
            .filter { pinnedMode == PinnedMode.COUNT_NORMALLY || !it.pinned }
            .filter { seen.add(NameMatcher.normalize(it.title)) }
        // stable, descending by time; entries without a readable time keep their list position relative to each other at the end
        val ranked = filtered.sortedWith(
            compareByDescending<ChatListEntry> { ChatTimeRank.rank(it.timeText, now) }.thenBy { it.order },
        )
        return ranked.take(n.coerceAtLeast(0))
    }
}
