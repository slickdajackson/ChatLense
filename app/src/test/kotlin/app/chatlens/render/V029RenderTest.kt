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

/** Renders of the setup pages and the overlay panel (0.2.9). Test data, Robolectric without blur, not evidence for the device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class V029RenderTest {
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
    private fun entry(i: Int, t: String) = CheckupItem(ChatListEntry(t, "Vorschau", "12:0$i", false, false, false, i), selected = i < 4, isNew = false)

    private fun page(name: String, step: WizardStep, facts: WizardFacts, st: WizardState = WizardState(step = step, consent = true), build: (WizardUi) -> WizardUi = { it }) {
        screen { WizardScreen(build(WizardUi(st, facts)), WizardActions()) }
        snap(name)
    }

    @Test fun w1Welcome() = page("wizard-1-willkommen", WizardStep.WELCOME, none, WizardState(consent = false))
    @Test fun w1WelcomeAgreed() = page("wizard-1-willkommen-zugestimmt", WizardStep.WELCOME, none, WizardState(consent = true))
    @Test fun w2A11y() = page("wizard-2-bedienungshilfe", WizardStep.A11Y, none)
    @Test fun w2A11yWaiting() = page("wizard-2-bedienungshilfe-warte", WizardStep.A11Y, none, WizardState(step = WizardStep.A11Y, consent = true, waiting = true))
    @Test fun w3Overlay() = page("wizard-3-overlay", WizardStep.OVERLAY, none.copy(a11y = true))
    @Test fun w4Model() = page("wizard-4-modell", WizardStep.MODEL, none.copy(a11y = true, overlay = true)) { it }
    @Test fun w4ModelDownloading() = page("wizard-4-modell-laedt", WizardStep.MODEL, none.copy(a11y = true, overlay = true)) {
        WizardUi(it.state, it.facts, voiceOn = true, gemmaDl = DlUi(DlStatus.RUNNING, 1_200_000_000L, 3_650_000_000L, ""), parakeetDl = DlUi(DlStatus.IDLE))
    }
    @Test fun w4ModelReady() = page("wizard-4-modell-bereit", WizardStep.MODEL, none.copy(a11y = true, overlay = true, modelReady = true)) {
        WizardUi(it.state, it.facts, voiceOn = true, parakeetReady = true, voiceFolderSet = false)
    }
    @Test fun w5Checkup() = page("wizard-5-checkup", WizardStep.CHECKUP, none.copy(a11y = true, overlay = true, modelReady = true))
    @Test fun w5Announce() = page("wizard-5-checkup-ansage", WizardStep.CHECKUP, none.copy(a11y = true, overlay = true, modelReady = true),
        WizardState(step = WizardStep.CHECKUP, consent = true, announced = true))
    @Test fun w5Selection() {
        val items = listOf("Anna", "Familie", "Ben", "Arbeit", "Clara", "Dennis", "Eva").mapIndexed { i, t -> entry(i, t) }
        page("wizard-5-checkup-auswahl", WizardStep.CHECKUP, none.copy(a11y = true, overlay = true, modelReady = true, checkupRows = items.size)) {
            WizardUi(it.state, it.facts, items = items)
        }
    }
    @Test fun w6Done() = page("wizard-6-fertig", WizardStep.DONE, WizardFacts(true, true, true, 7)) { WizardUi(it.state, it.facts, overlayEnabled = true, checkupOnStart = false) }

    // ---------- Overlay panel with a long result ----------

    private val longText = (1..60).joinToString("\n") { "Zeile $it: Dies ist ein langer Absatz der Analyse mit vielen Worten, damit das Panel scrollen muss." }

    @Test fun overlayPanelScrollsLongResult() {
        AgentState.reset()
        AgentState.update { it.copy(phase = Phase.DONE, result = longText, resultChat = "Anna Meier", message = "Fertig.") }
        screen {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Top) {
                OverlayPanel(onInsert = {}, onClose = {}, onOpenApp = {}, maxHeightDp = 420)
            }
        }
        snap("overlay-panel-langes-ergebnis")
        // Bounded height and its own scroll area
        rule.onNodeWithText("Punkt entfernen").assertExists()
        rule.onNodeWithText("Schließen").assertExists()
        val scroll = rule.onNode(hasScrollAction())
        scroll.assertExists()
        val before = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        scroll.performTouchInput { swipeUp() }
        rule.waitForIdle()
        val after = rule.onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("Touch-Scrollen bewegt den Inhalt ($before -> $after)", after > before)
        val maxV = rule.onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
        assertTrue("Inhalt ist laenger als die Flaeche", maxV > 0f)
        snap("overlay-panel-gescrollt")
        // The header stays put
        rule.onNodeWithText("Punkt entfernen").assertExists()
        AgentState.reset()
    }

    @Test fun overlayPanelWhileRunningShowsCancel() {
        AgentState.reset()
        AgentState.update { it.copy(phase = Phase.LLM, message = "Das Modell arbeitet.") }
        screen { Column(Modifier.padding(12.dp)) { OverlayPanel(onInsert = {}, onClose = {}, onOpenApp = {}, maxHeightDp = 420) } }
        rule.onNodeWithText("Abbrechen").assertExists()
        snap("overlay-panel-laeuft")
        AgentState.reset()
    }
}
