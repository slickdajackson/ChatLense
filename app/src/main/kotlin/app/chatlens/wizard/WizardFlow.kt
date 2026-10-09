package app.chatlens.wizard

/** Pages of the setup wizard, in order. */
enum class WizardStep(val number: Int) {
    WELCOME(1), A11Y(2), OVERLAY(3), MODEL(4), CHECKUP(5), DONE(6);

    companion object {
        const val COUNT = 6
        fun parse(s: String?): WizardStep = entries.firstOrNull { it.name == s } ?: WELCOME
    }
}

/** What the app currently knows about the device. Reread continuously (permissions change in the system settings). */
data class WizardFacts(val a11y: Boolean, val overlay: Boolean, val modelReady: Boolean, val checkupRows: Int)

/**
 * Wizard state. [consent] is the agreement on the first page (privacy). [waiting] means the user has just opened the system setting
 * and the app is waiting for success. [announced] means the notice "WhatsApp oeffnet sich" was shown (required before WhatsApp may start).
 */
data class WizardState(
    val step: WizardStep = WizardStep.WELCOME,
    val consent: Boolean = false,
    /** Separate, explicit consent for the accessibility service (disclosure on page 2, before the system dialog). */
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
    /** Skip an optional page (model, checkup). */
    object LaterStep : WizardAction()
    /** Leave the whole wizard (can be resumed later). */
    object Leave : WizardAction()
    object OpenA11y : WizardAction()
    object OpenOverlay : WizardAction()
    /** Show the notice before reading the chats. Does not open anything yet. */
    object AnnounceRead : WizardAction()
    object CancelAnnounce : WizardAction()
    /** Second, explicit tap after the notice: only now may WhatsApp be opened. */
    object ConfirmRead : WizardAction()
    object Finish : WizardAction()
    /** Facts have changed (permission granted). */
    object Observe : WizardAction()
}

/** Side effects the wizard requests. Without a user action there is never one. */
enum class WizardEffect { NONE, OPEN_A11Y_SETTINGS, OPEN_OVERLAY_SETTINGS, LAUNCH_CHECKUP_AND_WHATSAPP }

class WizardResult(val state: WizardState, val effect: WizardEffect = WizardEffect.NONE)

/** State machine of the wizard: pure logic without Android, so fully testable. */
object WizardFlow {
    fun canNext(s: WizardState, f: WizardFacts): Boolean = when (s.step) {
        WizardStep.WELCOME -> s.consent
        WizardStep.A11Y -> f.a11y
        WizardStep.OVERLAY -> f.overlay
        WizardStep.MODEL -> f.modelReady
        WizardStep.CHECKUP -> f.checkupRows > 0
        WizardStep.DONE -> false
    }

    /** "Später" exists only for the model and checkup pages (both can also be finished outside the wizard). */
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
            // After the system setting was opened: detect success and continue automatically
            val ok = when (s.step) { WizardStep.A11Y -> f.a11y; WizardStep.OVERLAY -> f.overlay; else -> false }
            if (s.waiting && ok) WizardResult(go(s, WizardStep.entries[s.step.ordinal + 1])) else WizardResult(s)
        }
    }
}

/** Rules for anything that could happen at app start without the user doing anything. */
object StartPolicy {
    /** The auto checkup at start runs only with a finished wizard, acknowledged privacy notice, and the switch turned on on purpose. */
    fun mayAutoCheckup(checkupOnStart: Boolean, wizardDone: Boolean, privacyAcknowledged: Boolean): Boolean =
        checkupOnStart && wizardDone && privacyAcknowledged

    /** Migration: through 0.2.8 the switch defaulted to on. On the first load from 0.2.9 it is set to off for everyone. */
    fun checkupOnStartLoaded(stored: Boolean, migrated: Boolean): Boolean = migrated && stored

    /** Migration: voice message transcription defaults to on from 0.2.9. A stored off through 0.2.8 was only the old default. */
    fun voiceLoaded(stored: Boolean, migrated: Boolean): Boolean = if (!migrated) true else stored
}
