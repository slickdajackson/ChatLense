package app.chatlens.service

/**
 * Regel fuer "Entfernen" im Menue des schwebenden Punktes. Laeuft gerade ein Auftrag, warnt der erste Tipp nur (Toast) und
 * merkt sich den Zeitpunkt; ein zweiter Tipp innerhalb des Fensters bricht den Auftrag sauber ab und entfernt den Punkt.
 * Ohne laufenden Auftrag wird sofort entfernt. Reine Logik ohne Android, deshalb testbar.
 */
object OverlayRemoval {
    const val CONFIRM_WINDOW_MS = 6_000L

    enum class Step { REMOVE_NOW, WARN_FIRST, CANCEL_AND_REMOVE }

    const val MSG_REMOVED = "Punkt entfernt. Wieder einschalten in den Einstellungen."
    const val MSG_WARN = "Es läuft ein Auftrag. Noch einmal Entfernen tippen bricht ihn ab und entfernt den Punkt."
    const val MSG_CANCELLED = "Auftrag abgebrochen. Punkt entfernt. Wieder einschalten in den Einstellungen."

    /** [armedAt] ist der Zeitpunkt der letzten Warnung (0 = keine). */
    fun decide(running: Boolean, armedAt: Long, now: Long): Step = when {
        !running -> Step.REMOVE_NOW
        armedAt > 0 && now - armedAt in 0..CONFIRM_WINDOW_MS -> Step.CANCEL_AND_REMOVE
        else -> Step.WARN_FIRST
    }

    fun message(step: Step): String = when (step) {
        Step.REMOVE_NOW -> MSG_REMOVED
        Step.WARN_FIRST -> MSG_WARN
        Step.CANCEL_AND_REMOVE -> MSG_CANCELLED
    }
}

/** Meldung des Dienstes an die offene App, damit der Schalter in den Einstellungen sofort "aus" zeigt. */
object OverlayEvents {
    val removed = kotlinx.coroutines.flow.MutableStateFlow(0)
}
