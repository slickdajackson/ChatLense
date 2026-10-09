package app.chatlens.checkup

import app.chatlens.agent.AgentException
import app.chatlens.match.ChatListEntry
import app.chatlens.match.ChatListSelector
import app.chatlens.match.NameMatcher
import app.chatlens.match.PinnedMode
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import kotlin.coroutines.coroutineContext

/** Why the checkup scan ended. */
enum class StopReason(val text: String) {
    LIMIT("gewuenschte Anzahl erreicht"),
    END("Listenende erreicht oder kein weiteres Scrollen moeglich"),
    PAGE_LIMIT("Seitengrenze erreicht"),
    SCROLL_FAILED("Scrollen nicht moeglich"),
}

/** Result of one scroll step. DONE: a gesture or action was performed. END_SIGNAL: Android refuses to scroll further (end). NO_WAY: there was no way to scroll (no list, gesture refused, no action). */
enum class ScrollTry { DONE, END_SIGNAL, NO_WAY }

class CheckupScan(
    val entries: List<ChatListEntry>,
    val reason: StopReason,
    val pages: Int,
    val gaps: Int,
    val duplicates: Int,
    val archiveSeen: Boolean,
    /** Number of scroll attempts that were performed (gesture or node action). */
    val scrollAttempts: Int = 0,
) {
    /** The end of the list counts as proven only if a scroll happened and the content then stayed the same (or Android reports the end). */
    val endProven: Boolean get() = reason == StopReason.LIMIT || (reason == StopReason.END && scrollAttempts >= 1)

    /** Warning for the UI: scrolling impossible, or fewer than [MIN_PLAUSIBLE] rows without a proven end. */
    fun warning(limit: Int): String? = when {
        reason == StopReason.SCROLL_FAILED -> "Scrollen nicht moeglich: nur ${entries.size} Zeilen von der ersten Seite gelesen. Die Liste ist wahrscheinlich laenger. Debug-Baum der Chatliste exportieren und den Checkup wiederholen."
        entries.size < MIN_PLAUSIBLE && entries.size < limit && !endProven -> "Nur ${entries.size} Zeilen gelesen und das Listenende ist nicht erwiesen (kein Scrollversuch mit unveraendertem Inhalt). Bitte pruefen."
        entries.size < MIN_PLAUSIBLE && entries.size < limit -> "Nur ${entries.size} Zeilen gelesen; das Listenende wurde nach $scrollAttempts Scrollversuchen ohne neue Zeilen gemeldet. Falls WhatsApp mehr Chats hat, bitte den Checkup wiederholen."
        else -> null
    }

    companion object { const val MIN_PLAUSIBLE = 15 }
}

/** What the scan needs from the device. On the device, the navigator. In tests, a fake. */
interface CheckupDevice {
    /** Guard, reach the chat list (Chats tab), scroll to the top. */
    suspend fun prepare()

    /** Visible rows of the chat list right now. */
    suspend fun readVisible(): List<ChatListEntry>

    /** One step downward. [precise]: page by page via a node action instead of a swipe. false: not possible. */
    suspend fun scrollForward(precise: Boolean): Boolean

    /** Like [scrollForward], but distinguishing "end reported" from "there is no way to scroll". Default: false means end. */
    suspend fun scrollStep(precise: Boolean): ScrollTry = if (scrollForward(precise)) ScrollTry.DONE else ScrollTry.END_SIGNAL

    /** One step upward (after a detected gap). */
    suspend fun scrollBackward(): Boolean

    /** Is the Chats tab selected (after a correction attempt)? True also when the bar is not recognized. */
    suspend fun chatsTabOk(): Boolean
    fun log(msg: String)
}

/** Shorten names in the log: two characters and the length, so the log does not carry full names. */
object NameMask {
    fun short(name: String): String {
        val t = name.trim()
        if (t.isEmpty()) return "(leer)"
        return t.take(2) + "*** (${t.length} Zeichen)"
    }
}

/**
 * Checkup scan of the chat list: reads rows only (no chat is opened), scrolls only downward, deduplicates across pages
 * (normalized name), stops at [limit] entries or at the end of the list. Pure logic. Gestures and the window check live in the device.
 *
 * Gap protection: a swipe can fling and skip rows. If a new page shares no row with the previous page,
 * it is discarded, one step is scrolled back, and from then on scrolling is page by page via a node action.
 */
