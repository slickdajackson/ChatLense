package app.chatlens.agent

import app.chatlens.core.UiNode
import app.chatlens.parse.ChatListParser
import app.chatlens.profile.SelectorProfile

/** Erkennung der aktuellen WhatsApp-Ansicht fuer das reine Listenlesen. Reine Logik, in JVM-Tests pruefbar. */
object ListScreen {
    /**
     * Reihenfolge der Entscheidung:
     * 1. Editierbares Feld unten: CHAT (Nachrichtenfeld).
     * 2. Editierbares Feld oben UND (Eingabefokus oder sichtbare Tastatur): SEARCH_ACTIVE. Ein ruhendes Suchfeld der Chatliste
     *    (kein Fokus, keine Tastatur) zaehlt NICHT als aktive Suche (bis 0.2.2 war das der Fehler: Zurueck wurde auf der Liste gedrueckt).
     * 3. Mindestens ein sichtbarer Knoten mit Namens- oder Container-ID oder mindestens zwei lesbare Zeilen: LIST.
     * 4. Sonst OTHER (unbekannt; dann wird nie "Zurueck" gedrueckt und nichts angetippt).
     */
    fun classify(root: UiNode?, profile: SelectorProfile, imeVisible: Boolean): ScreenState {
        if (root == null) return ScreenState.NOT_WHATSAPP
        val h = root.bounds.b.coerceAtLeast(1)
        val editables = root.walk().filter { it.editable && it.visible }.toList()
        if (editables.any { it.bounds.centerY > h * profile.messageInputBottomFraction }) return ScreenState.CHAT
        val topEdits = editables.filter { it.bounds.centerY < h * profile.searchFieldTopFraction }
        if (topEdits.any { it.focused } || (topEdits.isNotEmpty() && imeVisible)) return ScreenState.SEARCH_ACTIVE
        val rows = ChatListParser.parse(root, titleIds = profile.chatListRowNameIds, containerIds = profile.chatListRowContainerIds)
        if (ChatListParser.markerCount(root, profile.chatListMarkerIds) >= 1 || rows.size >= 2) return ScreenState.LIST
        return ScreenState.OTHER
    }
}

/** Ergebnis der Tab-Leisten-Erkennung. */
class TabInfo(val found: Boolean, val chatsSelected: Boolean, val selectedOther: String?)

object TabBar {
    private fun matches(label: String?, labels: List<String>): String? {
        val l = label?.trim() ?: return null
        if (l.isEmpty() || l.length > 40) return null
        return labels.firstOrNull { l.equals(it, true) || ((l.startsWith("$it,", true) || l.startsWith("$it ", true)) && l.length <= it.length + 30) }
    }

    /** Sucht die untere Tab-Leiste (Knoten im unteren Bereich mit den Beschriftungen) und welcher Tab gewaehlt ist. */
    fun read(root: UiNode, profile: SelectorProfile): TabInfo {
        val h = root.bounds.b.coerceAtLeast(1)
        var chatsFound = false
        var chatsSel = false
        var otherFound = false
        var otherSel: String? = null
        fun rec(n: UiNode, anc: List<UiNode>) {
            if (n.visible && n.bounds.centerY > h * profile.tabBarTopFraction) {
                val lab = listOfNotNull(n.text, n.desc)
                val c = lab.firstNotNullOfOrNull { matches(it, profile.tabLabelsChats) }
                val o = lab.firstNotNullOfOrNull { matches(it, profile.tabLabelsOther) }
                if (c != null || o != null) {
                    val sel = n.selected || anc.takeLast(3).any { it.selected } || n.walk().any { it.selected }
                    if (c != null) { chatsFound = true; if (sel) chatsSel = true }
                    if (o != null) { otherFound = true; if (sel && otherSel == null) otherSel = o }
                }
            }
            for (k in n.children) rec(k, anc + n)
        }
        rec(root, emptyList())
        return TabInfo(chatsFound && otherFound, chatsSel, otherSel)
    }
}

/** Was [ChatListEnsurer] vom Geraet braucht. Auf dem Geraet vom Navigator, in Tests ein Fake. */
interface ListDevice {
    suspend fun ensureForeground()
    fun snapshot(): UiNode?
    fun imeVisible(): Boolean
    fun isForeground(): Boolean
    fun describe(): String
    fun back(): Boolean
    suspend fun pause(ms: Long)

    /** Schreibt den Debug-Baum (maskiert) und liefert den Dateinamen. */
    fun dumpTree(): String?
    fun nav(msg: String)

    /** Tippt den Tab Chats der unteren Leiste (per Knoten-Aktion, nicht per Koordinate). true, wenn die Aktion angenommen wurde. */
    fun openChatsTab(): Boolean = false
}

/**
 * Bringt WhatsApp zur Chatliste. Die Liste wird an Knoten-IDs erkannt, nicht am Zustand "OTHER". "Zurueck" wird nur gedrueckt,
 * wenn ein Chat oder eine aktive Suche sicher erkannt ist (hoechstens [MAX_BACKS] mal). Nach jedem Zurueck wird geprueft, ob
 * WhatsApp noch vorn ist (sonst zaehlt es als Verlassen; bei [MAX_LEAVES] Abbruch, davor holt [ListDevice.ensureForeground] die App
 * zurueck). In der Liste selbst und bei unbekannter Ansicht wird nie "Zurueck" gedrueckt und nie etwas angetippt.
 */
