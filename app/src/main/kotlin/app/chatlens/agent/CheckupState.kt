package app.chatlens.agent

import app.chatlens.checkup.CheckupItem
import app.chatlens.checkup.CheckupSelection
import app.chatlens.checkup.CheckupStored
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime

data class CheckupUiState(
    val items: List<CheckupItem> = emptyList(),
    /** Zeitpunkt des letzten Scans oder des gespeicherten Stands (Millisekunden), 0 = keiner. */
    val lastAt: Long = 0,
    /** true: Menue stammt nur aus dem gespeicherten Stand (Namen, keine Vorschau), noch kein Scan in dieser Sitzung. */
    val storedOnly: Boolean = false,
    val note: String = "",
)

/**
 * Zustand des Auswahlmenues. Aenderungen der Haken werden ueber [saver] sofort verschluesselt gespeichert (nur Namen).
 * Der Scan selbst laeuft im AutoRunner (Auftrag Checkup).
 */
object CheckupState {
    private val _state = MutableStateFlow(CheckupUiState())
    val state: StateFlow<CheckupUiState> = _state

    @Volatile
    var stored: CheckupStored? = null
        private set

    /** Wird vom Start der App gesetzt: speichert asynchron. */
    @Volatile
    var saver: ((CheckupStored) -> Unit)? = null

    /** Liste leeren (Tests und "Alles loeschen"): danach sind Setup und Selbstanalyse wieder gesperrt. */
    fun reset() { stored = null; _state.value = CheckupUiState() }

    fun restore(s: CheckupStored?) {
        stored = s
        if (s != null && _state.value.items.isEmpty()) {
            _state.value = CheckupUiState(CheckupSelection.fromStoredOnly(s), s.savedAt, storedOnly = true, note = "Gespeicherter Stand vom letzten Checkup (nur Namen).")
        }
    }

    /** Ergebnis eines Scans uebernehmen: fruehere Auswahl vorwaehlen, Neue markieren, speichern. */
    fun applyScan(items: List<CheckupItem>, now: Long, note: String) {
        _state.value = CheckupUiState(items, now, storedOnly = false, note = note)
        persist(items, now)
    }

    private fun persist(items: List<CheckupItem>, now: Long) {
        val st = CheckupSelection.toStored(items, stored, now)
        stored = st
        saver?.invoke(st)
    }

    private fun change(f: (List<CheckupItem>) -> List<CheckupItem>) {
        val cur = _state.value
        val next = f(cur.items)
        _state.value = cur.copy(items = next)
        persist(next, cur.lastAt.takeIf { it > 0 } ?: System.currentTimeMillis())
    }

    fun toggle(title: String) = change { CheckupSelection.toggle(it, title) }
    fun setMany(titles: Set<String>, selected: Boolean) = change { CheckupSelection.setMany(it, titles, selected) }
    fun quick(n: Int, includeGroups: Boolean, pinnedCounts: Boolean) = change { CheckupSelection.quickPick(it, n, includeGroups, pinnedCounts, LocalDateTime.now()) }

    fun selectedTitles(): List<String> = CheckupSelection.selectedTitles(_state.value.items)

    /** Kurzfassung ohne volle Namen fuer das Markdown-Log. */
    fun summary(): String {
        val it = _state.value
        if (it.items.isEmpty()) return ""
        val sel = it.items.count { x -> x.selected }
        return "Checkup-Liste: ${it.items.size} Chats, $sel gewaehlt, ${it.items.count { x -> x.isNew }} neu, ${it.items.count { x -> x.entry.likelyGroup }} Gruppen, " +
            "${it.items.count { x -> x.entry.unread }} mit Ungelesen-Hinweis" + if (it.storedOnly) " (nur gespeicherter Stand)" else ""
    }
}