class CheckupScanner(private val dev: CheckupDevice, private val maxPages: Int = 80, private val maxGapRetries: Int = 3) {
    suspend fun scan(limit: Int): CheckupScan {
        require(limit >= 1) { "limit" }
        dev.prepare()
        val seen = LinkedHashMap<String, ChatListEntry>()
        var prevKeys: Set<String> = emptySet()
        var emptyRounds = 0
        var precise = false
        var gaps = 0
        var gapRetries = 0
        var recovering = false
        var dupes = 0
        var archive = false
        var tabFails = 0
        var reason = StopReason.PAGE_LIMIT
        var attempts = 0
        var page = 0
        while (page < maxPages) {
            coroutineContext.ensureActive()
            if (!dev.chatsTabOk()) {
                if (++tabFails >= 2) throw AgentException("Checkup: Der Tab Chats liess sich nicht waehlen. Bitte WhatsApp auf den Tab Chats stellen und den Checkup neu starten.")
                dev.log("Checkup: Tab Chats nicht gewaehlt, neuer Versuch.")
                continue
            }
            tabFails = 0
            val rows = dev.readVisible()
            val keys = rows.map { NameMatcher.normalize(it.title) }.filter { it.isNotEmpty() }
            val fresh = keys.filter { it !in seen }
            val overlap = keys.any { it in prevKeys }
            // Gap: after a swipe, no shared row with the previous page, but new rows. Not on the first page and not when paging.
            if (overlap) recovering = false
            if (page > 0 && (!precise || recovering) && fresh.isNotEmpty() && !overlap && prevKeys.isNotEmpty()) {
                if (!recovering) gaps++
                recovering = true
                precise = true
                if (gapRetries < maxGapRetries) {
                    gapRetries++
                    dev.log("Checkup: Seite ${page + 1} ohne Ueberlappung zur Vorseite (${fresh.size} neue Zeilen). Moegliche Luecke, Seite verworfen, einen Schritt zurueck, ab jetzt seitenweise per Knotenaktion.")
                    dev.scrollBackward()
                    continue
                }
                dev.log("Checkup: Luecke nicht behebbar nach $maxGapRetries Versuchen, Seite wird trotzdem uebernommen (Reihenfolge kann Luecken haben).")
                recovering = false
            }
            var added = 0
            var dupHere = 0
            for ((i, row) in rows.withIndex()) {
                val key = NameMatcher.normalize(row.title)
                if (key.isEmpty()) continue
                if (row.archiveRow || ChatListSelector.isArchiveRow(row.title)) { archive = true; continue }
                if (key in seen) { dupHere++; continue }
                seen[key] = row.copy(order = seen.size)
                added++
                if (seen.size >= limit) break
            }
            dupes += dupHere
            page++
            dev.log("Checkup: Seite $page, ${rows.size} Zeilen, $added neu, $dupHere bekannt, gesamt ${seen.size} von $limit, Ueberlappung=$overlap, Modus ${if (precise) "Knotenaktion" else "Wischgeste"}.")
            if (seen.size >= limit) { reason = StopReason.LIMIT; break }
            emptyRounds = if (added == 0) emptyRounds + 1 else 0
            if (emptyRounds >= 2) { reason = StopReason.END; break }
            prevKeys = keys.toSet()
            when (dev.scrollStep(precise)) {
                ScrollTry.DONE -> attempts++
                ScrollTry.END_SIGNAL -> { attempts++; reason = StopReason.END; break }
                ScrollTry.NO_WAY -> {
                    dev.log("Checkup: Scrollen nicht moeglich nach $page Seiten (${seen.size} Zeilen). Das ist kein Listenende.")
                    reason = StopReason.SCROLL_FAILED
                    break
                }
            }
        }
        val entries = seen.values.take(limit).mapIndexed { i, e -> e.copy(order = i) }
        dev.log("Checkup: fertig nach $page Seiten, ${entries.size} Chats, Grund: ${reason.text}, Luecken $gaps, doppelte Zeilen $dupes.")
        entries.forEachIndexed { i, e ->
            dev.log("Checkup Nr. ${i + 1}: ${NameMask.short(e.title)}, Gruppe=${if (e.likelyGroup) "ja" else "nein"}, ungelesen=${if (e.unread) (if (e.unreadCount > 0) e.unreadCount.toString() else "ja") else "nein"}, angeheftet=${if (e.pinned) "ja" else "nein"}, Zeit=${e.timeText.ifEmpty { "?" }}")
        }
        return CheckupScan(entries, reason, page, gaps, dupes, archive, attempts)
    }
}

/** One entry in the selection menu. [selected] = chosen for setup, [isNew] = in the list, but not seen in any earlier checkup. */
data class CheckupItem(val entry: ChatListEntry, val selected: Boolean, val isNew: Boolean)

/** Locally stored selection: names only, no chat contents (no preview, no messages). */
data class CheckupStored(val savedAt: Long, val selected: List<String>, val known: List<String>, val order: List<String>)

