package app.chatlens.agent

/**
 * Setup order: 1. permissions, 2. checkup (chat list, default top 50), 3. selection, 4. setup.
 * Setup, self-analysis, and other multi-chat tasks become usable only once the checkup list exists. Pure logic.
 */
enum class WfStep(val number: Int, val title: String) {
    PERMISSIONS(1, "Berechtigungen"),
    CHECKUP(2, "Checkup der obersten 50 Chats"),
    SELECTION(3, "Chats auswählen"),
    SETUP(4, "Setup"),
    DONE(5, "Fertig eingerichtet"),
}

class WfState(val step: WfStep, val checkupAvailable: Boolean, val selected: Int, val permissionsOk: Boolean)

object Workflow {
    /** [checkupItems]: number of chats in the checkup list (scanned or stored). [selected]: checked chats. [profiles]: memory profiles that have been created. */
    fun state(a11y: Boolean, privacyOk: Boolean, hasBackend: Boolean, checkupItems: Int, selected: Int, profiles: Int): WfState {
        val perm = a11y && privacyOk && hasBackend
        val step = when {
            !perm -> WfStep.PERMISSIONS
            checkupItems == 0 -> WfStep.CHECKUP
            selected == 0 -> WfStep.SELECTION
            profiles == 0 -> WfStep.SETUP
            else -> WfStep.DONE
        }
        return WfState(step, checkupItems > 0, selected, perm)
    }

    /** Multi-chat tasks (setup, self-analysis, auto) need the checkup list. */
    fun multiChatUnlocked(checkupItems: Int) = checkupItems > 0

    /** Reason for the lock, or null. [selected] is required only for setup and self-analysis. */
    fun lockReason(task: String, checkupItems: Int, selected: Int, needsSelection: Boolean = true): String? = when {
        checkupItems == 0 -> "Zuerst den Checkup ausführen: Er liest die Chatliste ein, aus der $task die Chats wählt."
        needsSelection && selected == 0 -> "Im Checkup mindestens einen Chat ankreuzen, $task arbeitet nur die angekreuzten Chats ab."
        else -> null
    }

    /** Only titles that appear in the checkup list (compared by normalized spelling), without duplicates, in the given order. */
    fun fromCheckup(wanted: List<String>, checkupTitles: List<String>): List<String> {
        val allowed = checkupTitles.associateBy { app.chatlens.match.NameMatcher.normalize(it) }
        return wanted.mapNotNull { allowed[app.chatlens.match.NameMatcher.normalize(it.trim())] }.distinct()
    }

    fun stepsText(w: WfState): List<Pair<WfStep, Boolean>> = listOf(WfStep.PERMISSIONS, WfStep.CHECKUP, WfStep.SELECTION, WfStep.SETUP).map { it to (it.number < w.step.number) }
}
