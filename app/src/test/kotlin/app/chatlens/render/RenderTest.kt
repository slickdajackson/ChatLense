package app.chatlens.render

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import app.chatlens.ui.GlassChip
import app.chatlens.ui.GlassColors
import app.chatlens.ui.Section
import app.chatlens.ui.WarningCard
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.chatlens.agent.AgentState
import app.chatlens.agent.AutoState
import app.chatlens.agent.Phase
import app.chatlens.agent.QueueItemView
import app.chatlens.auto.ItemStatus
import app.chatlens.auto.QueueKind
import app.chatlens.data.AppSettings
import app.chatlens.ui.GlassBackground
import app.chatlens.ui.GlassTheme
import app.chatlens.ui.LocalGlassAnimate
import app.chatlens.ui.OverlayDot
import app.chatlens.ui.OverlayPanel
import app.chatlens.ui.StartScreen
import app.chatlens.ui.ModelsScreen
import app.chatlens.models.DeviceInfo
import app.chatlens.models.DlStatus
import app.chatlens.models.DlUi
import app.chatlens.models.ModelDownloads
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the UI without an emulator (Robolectric, native graphics mode) to PNG files under app/build/renders.
 * This is a preview with test data, not evidence of behavior on the device. Real blur (Modifier.blur, API 31+)
 * is not drawn here; the preview shows the variant without blur.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1000dp-xhdpi")
class RenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun snap(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val out = File("build/renders").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun renderStartWithSetupProgress() {
        val names = listOf("Anna Meier", "Familie", "Team Projekt", "Lukas", "Mutti", "Dr. Schulz")
        val st = listOf(ItemStatus.FERTIG, ItemStatus.FERTIG, ItemStatus.FEHLER, ItemStatus.LAEUFT, ItemStatus.WARTET, ItemStatus.WARTET)
        AutoState.update {
            it.copy(
                running = true, kind = QueueKind.SETUP, message = "Chat 4 von 6: Lukas", done = 3, total = 6,
                items = names.mapIndexed { i, n -> QueueItemView(n, st[i], if (st[i] == ItemStatus.FEHLER) "Chat nicht in der Liste gefunden" else "", if (st[i] == ItemStatus.FERTIG) "100 Nachrichten gelesen" else "") },
            )
        }
        rule.setContent {
            CompositionLocalProvider(LocalGlassAnimate provides false) {
                GlassTheme {
                    GlassBackground {
                        StartScreen(
                            settings = AppSettings(privacyAcknowledged = true, setupCount = 20),
                            onSettings = {}, hasBackend = true, overlayGranted = true, memoryCount = 2,
                            onOpenA11y = {}, onOpenOverlay = {}, onStartCheckup = {}, onStartSetup = {}, onResume = {}, onCancel = {}, onGoto = {},
                        )
                    }
                }
            }
        }
        snap("start-setup")
    }

    @Test
    fun renderCheckupMenu() {
        val names = listOf("Anna Meier", "Familie", "Team Projekt", "Lukas", "Mutti", "Dr. Schulz", "Neuer Kontakt")
        val es = names.mapIndexed { i, n ->
            app.chatlens.match.ChatListEntry(n, "Vorschau zu $n", "1$i:00", i == 0, false, i == 1 || i == 2, i, unread = i % 3 == 0, unreadCount = if (i % 3 == 0) i + 1 else 0)
        }
        val prev = app.chatlens.checkup.CheckupStored(1L, listOf("Anna Meier", "Lukas"), names.dropLast(1), names.dropLast(1))
        app.chatlens.agent.AutoState.update { it.copy(running = false, message = "") }
        app.chatlens.agent.CheckupState.applyScan(app.chatlens.checkup.CheckupSelection.reconcile(es, prev), System.currentTimeMillis(), "7 Chats gelesen (Listenende erreicht), 2 vorgewählt, 1 neu.")
        rule.setContent {
            CompositionLocalProvider(LocalGlassAnimate provides false) {
                GlassTheme {
                    GlassBackground {
                        StartScreen(
                            settings = AppSettings(privacyAcknowledged = true, setupCount = 20),
                            onSettings = {}, hasBackend = true, overlayGranted = true, memoryCount = 2,
                            onOpenA11y = {}, onOpenOverlay = {}, onStartCheckup = {}, onStartSetup = {}, onResume = {}, onCancel = {}, onGoto = {},
                        )
                    }
                }
            }
        }
        snap("checkup-menu")
    }

    @Test
    fun renderOverlayRingAndPanel() {
        AgentState.update {
            it.copy(
                phase = Phase.DONE, message = "Fertig.", resultChat = "Anna Meier",
                suggestions = listOf("Passt, dann bis Donnerstag um 18 Uhr.", "Donnerstag klingt gut. Soll ich einen Tisch reservieren?"),
            )
        }
        rule.setContent {
            CompositionLocalProvider(LocalGlassAnimate provides false) {
                GlassTheme {
                    GlassBackground {
                        Column(Modifier.fillMaxSize().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            OverlayPanel(onInsert = {}, onClose = {}, onOpenApp = {})
                            Box(Modifier.padding(top = 40.dp)) { OverlayDot(expanded = true, onToggle = {}, onDrag = { _, _ -> }, onAction = {}) }
                        }
                    }
                }
            }
        }
        snap("overlay-ring")
    }

    /** Contrast sample: text WITHOUT an explicit color in the scaffold (the bug from 0.2.2 showed black text here). */
    @Test
    fun renderContrastSampler() {
        rule.setContent {
            CompositionLocalProvider(LocalGlassAnimate provides false) {
                GlassTheme {
                    GlassBackground {
                        Scaffold(containerColor = Color.Transparent, contentColor = GlassColors.Text) { pad ->
                            Column(Modifier.padding(pad).padding(top = 24.dp)) {
                                ScrollableTabRow(selectedTabIndex = 1, containerColor = Color.Transparent, contentColor = GlassColors.Accent, edgePadding = 8.dp) {
                                    listOf("Start", "Analyse", "Auto", "Debug").forEachIndexed { i, t ->
                                        Tab(selected = i == 1, onClick = {}, text = { Text(t) }, selectedContentColor = GlassColors.Accent, unselectedContentColor = GlassColors.TextDim)
                                    }
                                }
                                Text("Text ohne eigene Farbe direkt im Scaffold", Modifier.padding(12.dp))
                                Section("Karte mit Standardtext") {
                                    Text("Dieser Text hat keine explizite Farbe und kommt aus dem Theme.")
                                    Text("Gedämpfter Text", color = GlassColors.TextDim, style = MaterialTheme.typography.bodySmall)
                                    OutlinedTextField(value = "Eingabe", onValueChange = {}, label = { Text("Beschriftung") }, placeholder = { Text("Platzhalter") })
                                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = {}) { Text("Knopf") }
                                        OutlinedButton(onClick = {}) { Text("Rand") }
                                        Switch(checked = true, onCheckedChange = {})
                                    }
                                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                                        GlassChip("Gewählt", true, {})
                                        GlassChip("Nicht gewählt", false, {})
                                    }
                                    Text("OK-Farbe", color = GlassColors.Ok)
                                    Text("Warnfarbe", color = GlassColors.Warn)
                                    Text("Fehlerfarbe", color = GlassColors.Bad)
                                    Text("Violett (Schrift)", color = GlassColors.Accent2Text)
                                }
                                Section("Hervorgehobene Karte", highlight = true) { Text("Standardtext in der Setup-Karte.") }
                                WarningCard("Warnhinweis auf der Karte.")
                                // Dialog surface (AlertDialog uses surfaceContainerHigh, onSurface, and onSurfaceVariant)
                                Surface(Modifier.padding(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text("Dialogtitel", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                                        Text("Dialogtext in onSurfaceVariant.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Knopf im Dialog", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        snap("kontrast-muster")
    }

    /**
     * Shows the cause of the black text in the rendered image: without LocalContentColor set (the 0.2.2 theme), text without
     * a color is drawn dark in the scaffold; with the 0.2.3 theme it is light. The brightest pixel color of each half is measured.
     */
    @Test
    fun plainTextIsDarkWithOldThemeAndLightWithNewTheme() {
        rule.setContent {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxSize().androidx_background(GlassColors.BgTop)) {
                    // old behavior: color scheme without LocalContentColor
                    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                        Scaffold(containerColor = Color.Transparent) { pad -> Text("Alt ohne Farbe", Modifier.padding(pad).padding(20.dp)) }
                    }
                }
                Box(Modifier.weight(1f).fillMaxSize().androidx_background(GlassColors.BgTop)) {
                    GlassTheme {
                        Scaffold(containerColor = Color.Transparent, contentColor = GlassColors.Text) { pad -> Text("Neu ohne Farbe", Modifier.padding(pad).padding(20.dp)) }
                    }
                }
            }
        }
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        fun brightest(x0: Int, x1: Int): Int {
            var best = 0
            for (y in 0 until bmp.height / 4) for (x in x0 until x1) {
                val p = bmp.getPixel(x, y)
                val l = (android.graphics.Color.red(p) * 299 + android.graphics.Color.green(p) * 587 + android.graphics.Color.blue(p) * 114) / 1000
                if (l > best) best = l
            }
            return best
        }
        val w = bmp.width
        // The background is very dark (luminance about 20): the old text stays near that or below it, the new text is clearly lighter
        val old = brightest(0, w / 2)
        val new = brightest(w / 2, w)
        org.junit.Assert.assertTrue("neuer Text muss hell sein (hellster Pixel $new)", new > 200)
        org.junit.Assert.assertTrue("alter Text war dunkel (hellster Pixel $old)", old < 90)
    }

    private fun Modifier.androidx_background(c: Color): Modifier = this.then(Modifier.background(c))

    private fun renderModels(name: String, dev: DeviceInfo) {
        ModelDownloads.set("qwen3-1.7b-int8", DlUi(DlStatus.RUNNING, 812_000_000L, 2_056_729_520L, "Lade ..."))
        rule.setContent {
            CompositionLocalProvider(LocalGlassAnimate provides false) {
                GlassTheme {
                    GlassBackground {
                        ModelsScreen(
                            settings = AppSettings(), onOpenUrl = {}, onStart = { _, _ -> }, onPause = {}, onCancel = {},
                            onUse = { _, _ -> }, onDelete = { _, _ -> }, onUseFile = {}, deviceOverride = dev,
                        )
                    }
                }
            }
        }
        snap(name)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h3600dp-xhdpi")
    fun renderModelsBigPhone() = renderModels("modelle-12gb", DeviceInfo(12_000, 6_500, 80_000_000_000L, true))

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h3600dp-xhdpi")
    fun renderModelsSmallPhone() = renderModels("modelle-4gb-mobil", DeviceInfo(4_000, 1_800, 2_500_000_000L, false))

    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h1100dp-xhdpi")
    fun renderVoiceSection() {
        rule.setContent {
            CompositionLocalProvider(LocalGlassAnimate provides false) {
                GlassTheme {
                    GlassBackground {
                        androidx.compose.foundation.layout.Column {
                            app.chatlens.ui.VoiceSection(AppSettings(voiceTranscribe = true), {}, {})
                        }
                    }
                }
            }
        }
        snap("sprachnachrichten-einstellungen")
    }
}