object CheckupCodec {
    fun toJson(s: CheckupStored): String = JSONObject().apply {
        put("v", 1)
        put("savedAt", s.savedAt)
        put("selected", JSONArray(s.selected))
        put("known", JSONArray(s.known))
        put("order", JSONArray(s.order))
    }.toString()

    fun fromJson(json: String): CheckupStored {
        val o = JSONObject(json)
        fun list(n: String): List<String> {
            val a = o.optJSONArray(n) ?: return emptyList()
            return List(a.length()) { a.getString(it) }
        }
        return CheckupStored(o.optLong("savedAt", 0), list("selected"), list("known"), list("order"))
    }
}

/** Name matching as in 0.2.0: equal normalized text, otherwise the best hit from [MIN_PERCENT] percent, but only when it is unique. */
class NameIndex(names: Collection<String>) {
    private val list = names.toList()
    private val exact = list.associateBy { NameMatcher.normalize(it) }

    fun find(name: String): String? {
        exact[NameMatcher.normalize(name)]?.let { return it }
        var best: String? = null
        var bestP = 0
        var tie = false
        for (c in list) {
            val p = NameMatcher.similarity(name, c)
            if (p > bestP) { bestP = p; best = c; tie = false } else if (p == bestP && p > 0) tie = true
        }
        return if (best != null && bestP >= MIN_PERCENT && !tie) best else null
    }

    companion object {
        const val MIN_PERCENT = 92
    }
}

object CheckupSelection {
    private const val KNOWN_CAP = 1000

    /** Builds the menu entries: preselect the earlier selection, mark chats not seen before as new (only from the second checkup on). */
    fun reconcile(entries: List<ChatListEntry>, stored: CheckupStored?): List<CheckupItem> {
        val sel = NameIndex(stored?.selected.orEmpty())
        val known = NameIndex(stored?.known.orEmpty())
        val hadBefore = stored != null && stored.known.isNotEmpty()
        return entries.map { e ->
            CheckupItem(e, selected = sel.find(e.title) != null, isNew = hadBefore && known.find(e.title) == null)
        }
    }

    /** Quick pick: the top [n] chats (newest first, as setup did before) are checked, all others unchecked. */
    fun quickPick(items: List<CheckupItem>, n: Int, includeGroups: Boolean, pinnedCounts: Boolean, now: LocalDateTime): List<CheckupItem> {
        val chosen = ChatListSelector.select(items.map { it.entry }, n, includeGroups, if (pinnedCounts) PinnedMode.COUNT_NORMALLY else PinnedMode.EXCLUDE, now)
        val keys = chosen.map { NameMatcher.normalize(it.title) }.toSet()
        return items.map { it.copy(selected = NameMatcher.normalize(it.entry.title) in keys) }
    }

    fun setMany(items: List<CheckupItem>, titles: Set<String>, selected: Boolean): List<CheckupItem> =
        items.map { if (it.entry.title in titles) it.copy(selected = selected) else it }

    fun toggle(items: List<CheckupItem>, title: String): List<CheckupItem> =
        items.map { if (it.entry.title == title) it.copy(selected = !it.selected) else it }

    /** Search field: substring of the normalized name. An empty search shows everything. */
    fun filter(items: List<CheckupItem>, query: String): List<CheckupItem> {
        val q = NameMatcher.normalize(query)
        if (q.isEmpty()) return items
        return items.filter { NameMatcher.normalize(it.entry.title).contains(q) }
    }

    /** Selected names in list order. */
    fun selectedTitles(items: List<CheckupItem>): List<String> = items.filter { it.selected }.map { it.entry.title }

    /** To store: selected names, every name ever seen (capped), and the order. */
    fun toStored(items: List<CheckupItem>, prev: CheckupStored?, now: Long): CheckupStored {
        val names = items.map { it.entry.title }
        val known = LinkedHashSet<String>()
        val idx = NameIndex(prev?.known.orEmpty())
        prev?.known?.let { known.addAll(it) }
        names.forEach { if (idx.find(it) == null) known.add(it) }
        return CheckupStored(now, selectedTitles(items), known.toList().takeLast(KNOWN_CAP), names)
    }

    /** Menu from the stored data alone (before the first checkup of this session): names only, no preview. */
    fun fromStoredOnly(stored: CheckupStored): List<CheckupItem> {
        val sel = NameIndex(stored.selected)
        return stored.order.mapIndexed { i, n ->
            CheckupItem(ChatListEntry(n, "", "", false, false, false, i), selected = sel.find(n) != null, isNew = false)
        }
    }
}
