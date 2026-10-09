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

/** Warum der Checkup-Scan endete. */
enum class StopReason(val text: String) {
    LIMIT("gewuenschte Anzahl erreicht"),
    END("Listenende erreicht oder kein weiteres Scrollen moeglich"),
    PAGE_LIMIT("Seitengrenze erreicht"),
    SCROLL_FAILED("Scrollen nicht moeglich"),
}

/** Ergebnis eines Scrollschritts. DONE: Geste oder Aktion wurde ausgefuehrt. END_SIGNAL: Android lehnt das Weiterscrollen ab (Ende). NO_WAY: es gab keinen Weg zu scrollen (keine Liste, Geste verweigert, keine Aktion). */
enum class ScrollTry { DONE, END_SIGNAL, NO_WAY }

class CheckupScan(
    val entries: List<ChatListEntry>,
    val reason: StopReason,
    val pages: Int,
    val gaps: Int,
    val duplicates: Int,
    val archiveSeen: Boolean,
    /** Zahl der Scrollversuche, die ausgefuehrt wurden (Geste oder Knotenaktion). */
    val scrollAttempts: Int = 0,
) {
    /** Das Listenende gilt nur als erwiesen, wenn gescrollt wurde und der Inhalt danach gleich blieb (oder Android das Ende meldet). */
    val endProven: Boolean get() = reason == StopReason.LIMIT || (reason == StopReason.END && scrollAttempts >= 1)

    /** Warnung fuer die Oberflaeche: Scrollen unmoeglich, oder weniger als [MIN_PLAUSIBLE] Zeilen ohne belegtes Ende. */
    fun warning(limit: Int): String? = when {
        reason == StopReason.SCROLL_FAILED -> "Scrollen nicht moeglich: nur ${entries.size} Zeilen von der ersten Seite gelesen. Die Liste ist wahrscheinlich laenger. Debug-Baum der Chatliste exportieren und den Checkup wiederholen."
        entries.size < MIN_PLAUSIBLE && entries.size < limit && !endProven -> "Nur ${entries.size} Zeilen gelesen und das Listenende ist nicht erwiesen (kein Scrollversuch mit unveraendertem Inhalt). Bitte pruefen."
        entries.size < MIN_PLAUSIBLE && entries.size < limit -> "Nur ${entries.size} Zeilen gelesen; das Listenende wurde nach $scrollAttempts Scrollversuchen ohne neue Zeilen gemeldet. Falls WhatsApp mehr Chats hat, bitte den Checkup wiederholen."
        else -> null
    }

    companion object { const val MIN_PLAUSIBLE = 15 }
}

/** Was der Scan vom Geraet braucht. Auf dem Geraet der Navigator, in Tests ein Fake. */
interface CheckupDevice {
    /** Waechter, Chatliste erreichen (Tab Chats), nach oben scrollen. */
    suspend fun prepare()

    /** Sichtbare Zeilen der Chatliste jetzt. */
    suspend fun readVisible(): List<ChatListEntry>

    /** Ein Schritt abwaerts. [precise]: seitenweise per Knotenaktion statt Wischgeste. false: nicht moeglich. */
    suspend fun scrollForward(precise: Boolean): Boolean

    /** Wie [scrollForward], aber mit Unterscheidung zwischen "Ende gemeldet" und "es gibt keinen Weg zu scrollen". Standard: false heisst Ende. */
    suspend fun scrollStep(precise: Boolean): ScrollTry = if (scrollForward(precise)) ScrollTry.DONE else ScrollTry.END_SIGNAL

    /** Ein Schritt aufwaerts (nach erkannter Luecke). */
    suspend fun scrollBackward(): Boolean

    /** Ist der Tab Chats gewaehlt (nach Korrekturversuch)? true auch, wenn die Leiste nicht erkannt wird. */
    suspend fun chatsTabOk(): Boolean
    fun log(msg: String)
}

/** Namen im Log verkuerzen: zwei Zeichen und Laenge, damit das Log keine vollen Namen traegt. */
object NameMask {
    fun short(name: String): String {
        val t = name.trim()
        if (t.isEmpty()) return "(leer)"
        return t.take(2) + "*** (${t.length} Zeichen)"
    }
}

