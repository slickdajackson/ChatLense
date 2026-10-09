package app.chatlens.render

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import app.chatlens.agent.AgentState
import app.chatlens.agent.CheckupState
import app.chatlens.agent.Phase
import app.chatlens.checkup.CheckupItem
import app.chatlens.match.ChatListEntry
import app.chatlens.models.DlStatus
import app.chatlens.models.DlUi
import app.chatlens.ui.GlassBackground
import app.chatlens.ui.GlassTheme
import app.chatlens.ui.LocalGlassAnimate
import app.chatlens.ui.OverlayPanel
import app.chatlens.ui.WizardActions
import app.chatlens.ui.WizardScreen
import app.chatlens.ui.WizardUi
import app.chatlens.wizard.WizardFacts
import app.chatlens.wizard.WizardState
import app.chatlens.wizard.WizardStep
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renderbilder der Einrichtungsseiten und des Overlay-Panels (0.3.0). Testdaten, Robolectric ohne Blur, kein Beleg fuer das Geraet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class V030RenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun snap(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val out = File("build/renders").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun screen(content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalGlassAnimate provides false) { GlassTheme { GlassBackground { content() } } }
    }

    private val none = WizardFacts(false, false, false, 0)

    @Test fun w2A11yDisclosureUnchecked() {
        screen { WizardScreen(WizardUi(WizardState(step = WizardStep.A11Y, consent = true), none), WizardActions()) }
        snap("v030-wizard-2-offenlegung")
    }

    @Test fun w2A11yDisclosureChecked() {
        screen { WizardScreen(WizardUi(WizardState(step = WizardStep.A11Y, consent = true, a11yConsent = true), none), WizardActions()) }
        snap("v030-wizard-2-offenlegung-zugestimmt")
    }

    @Test fun settingsMessengerAndAbout() {
        screen {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                app.chatlens.ui.SettingsScreen(
                    settings = app.chatlens.data.AppSettings(), onSettings = {}, onPickModel = {}, onReleaseModel = {},
                    overlayGranted = true, onOpenOverlayPerm = {}, onOpenAppSettings = {},
                )
            }
        }
        rule.onNode(hasScrollAction()).performTouchInput { swipeUp(startY = bottom - 10f, endY = top + 10f, durationMillis = 100); swipeUp(startY = bottom - 10f, endY = top + 10f, durationMillis = 100); swipeUp(startY = bottom - 10f, endY = top + 10f, durationMillis = 100) }
        snap("v030-einstellungen-unten")
    }

    @Test fun ringWithNewLabels() {
        screen { androidx.compose.foundation.layout.Box(Modifier.padding(top = 20.dp)) { app.chatlens.ui.OverlayDot(expanded = true, onToggle = {}, onDrag = { _, _ -> }, onAction = {}) } }
        snap("v030-ring")
    }
}
