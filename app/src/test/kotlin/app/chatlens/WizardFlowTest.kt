package app.chatlens

import app.chatlens.wizard.WizardAction
import app.chatlens.wizard.WizardEffect
import app.chatlens.wizard.WizardFacts
import app.chatlens.wizard.WizardFlow
import app.chatlens.wizard.WizardState
import app.chatlens.wizard.WizardStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WizardFlowTest {
    private val none = WizardFacts(a11y = false, overlay = false, modelReady = false, checkupRows = 0)
    private val all = WizardFacts(a11y = true, overlay = true, modelReady = true, checkupRows = 20)

    private fun step(s: WizardState, f: WizardFacts, a: WizardAction) = WizardFlow.reduce(s, f, a)

    @Test fun startsAtWelcomeAndNeedsConsent() {
        val s = WizardState()
        assertEquals(WizardStep.WELCOME, s.step)
        assertFalse(WizardFlow.canNext(s, all))
        assertEquals(WizardStep.WELCOME, step(s, all, WizardAction.Next).state.step)
        val c = step(s, all, WizardAction.Consent(true)).state
        assertTrue(WizardFlow.canNext(c, all))
        assertEquals(WizardStep.A11Y, step(c, all, WizardAction.Next).state.step)
        assertFalse(step(c, all, WizardAction.Consent(false)).state.consent)
    }

    @Test fun progressTextIsKOfSix() {
        assertEquals("1 von 6", WizardFlow.progressText(WizardState()))
        assertEquals("6 von 6", WizardFlow.progressText(WizardState(step = WizardStep.DONE)))
    }

    @Test fun nextIsBlockedUntilEachStepIsDone() {
        assertFalse(WizardFlow.canNext(WizardState(step = WizardStep.A11Y), none))
        assertFalse(WizardFlow.canNext(WizardState(step = WizardStep.OVERLAY), none.copy(a11y = true)))
        assertFalse(WizardFlow.canNext(WizardState(step = WizardStep.MODEL), none))
        assertFalse(WizardFlow.canNext(WizardState(step = WizardStep.CHECKUP), none))
        assertTrue(WizardFlow.canNext(WizardState(step = WizardStep.CHECKUP), all))
        assertFalse(WizardFlow.canNext(WizardState(step = WizardStep.DONE), all))
    }

    @Test fun openingSystemSettingsIsAnEffectOnlyOnItsOwnPage() {
        val a = step(WizardState(step = WizardStep.A11Y, a11yConsent = true), none, WizardAction.OpenA11y)
        assertEquals(WizardEffect.OPEN_A11Y_SETTINGS, a.effect)
        assertTrue(a.state.waiting)
        assertEquals(WizardEffect.NONE, step(WizardState(step = WizardStep.WELCOME), none, WizardAction.OpenA11y).effect)
        assertEquals(WizardEffect.OPEN_OVERLAY_SETTINGS, step(WizardState(step = WizardStep.OVERLAY), none, WizardAction.OpenOverlay).effect)
        assertEquals(WizardEffect.NONE, step(WizardState(step = WizardStep.A11Y), none, WizardAction.OpenOverlay).effect)
    }

    @Test fun successIsDetectedAutomaticallyAndJumpsOn() {
        var s = step(WizardState(step = WizardStep.A11Y, a11yConsent = true), none, WizardAction.OpenA11y).state
        s = step(s, none, WizardAction.Observe).state
        assertEquals("ohne Erfolg bleibt es", WizardStep.A11Y, s.step)
        s = step(s, none.copy(a11y = true), WizardAction.Observe).state
        assertEquals(WizardStep.OVERLAY, s.step)
        assertFalse(s.waiting)
        s = step(s, none.copy(a11y = true), WizardAction.OpenOverlay).state
        s = step(s, none.copy(a11y = true, overlay = true), WizardAction.Observe).state
        assertEquals(WizardStep.MODEL, s.step)
    }

    @Test fun alreadyGrantedPermissionDoesNotJumpWithoutTap() {
        val s = step(WizardState(step = WizardStep.A11Y), all, WizardAction.Observe).state
        assertEquals("ohne Tipp kein Springen, Weiter ist moeglich", WizardStep.A11Y, s.step)
        assertTrue(WizardFlow.canNext(s, all))
    }

    @Test fun whatsAppOnlyAfterAnnouncementAndSecondTap() {
        val s0 = WizardState(step = WizardStep.CHECKUP)
        // confirm directly without the announcement: nothing happens
        assertEquals(WizardEffect.NONE, step(s0, all, WizardAction.ConfirmRead).effect)
        // show the announcement: still no effect
        val a = step(s0, all, WizardAction.AnnounceRead)
        assertEquals(WizardEffect.NONE, a.effect)
        assertTrue(a.state.announced)
        // the announcement is not possible without accessibility
        assertFalse(step(s0, none, WizardAction.AnnounceRead).state.announced)
        // cancel withdraws the announcement
        assertFalse(step(a.state, all, WizardAction.CancelAnnounce).state.announced)
        assertEquals(WizardEffect.NONE, step(step(a.state, all, WizardAction.CancelAnnounce).state, all, WizardAction.ConfirmRead).effect)
        // second tap: now, and only now, start
        val go = step(a.state, all, WizardAction.ConfirmRead)
        assertEquals(WizardEffect.LAUNCH_CHECKUP_AND_WHATSAPP, go.effect)
        assertFalse(go.state.announced)
        // changing page discards the announcement
        assertFalse(step(a.state, all, WizardAction.Back).state.announced)
        // other pages never start anything
        for (st in WizardStep.entries.filter { it != WizardStep.CHECKUP }) {
            assertEquals(WizardEffect.NONE, step(WizardState(step = st, announced = true), all, WizardAction.ConfirmRead).effect)
        }
    }

    @Test fun noActionYieldsALaunchExceptConfirmRead() {
        val actions = listOf(
            WizardAction.Consent(true), WizardAction.Next, WizardAction.Back, WizardAction.LaterStep, WizardAction.Leave, WizardAction.OpenA11y,
            WizardAction.OpenOverlay, WizardAction.AnnounceRead, WizardAction.CancelAnnounce, WizardAction.Finish, WizardAction.Observe,
        )
        for (st in WizardStep.entries) for (a in actions) {
            val r = step(WizardState(step = st, consent = true, waiting = true, announced = true), all, a)
            assertFalse("$st/$a", r.effect == WizardEffect.LAUNCH_CHECKUP_AND_WHATSAPP)
        }
    }

    @Test fun backLaterLeaveFinish() {
        assertEquals(WizardStep.WELCOME, step(WizardState(step = WizardStep.WELCOME), all, WizardAction.Back).state.step)
        assertEquals(WizardStep.OVERLAY, step(WizardState(step = WizardStep.MODEL), all, WizardAction.Back).state.step)
        assertEquals(WizardStep.CHECKUP, step(WizardState(step = WizardStep.MODEL), none, WizardAction.LaterStep).state.step)
        assertEquals(WizardStep.DONE, step(WizardState(step = WizardStep.CHECKUP), none, WizardAction.LaterStep).state.step)
        assertEquals("Pflichtseiten kann man nicht uebergehen", WizardStep.A11Y, step(WizardState(step = WizardStep.A11Y), none, WizardAction.LaterStep).state.step)
        assertTrue(step(WizardState(step = WizardStep.MODEL), none, WizardAction.Leave).state.skipped)
        assertTrue(step(WizardState(step = WizardStep.DONE), all, WizardAction.Finish).state.finished)
        assertFalse(step(WizardState(step = WizardStep.MODEL), all, WizardAction.Finish).state.finished)
    }

    @Test fun stepNameRoundTripForResume() {
        for (s in WizardStep.entries) assertEquals(s, WizardStep.parse(s.name))
        assertEquals(WizardStep.WELCOME, WizardStep.parse("kaputt"))
        assertEquals(WizardStep.WELCOME, WizardStep.parse(null))
    }

    @Test fun wizardTextsAreGermanShortWithoutDashesOrEmoji() {
        val roots = listOf("src/main/kotlin/app/chatlens/ui/WizardUi.kt", "app/src/main/kotlin/app/chatlens/ui/WizardUi.kt")
        val t = roots.map(::File).first { it.isFile }.readText()
        val texts = Regex("(?:Title|Body|Done|Text)\\((\"[^\"]*\")").findAll(t).map { it.groupValues[1].trim('"') }.toList()
        assertTrue(texts.size > 20)
        for (x in texts) {
            assertFalse("dash in: $x", x.contains('–') || x.contains('—') || x.contains(" - "))
            assertFalse("Emoji in: $x", x.any { Character.getType(it) == Character.SURROGATE.toInt() || it.code in 0x2600..0x27BF })
        }
        // each body text is at most two short sentences
        val bodies = Regex("Body\\(\"([^\"]*)\"").findAll(t).map { it.groupValues[1] }.toList()
        assertTrue(bodies.size >= 10)
        for (b in bodies) {
            assertTrue("zu viele Saetze: $b", b.count { it == '.' } <= 2)
            assertTrue("zu lang: $b", b.length <= 100)
        }
    }

    @Test fun systemDialogForA11yNeedsItsOwnConsent() {
        // 0.3.0 (U2): without its own consent on page 2 there is no effect and no waiting
        val no = step(WizardState(step = WizardStep.A11Y), none, WizardAction.OpenA11y)
        assertEquals(WizardEffect.NONE, no.effect)
        assertFalse(no.state.waiting)
        val given = step(WizardState(step = WizardStep.A11Y), none, WizardAction.A11yConsent(true)).state
        assertTrue(given.a11yConsent)
        assertEquals(WizardEffect.OPEN_A11Y_SETTINGS, step(given, none, WizardAction.OpenA11y).effect)
        // withdrawing consent locks it again
        val back = step(given, none, WizardAction.A11yConsent(false)).state
        assertEquals(WizardEffect.NONE, step(back, none, WizardAction.OpenA11y).effect)
    }

    @Test fun welcomeConsentIsSeparateFromA11yConsent() {
        val s = step(WizardState(), none, WizardAction.Consent(true)).state
        assertTrue(s.consent)
        assertFalse(s.a11yConsent)
    }
}
