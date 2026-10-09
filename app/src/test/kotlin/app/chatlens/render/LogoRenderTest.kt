package app.chatlens.render

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import app.chatlens.R
import app.chatlens.ui.LogoMark
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Finale Renderbilder des Logos (Entwurf 2a) aus den echten Vektordateien. Testdaten, kein Beleg fuer das Geraet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1000dp-xxhdpi")
class LogoRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val page = Color(0xFF10141C)
    private val sub = Color(0xFFB8C2D6)

    private fun snap(name: String) {
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = android.graphics.Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val out = File("build/renders").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Adaptive Icon wie der Launcher: Ebenen 108 dp, sichtbar 72 dp, Form als Maske. */
    @Composable private fun Masked(size: Dp, shape: Shape, mono: Boolean = false) {
        Box(Modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
            Box(Modifier.requiredSize(size * 1.5f)) {
                if (mono) Box(Modifier.requiredSize(size * 1.5f).background(Color(0xFF2B3A55)))
                else Image(painterResource(R.drawable.ic_launcher_bg), null, Modifier.requiredSize(size * 1.5f))
                Image(
                    painterResource(if (mono) R.drawable.ic_launcher_mono else R.drawable.ic_launcher_fg), null, Modifier.requiredSize(size * 1.5f),
                    colorFilter = if (mono) ColorFilter.tint(Color(0xFFA8C7FA)) else null,
                )
            }
        }
    }

    private fun show(name: String, title: String, content: @Composable () -> Unit) {
        rule.setContent {
            Column(Modifier.background(page).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, color = Color.White)
                content()
            }
        }
        snap(name)
    }

    @Test fun kreis() = show("final-kreis", "Adaptive Icon, Kreismaske, 160 dp") { Masked(160.dp, CircleShape) }

    @Test fun quadrat() = show("final-quadrat", "Adaptive Icon, abgerundetes Quadrat, 160 dp") { Masked(160.dp, RoundedCornerShape(40.dp)) }

    @Test fun einfarbig() = show("final-einfarbig", "Themed Icon (monochrome Ebene), Kreis und Quadrat, 160 dp") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Masked(160.dp, CircleShape, mono = true)
            Masked(160.dp, RoundedCornerShape(40.dp), mono = true)
        }
    }

    @Test fun dp48() = show("final-48dp", "Echte Groesse 48 dp: Kreis, Quadrat, einfarbig; daneben die Marke 36 dp und 24 dp") {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Masked(48.dp, CircleShape)
            Masked(48.dp, RoundedCornerShape(12.dp))
            Masked(48.dp, CircleShape, mono = true)
            LogoMark(Modifier.size(36.dp))
            LogoMark(Modifier.size(24.dp))
        }
    }

    @Test fun punktUndKopfzeile() = show("final-punkt-kopfzeile", "Overlay-Punkt (60 dp, Marke 36 dp) und App-Kopfzeile (Marke 40 dp)") {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(60.dp).clip(CircleShape).background(Color(0xFF1B2230)), contentAlignment = Alignment.Center) { LogoMark(Modifier.size(36.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LogoMark(Modifier.size(40.dp), description = "ChatLens Logo")
                Text("ChatLens", color = Color.White)
            }
        }
        Box(Modifier.size(80.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFF4F6FA)), contentAlignment = Alignment.Center) { LogoMark(Modifier.size(56.dp)) }
        Text("Marke auf hellem Grund", color = sub)
    }
}
