package app.chatlens.service

/**
 * Rule for "Entfernen" in the floating dot's menu. If a job is running, the first tap only warns (a toast) and
 * remembers the time; a second tap within the window cancels the job cleanly and removes the dot.
 * With no job running, it is removed immediately. Pure logic with no Android, so it is testable.
 */
object OverlayRemoval {
    const val CONFIRM_WINDOW_MS = 6_000L

    enum class Step { REMOVE_NOW, WARN_FIRST, CANCEL_AND_REMOVE }

    const val MSG_REMOVED = "Punkt entfernt. Wieder einschalten in den Einstellungen."
    const val MSG_WARN = "Es läuft ein Auftrag. Noch einmal Entfernen tippen bricht ihn ab und entfernt den Punkt."
    const val MSG_CANCELLED = "Auftrag abgebrochen. Punkt entfernt. Wieder einschalten in den Einstellungen."

    /** [armedAt] is the time of the last warning (0 means none). */
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

/** Message from the service to the open app, so the switch in settings shows "aus" immediately. */
object OverlayEvents {
    val removed = kotlinx.coroutines.flow.MutableStateFlow(0)
}