class ChatListEnsurer(private val dev: ListDevice, private val profile: SelectorProfile) {
    suspend fun run(log: (String) -> Unit) {
        var backs = 0
        var leaves = 0
        var unknown = 0
        var tabClicks = 0
        var listSeen = 0
        for (attempt in 1..MAX_ATTEMPTS) {
            dev.ensureForeground()
            val snap = dev.snapshot()
            val st = ListScreen.classify(snap, profile, dev.imeVisible())
            dev.nav("Chatliste: Versuch $attempt, Zustand $st, ${dev.describe()}, Markerknoten ${snap?.let { ChatListParser.markerCount(it, profile.chatListMarkerIds) } ?: 0}")
            if (snap != null) {
                val tabs = TabBar.read(snap, profile)
                if (tabs.found && !tabs.chatsSelected) {
                    dev.nav("TAB: Anderer Tab erkannt (${tabs.selectedOther ?: "unbekannt"} gewaehlt, Chats nicht), Zustand $st.")
                }
            }
            if (st != ScreenState.LIST) listSeen = 0
            when (st) {
                ScreenState.LIST -> {
                    // Zweimal hintereinander als Liste erkannt (Uebergangszustaende nach Zurueck ausschliessen)
                    if (++listSeen >= 2) return
                    dev.pause(450)
                }
                ScreenState.NOT_WHATSAPP, ScreenState.OTHER -> {
                    if (st == ScreenState.OTHER) {
                        // Zuerst pruefen, ob nur der falsche Tab (Aktuelles, Communities, Anrufe) offen ist: dann gezielt den Tab Chats waehlen
                        val tabs = snap?.let { TabBar.read(it, profile) }
                        if (tabs != null && tabs.found && !tabs.chatsSelected && tabClicks < MAX_TAB_CLICKS) {
                            tabClicks++
                            val ok = dev.openChatsTab()
                            dev.nav("TAB: Tab Chats gewaehlt (Versuch $tabClicks), Aktion angenommen=$ok.")
                            dev.pause(900)
                            continue
                        }
                        unknown++
                        // Ansicht evtl. noch im Aufbau: kurz warten und neu lesen. Nie Zurueck und nie Antippen.
                        if (unknown >= UNKNOWN_LIMIT) {
                            fail(backs, "Chatliste nicht erkannt (keine Knoten mit den IDs ${profile.chatListMarkerIds.joinToString(", ") { it.substringAfter('/') }}, kein Chat, keine aktive Suche).", snap)
                        }
                    }
                    dev.pause(900)
                }
                ScreenState.CHAT, ScreenState.SEARCH_ACTIVE -> {
                    if (backs >= MAX_BACKS) fail(backs, "Nach $backs Mal Zurueck ist die Chatliste nicht erreicht (Zustand $st).", snap)
                    log(if (st == ScreenState.CHAT) "In einem Chat: zurueck zur Chatliste." else "Suche ist aktiv: zurueck zur Chatliste.")
                    dev.back(); backs++
                    dev.pause(900)
                    if (!dev.isForeground()) dev.pause(800) // Fensterwechsel evtl. noch im Gange
                    if (!dev.isForeground()) {
                        leaves++
                        dev.nav("WhatsApp nach Zurueck verlassen (${dev.describe()}), Zaehler $leaves von $MAX_LEAVES.")
                        if (leaves >= MAX_LEAVES) {
                            throw NavigationException("WhatsApp wurde beim Zurueckgehen wiederholt verlassen ($leaves Mal). Abbruch, damit nichts in einem fremden Fenster passiert. Bitte WhatsApp auf der Chatliste oeffnen und neu starten.")
                        }
                    }
                }
            }
        }
        fail(backs, "Chatliste nicht erreicht.", null)
    }

    /** Schreibt den Debug-Baum (maskiert) in eine Datei und bricht mit klarer Meldung ab. Nichts wurde angetippt. */
    private fun fail(backs: Int, reason: String, snap: UiNode?): Nothing {
        val name = dev.dumpTree()
        val summary = snap?.let { s0 ->
            "Knoten ${s0.walk().count()}, scrollbar ${s0.walk().count { it.scrollable }}, editierbar ${s0.walk().count { it.editable }}"
        } ?: "kein Baum"
        dev.nav("Abbruch Chatliste: $reason ($summary)" + (name?.let { ", Baum gespeichert: $it" } ?: ", Baum NICHT gespeichert"))
        throw NavigationException(
            "$reason Es wurde nichts angetippt, Zurueck wurde $backs Mal gedrueckt (nur in erkanntem Chat oder aktiver Suche). WhatsApp auf der Chatliste oeffnen und neu starten." +
                (name?.let { " Debug-Baum (maskiert) gespeichert: $it (Tab Debug, dort exportieren)." } ?: ""),
        )
    }

    companion object {
        const val MAX_BACKS = 4
        const val MAX_LEAVES = 2
        const val MAX_ATTEMPTS = 12
        const val UNKNOWN_LIMIT = 3
        const val MAX_TAB_CLICKS = 2
    }
}
