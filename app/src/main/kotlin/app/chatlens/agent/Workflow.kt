package app.chatlens.agent

/**
 * Reihenfolge der Einrichtung: 1. Berechtigungen, 2. Checkup (Chatliste, Standard oberste 50), 3. Auswahl, 4. Setup.
 * Setup, Selbstanalyse und andere Mehr-Chat-Aufgaben sind erst nutzbar, wenn die Checkup-Liste vorliegt. Reine Logik.
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
    /** [checkupItems]: Zahl der Chats in der Checkup-Liste (gescannt oder gespeichert). [selected]: angekreuzte Chats. [profiles]: angelegte Gedaechtnisprofile. */
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

    /** Mehr-Chat-Aufgaben (Setup, Selbstanalyse, Auto) brauchen die Checkup-Liste. */
    fun multiChatUnlocked(checkupItems: Int) = checkupItems > 0

    /** Grund der Sperre oder null. [selected] wird nur fuer Setup und Selbstanalyse verlangt. */
    fun lockReason(task: String, checkupItems: Int, selected: Int, needsSelection: Boolean = true): String? = when {
        checkupItems == 0 -> "Zuerst den Checkup ausführen: Er liest die Chatliste ein, aus der $task die Chats wählt."
        needsSelection && selected == 0 -> "Im Checkup mindestens einen Chat ankreuzen, $task arbeitet nur die angekreuzten Chats ab."
        else -> null
    }

    /** Nur Titel, die in der Checkup-Liste stehen (Vergleich ueber die normalisierte Schreibweise), ohne Doppelte, in der uebergebenen Reihenfolge. */
    fun fromCheckup(wanted: List<String>, checkupTitles: List<String>): List<String> {
        val allowed = checkupTitles.associateBy { app.chatlens.match.NameMatcher.normalize(it) }
        return wanted.mapNotNull { allowed[app.chatlens.match.NameMatcher.normalize(it.trim())] }.distinct()
    }

    fun stepsText(w: WfState): List<Pair<WfStep, Boolean>> = listOf(WfStep.PERMISSIONS, WfStep.CHECKUP, WfStep.SELECTION, WfStep.SETUP).map { it to (it.number < w.step.number) }
}
