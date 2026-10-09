package app.chatlens.render

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.chatlens.agent.AgentState
import app.chatlens.agent.AgentUiState
import app.chatlens.agent.AutoState
import app.chatlens.agent.CheckupState
import app.chatlens.agent.Phase
import app.chatlens.agent.PromptChoice
import app.chatlens.agent.RunProgress
import app.chatlens.agent.StepKind
import app.chatlens.core.TaskMode
import app.chatlens.data.AppSettings
import app.chatlens.memory.DiscLevel
import app.chatlens.memory.DiscProfile
import app.chatlens.models.DeviceInfo
import app.chatlens.prompts.PromptBook
import app.chatlens.prompts.PromptMode
import app.chatlens.ui.DiscBar
import app.chatlens.ui.GlassBackground
import app.chatlens.ui.GlassColors
import app.chatlens.ui.GlassTheme
import app.chatlens.ui.LocalGlassAnimate
import app.chatlens.ui.ModelsScreen
import app.chatlens.ui.OverlayDot
import app.chatlens.ui.PromptChoiceCard
import app.chatlens.ui.RunStatus
import app.chatlens.ui.Section
import app.chatlens.ui.SelfAnalysisSection
import app.chatlens.ui.SetupWizardSection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Vorschaubilder der Version 0.2.7 (Logo, Fortschritt, Modelle, Prompt-Karte, Ring, DISC, Einrichtung). Testdaten, kein Beleg fuer das Geraet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1000dp-xxhdpi")
class V027RenderTest {
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

    // ---------- Fortschritt ----------

    @Config(sdk = [34], qualifiers = "w411dp-h2400dp-xhdpi")
    @Test fun progressStates() {
        val steps = RunProgress.plan(TaskMode.ANALYSE, chatAlreadyOpen = false, askPrompt = true, voiceEnabled = true, localModel = true)
        var p = RunProgress.begin(steps, 0L, "Gemma analysiert")
        p = RunProgress.advance(p, StepKind.LLM, 20_000L)
        val running = AgentUiState(phase = Phase.LLM, message = "Das Modell arbeitet.", progress = p, resultChat = "Anna Meier")
        val cancelled = running.copy(phase = Phase.CANCELLED, message = "Abgebrochen.")
        val failed = running.copy(phase = Phase.FAILED, message = "Modell nicht gefunden.", error = "Modelldatei fehlt")
        val done = running.copy(phase = Phase.DONE, message = "Fertig.")
        val sp = RunProgress.advance(RunProgress.begin(steps, 0L, "Gemma analysiert"), StepKind.COLLECT, 4_000L)
        screen {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Section("Läuft: Modell (seit 42 s)") { RunStatus(running, null, nowMs = 62_000L) }
                Section("Sammeln") { RunStatus(running.copy(progress = sp, phase = Phase.SCROLLING, scrollDone = 40, scrollTotal = 100), null, nowMs = 9_000L) }
                Section("Abgebrochen") { RunStatus(cancelled, null, nowMs = 62_000L) }
                Section("Fehler") { RunStatus(failed, null, nowMs = 62_000L) }
                Section("Fertig") { RunStatus(done, null, nowMs = 62_000L) }
            }
        }
        snap("fortschritt")
    }

    // ---------- Modelle ----------

    @Config(sdk = [34], qualifiers = "w411dp-h2400dp-xhdpi")
    @Test fun modelsCompactAndExpanded() {
        screen {
            ModelsScreen(
                settings = AppSettings(), onOpenUrl = {}, onStart = { _, _ -> }, onPause = {}, onCancel = {},
                onUse = { _, _ -> }, onDelete = { _, _ -> }, onUseFile = {},
                deviceOverride = DeviceInfo(12_000, 6_500, 80_000_000_000L, true),
            )
        }
        snap("modelle-kompakt")
        rule.activity.runOnUiThread { }
    }

    @Config(sdk = [34], qualifiers = "w411dp-h2400dp-xhdpi")
    @Test fun modelsOneCardOpen() {
        screen {
            ModelsScreen(
                settings = AppSettings(), onOpenUrl = {}, onStart = { _, _ -> }, onPause = {}, onCancel = {},
                onUse = { _, _ -> }, onDelete = { _, _ -> }, onUseFile = {},
                deviceOverride = DeviceInfo(12_000, 6_500, 80_000_000_000L, true), initialOpen = "gemma-4-e4b",
            )
        }
        snap("modelle-karte-offen")
    }

    // ---------- Prompt-Karte ----------

    @Test fun promptCard() {
        val book = PromptBook(PromptMode.CUSTOM, "Welche Termine nennt Anna?", listOf("Welche Termine nennt Anna?", "Fasse kurz zusammen"), emptyList())
        screen {
            Column(Modifier.padding(12.dp)) {
                Section("Nach dem Lesen: Auswahl") {
                    PromptChoiceCard("Anna Meier", 120, book, onChoice = { _: PromptChoice -> }, onSaveTemplate = { _, _ -> }, onDeleteSaved = {}, onDeleteRecent = {}, onOpenApp = {})
                }
            }
        }
        snap("prompt-karte")
    }

    // ---------- Ring mit rotem Entfernen ----------

    @Test fun ringWithRemove() {
        screen { Box(Modifier.padding(top = 20.dp)) { OverlayDot(expanded = true, onToggle = {}, onDrag = { _, _ -> }, onAction = {}) } }
        snap("ring-entfernen")
    }

    // ---------- DISC ----------

    @Test fun discBars() {
        val full = DiscProfile(45, 25, 20, 10, DiscLevel.MITTEL, "Knappe, direkte Sätze, klare Ansagen.", 90, 0)
        screen {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Section("Panel (kompakt)") { DiscBar(full, who = "Gegenüber") }
                Section("Gedächtnis (mit Begründung)") { DiscBar(full, showReason = true) }
                Section("Zu wenig Daten") { DiscBar(DiscProfile(basis = 8), who = "Gegenüber") }
            }
        }
        snap("disc")
    }

    // ---------- Einrichtungs-Assistent, gesperrt ----------

    @Test fun wizardWithoutCheckup() {
        CheckupState.reset()
        AutoState.update { it.copy(running = false, message = "") }
        screen {
            Column(Modifier.padding(vertical = 8.dp)) {
                SetupWizardSection(AppSettings(privacyAcknowledged = true), hasBackend = true, memoryCount = 0, onCheckup = {})
                SelfAnalysisSection(AppSettings(privacyAcknowledged = true), hasBackend = true, onStart = { _, _, _ -> })
            }
        }
        snap("start-assistent")
    }
}
