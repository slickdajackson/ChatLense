package app.chatlens.agent

import app.chatlens.checkup.CheckupItem
import app.chatlens.checkup.CheckupSelection
import app.chatlens.checkup.CheckupStored
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime

data class CheckupUiState(
    val items: List<CheckupItem> = emptyList(),
    /** Time of the last scan or of the stored snapshot (milliseconds), 0 means none. */
    val lastAt: Long = 0,
    /** true: the menu comes only from the stored snapshot (names, no preview), and there has been no scan in this session yet. */
    val storedOnly: Boolean = false,
    val note: String = "",
)

/**
 * State of the selection menu. Changes to the checkmarks are saved immediately through [saver], encrypted (names only).
 * The scan itself runs in AutoRunner (checkup job).
 */
object CheckupState {
    private val _state = MutableStateFlow(CheckupUiState())
    val state: StateFlow<CheckupUiState> = _state

    @Volatile
    var stored: CheckupStored? = null
        private set

    /** Set at app start: saves asynchronously. */
    @Volatile
    var saver: ((CheckupStored) -> Unit)? = null

    /** Clears the list (tests and "Alles loeschen"): after that, setup and self-analysis are locked again. */
    fun reset() { stored = null; _state.value = CheckupUiState() }

    fun restore(s: CheckupStored?) {
        stored = s
        if (s != null && _state.value.items.isEmpty()) {
            _state.value = CheckupUiState(CheckupSelection.fromStoredOnly(s), s.savedAt, storedOnly = true, note = "Gespeicherter Stand vom letzten Checkup (nur Namen).")
        }
    }

    /** Applies a scan result: preselect the previous selection, mark new items, and save. */
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

    /** Short summary without full names, for the markdown log. */
    fun summary(): String {
        val it = _state.value
        if (it.items.isEmpty()) return ""
        val sel = it.items.count { x -> x.selected }
        return "Checkup-Liste: ${it.items.size} Chats, $sel gewaehlt, ${it.items.count { x -> x.isNew }} neu, ${it.items.count { x -> x.entry.likelyGroup }} Gruppen, " +
            "${it.items.count { x -> x.entry.unread }} mit Ungelesen-Hinweis" + if (it.storedOnly) " (nur gespeicherter Stand)" else ""
    }
}
