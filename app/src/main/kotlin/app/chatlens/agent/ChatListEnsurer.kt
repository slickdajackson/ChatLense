package app.chatlens.agent

import app.chatlens.core.UiNode
import app.chatlens.parse.ChatListParser
import app.chatlens.profile.SelectorProfile

/** Recognizes the current WhatsApp screen for list-only reading. Pure logic, checkable in JVM tests. */
object ListScreen {
    /**
     * Decision order:
     * 1. Editable field at the bottom: CHAT (message field).
     * 2. Editable field at the top AND (input focus or a visible keyboard): SEARCH_ACTIVE. An idle search field on the chat list
     *    (no focus, no keyboard) does NOT count as an active search (until 0.2.2 this was the bug: "Zurueck" was pressed on the list).
     * 3. At least one visible node with a name or container id, or at least two readable rows: LIST.
     * 4. Otherwise OTHER (unknown; then "Zurueck" is never pressed and nothing is tapped).
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

/** Result of tab bar recognition. */
class TabInfo(val found: Boolean, val chatsSelected: Boolean, val selectedOther: String?)

object TabBar {
    private fun matches(label: String?, labels: List<String>): String? {
        val l = label?.trim() ?: return null
        if (l.isEmpty() || l.length > 40) return null
        return labels.firstOrNull { l.equals(it, true) || ((l.startsWith("$it,", true) || l.startsWith("$it ", true)) && l.length <= it.length + 30) }
    }

    /** Finds the bottom tab bar (nodes in the lower area that carry the labels) and which tab is selected. */
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

/** What [ChatListEnsurer] needs from the device. On the device this comes from the navigator, in tests a fake. */
interface ListDevice {
    suspend fun ensureForeground()
    fun snapshot(): UiNode?
    fun imeVisible(): Boolean
    fun isForeground(): Boolean
    fun describe(): String
    fun back(): Boolean
    suspend fun pause(ms: Long)

    /** Writes the debug tree (masked) and returns the file name. */
    fun dumpTree(): String?
    fun nav(msg: String)

    /** Taps the Chats tab of the bottom bar (by node action, not by coordinate). true when the action was accepted. */
    fun openChatsTab(): Boolean = false
}

/**
 * Brings WhatsApp to the chat list. The list is recognized by node ids, not by the OTHER state. "Zurueck" is pressed only
 * when a chat or an active search is recognized with confidence (at most [MAX_BACKS] times). After each back press, the code checks whether
 * WhatsApp is still in front (otherwise it counts as leaving; at [MAX_LEAVES] the run aborts, and before that [ListDevice.ensureForeground] brings the app
 * back). On the list itself and on an unknown screen, "Zurueck" is never pressed and nothing is ever tapped.
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
                    // Recognized as the list twice in a row (exclude transitional states after back)
                    if (++listSeen >= 2) return
                    dev.pause(450)
                }
                ScreenState.NOT_WHATSAPP, ScreenState.OTHER -> {
                    if (st == ScreenState.OTHER) {
                        // First check whether only the wrong tab (Aktuelles, Communities, Anrufe) is open: then select the Chats tab specifically
                        val tabs = snap?.let { TabBar.read(it, profile) }
                        if (tabs != null && tabs.found && !tabs.chatsSelected && tabClicks < MAX_TAB_CLICKS) {
                            tabClicks++
                            val ok = dev.openChatsTab()
                            dev.nav("TAB: Tab Chats gewaehlt (Versuch $tabClicks), Aktion angenommen=$ok.")
                            dev.pause(900)
                            continue
                        }
                        unknown++
                        // The screen may still be building: wait briefly and read again. Never press back and never tap.
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
                    if (!dev.isForeground()) dev.pause(800) // a window change may still be in progress
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

    /** Writes the debug tree (masked) to a file and aborts with a clear message. Nothing was tapped. */
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
