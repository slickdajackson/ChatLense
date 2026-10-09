package app.chatlens.wizard

/** Seiten des Einrichtungsassistenten, in Reihenfolge. */
enum class WizardStep(val number: Int) {
    WELCOME(1), A11Y(2), OVERLAY(3), MODEL(4), CHECKUP(5), DONE(6);

    companion object {
        const val COUNT = 6
        fun parse(s: String?): WizardStep = entries.firstOrNull { it.name == s } ?: WELCOME
    }
}

/** Was die App gerade ueber das Geraet weiss. Wird laufend neu gelesen (Berechtigungen aendern sich in den Systemeinstellungen). */
data class WizardFacts(val a11y: Boolean, val overlay: Boolean, val modelReady: Boolean, val checkupRows: Int)

/**
 * Zustand des Assistenten. [consent] ist die Zustimmung auf der ersten Seite (Datenschutz), [waiting] heisst: der Nutzer hat gerade die Systemeinstellung
 * geoeffnet und die App wartet auf den Erfolg, [announced] heisst: die Ansage "WhatsApp oeffnet sich" wurde gezeigt (Voraussetzung fuer den Start von WhatsApp).
 */
data class WizardState(
    val step: WizardStep = WizardStep.WELCOME,
    val consent: Boolean = false,
    /** Eigene, ausdrueckliche Zustimmung zur Bedienungshilfe (Offenlegung auf Seite 2, vor dem Systemdialog). */
    val a11yConsent: Boolean = false,
    val waiting: Boolean = false,
    val announced: Boolean = false,
    val finished: Boolean = false,
    val skipped: Boolean = false,
)

sealed class WizardAction {
    data class Consent(val value: Boolean) : WizardAction()
    data class A11yConsent(val value: Boolean) : WizardAction()
    object Next : WizardAction()
    object Back : WizardAction()
    /** Eine optionale Seite (Modell, Checkup) ueberspringen. */
    object LaterStep : WizardAction()
    /** Den ganzen Assistenten verlassen (spaeter fortsetzbar). */
    object Leave : WizardAction()
    object OpenA11y : WizardAction()
    object OpenOverlay : WizardAction()
    /** Die Ansage vor dem Lesen der Chats zeigen. Oeffnet noch nichts. */
    object AnnounceRead : WizardAction()
    object CancelAnnounce : WizardAction()
    /** Zweiter, ausdruecklicher Tipp nach der Ansage: erst jetzt darf WhatsApp geoeffnet werden. */
    object ConfirmRead : WizardAction()
    object Finish : WizardAction()
    /** Fakten haben sich geaendert (Berechtigung erteilt). */
    object Observe : WizardAction()
}

/** Nebenwirkungen, die der Assistent erbittet. Ohne Aktion des Nutzers gibt es nie eine. */
enum class WizardEffect { NONE, OPEN_A11Y_SETTINGS, OPEN_OVERLAY_SETTINGS, LAUNCH_CHECKUP_AND_WHATSAPP }

class WizardResult(val state: WizardState, val effect: WizardEffect = WizardEffect.NONE)

/** Zustandsautomat des Assistenten: reine Logik ohne Android, deshalb voll testbar. */
object WizardFlow {
    fun canNext(s: WizardState, f: WizardFacts): Boolean = when (s.step) {
        WizardStep.WELCOME -> s.consent
        WizardStep.A11Y -> f.a11y
        WizardStep.OVERLAY -> f.overlay
        WizardStep.MODEL -> f.modelReady
        WizardStep.CHECKUP -> f.checkupRows > 0
        WizardStep.DONE -> false
    }

    /** "Spaeter" gibt es nur fuer Modell und Checkup (beide koennen auch ausserhalb des Assistenten erledigt werden). */
    fun canLater(s: WizardState): Boolean = s.step == WizardStep.MODEL || s.step == WizardStep.CHECKUP

    fun progressText(s: WizardState): String = "${s.step.number} von ${WizardStep.COUNT}"

    private fun go(s: WizardState, to: WizardStep) = s.copy(step = to, waiting = false, announced = false)

    fun reduce(s: WizardState, f: WizardFacts, a: WizardAction): WizardResult = when (a) {
        is WizardAction.Consent -> WizardResult(s.copy(consent = a.value))
        is WizardAction.A11yConsent -> WizardResult(s.copy(a11yConsent = a.value))
        WizardAction.Next -> {
            val i = s.step.ordinal
            if (canNext(s, f) && i < WizardStep.entries.size - 1) WizardResult(go(s, WizardStep.entries[i + 1])) else WizardResult(s)
        }
        WizardAction.LaterStep -> {
            val i = s.step.ordinal
            if (canLater(s)) WizardResult(go(s, WizardStep.entries[i + 1])) else WizardResult(s)
        }
        WizardAction.Back -> WizardResult(if (s.step.ordinal > 0) go(s, WizardStep.entries[s.step.ordinal - 1]) else s)
        WizardAction.Leave -> WizardResult(s.copy(skipped = true, waiting = false, announced = false))
        WizardAction.OpenA11y -> if (s.step == WizardStep.A11Y && s.a11yConsent) WizardResult(s.copy(waiting = true), WizardEffect.OPEN_A11Y_SETTINGS) else WizardResult(s)
        WizardAction.OpenOverlay -> if (s.step == WizardStep.OVERLAY) WizardResult(s.copy(waiting = true), WizardEffect.OPEN_OVERLAY_SETTINGS) else WizardResult(s)
        WizardAction.AnnounceRead -> if (s.step == WizardStep.CHECKUP && f.a11y) WizardResult(s.copy(announced = true)) else WizardResult(s)
        WizardAction.CancelAnnounce -> WizardResult(s.copy(announced = false))
        WizardAction.ConfirmRead ->
            if (s.step == WizardStep.CHECKUP && s.announced && f.a11y) WizardResult(s.copy(announced = false), WizardEffect.LAUNCH_CHECKUP_AND_WHATSAPP) else WizardResult(s)
        WizardAction.Finish -> if (s.step == WizardStep.DONE) WizardResult(s.copy(finished = true)) else WizardResult(s)
        WizardAction.Observe -> {
            // Nach dem Oeffnen der Systemeinstellung: Erfolg erkennen und automatisch weiter
            val ok = when (s.step) { WizardStep.A11Y -> f.a11y; WizardStep.OVERLAY -> f.overlay; else -> false }
            if (s.waiting && ok) WizardResult(go(s, WizardStep.entries[s.step.ordinal + 1])) else WizardResult(s)
        }
    }
}

/** Regeln fuer alles, was ohne Zutun des Nutzers beim Start der App passieren koennte. */
object StartPolicy {
    /** Der Auto-Checkup beim Start laeuft nur mit abgeschlossenem Assistenten, bestaetigtem Datenschutz und bewusst eingeschaltetem Schalter. */
    fun mayAutoCheckup(checkupOnStart: Boolean, wizardDone: Boolean, privacyAcknowledged: Boolean): Boolean =
        checkupOnStart && wizardDone && privacyAcknowledged

    /** Migration: bis 0.2.8 war der Schalter standardmaessig an. Beim ersten Laden ab 0.2.9 wird er fuer alle auf aus gesetzt. */
    fun checkupOnStartLoaded(stored: Boolean, migrated: Boolean): Boolean = migrated && stored

    /** Migration: Sprachnachrichten-Transkription ist ab 0.2.9 standardmaessig an; bis 0.2.8 gespeichertes "aus" war nur der alte Standard. */
    fun voiceLoaded(stored: Boolean, migrated: Boolean): Boolean = if (!migrated) true else stored
}