/**
 * Checkup-Scan der Chatliste: liest nur Zeilen (kein Chat wird geoeffnet), scrollt nur abwaerts, dedupliziert ueber die Seiten
 * (normalisierter Name), stoppt bei [limit] Eintraegen oder am Listenende. Reine Logik; Gesten und Fensterpruefung liegen im Geraet.
 *
 * Luecken-Schutz: Beim Wischen kann ein Nachschwung Zeilen ueberspringen. Hat eine neue Seite keine einzige Zeile mit der Vorseite
 * gemeinsam, wird sie verworfen, einen Schritt zurueckgescrollt und ab dann seitenweise per Knotenaktion gescrollt.
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
            // Luecke: nach einem Wisch keine gemeinsame Zeile mit der Vorseite, aber neue Zeilen. Nicht bei der ersten Seite und nicht seitenweise.
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

/** Ein Eintrag im Auswahlmenue. [selected] = fuers Setup gewaehlt, [isNew] = in der Liste, aber bei keinem frueheren Checkup gesehen. */
data class CheckupItem(val entry: ChatListEntry, val selected: Boolean, val isNew: Boolean)

/** Lokal gespeicherte Auswahl: nur Namen, keine Chatinhalte (keine Vorschau, keine Nachrichten). */
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

/** Namensabgleich wie in 0.2.0: normalisierter Text gleich, sonst bester Treffer ab [MIN_PERCENT] Prozent, aber nur wenn eindeutig. */
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

    /** Baut die Menueeintraege: fruehere Auswahl vorauswaehlen, bisher unbekannte Chats als neu markieren (erst ab dem zweiten Checkup). */
    fun reconcile(entries: List<ChatListEntry>, stored: CheckupStored?): List<CheckupItem> {
        val sel = NameIndex(stored?.selected.orEmpty())
        val known = NameIndex(stored?.known.orEmpty())
        val hadBefore = stored != null && stored.known.isNotEmpty()
        return entries.map { e ->
            CheckupItem(e, selected = sel.find(e.title) != null, isNew = hadBefore && known.find(e.title) == null)
        }
    }

    /** Schnellwahl: die obersten [n] Chats (neueste zuerst, wie das Setup bisher) werden angehakt, alle anderen abgehakt. */
    fun quickPick(items: List<CheckupItem>, n: Int, includeGroups: Boolean, pinnedCounts: Boolean, now: LocalDateTime): List<CheckupItem> {
        val chosen = ChatListSelector.select(items.map { it.entry }, n, includeGroups, if (pinnedCounts) PinnedMode.COUNT_NORMALLY else PinnedMode.EXCLUDE, now)
        val keys = chosen.map { NameMatcher.normalize(it.title) }.toSet()
        return items.map { it.copy(selected = NameMatcher.normalize(it.entry.title) in keys) }
    }

    fun setMany(items: List<CheckupItem>, titles: Set<String>, selected: Boolean): List<CheckupItem> =
        items.map { if (it.entry.title in titles) it.copy(selected = selected) else it }

    fun toggle(items: List<CheckupItem>, title: String): List<CheckupItem> =
        items.map { if (it.entry.title == title) it.copy(selected = !it.selected) else it }

    /** Suchfeld: Teilstring im normalisierten Namen. Leere Suche zeigt alles. */
    fun filter(items: List<CheckupItem>, query: String): List<CheckupItem> {
        val q = NameMatcher.normalize(query)
        if (q.isEmpty()) return items
        return items.filter { NameMatcher.normalize(it.entry.title).contains(q) }
    }

    /** Gewaehlte Namen in Listenreihenfolge. */
    fun selectedTitles(items: List<CheckupItem>): List<String> = items.filter { it.selected }.map { it.entry.title }

    /** Zu speichern: gewaehlte Namen, alle je gesehenen Namen (begrenzt) und die Reihenfolge. */
    fun toStored(items: List<CheckupItem>, prev: CheckupStored?, now: Long): CheckupStored {
        val names = items.map { it.entry.title }
        val known = LinkedHashSet<String>()
        val idx = NameIndex(prev?.known.orEmpty())
        prev?.known?.let { known.addAll(it) }
        names.forEach { if (idx.find(it) == null) known.add(it) }
        return CheckupStored(now, selectedTitles(items), known.toList().takeLast(KNOWN_CAP), names)
    }

    /** Menue aus dem Gespeicherten allein (vor dem ersten Checkup dieser Sitzung): nur Namen, keine Vorschau. */
    fun fromStoredOnly(stored: CheckupStored): List<CheckupItem> {
        val sel = NameIndex(stored.selected)
        return stored.order.mapIndexed { i, n ->
            CheckupItem(ChatListEntry(n, "", "", false, false, false, i), selected = sel.find(n) != null, isNew = false)
        }
    }
}
