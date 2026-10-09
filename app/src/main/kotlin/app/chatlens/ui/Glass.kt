package app.chatlens.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Turns off the infinite animations (for screenshots in tests). */
val LocalGlassAnimate = androidx.compose.runtime.compositionLocalOf { true }

/**
 * Colors of the glass design: a dark background, one accent (turquoise), and a second accent (violet) for gradients.
 *
 * Contrast (from 0.2.3): all text colors live HERE and are set through [glassColorScheme] and LocalContentColor in the theme.
 * Cards are a dark tint at [CardAlpha] (70 percent) over the background, and the text is light (almost white). The test
 * ThemeContrastTest checks every text/background pair against WCAG AA (at least 4.5:1), including over the brightest background blob.
 * If a new text color is needed, take one of the text colors here and add it to the test.
 */
object GlassColors {
    val BgTop = Color(0xFF0B1020)
    val BgBottom = Color(0xFF151A33)
    val Accent = Color(0xFF4DE3D0)
    val Accent2 = Color(0xFF8B7CFF)

    /** Lightened violet for TEXT (Accent2 itself is only for fills and gradients). */
    val Accent2Text = Color(0xFFCFC9FF)
    val Ok = Color(0xFF6EE7A8)
    val Warn = Color(0xFFFFC46B)
    val Bad = Color(0xFFFF8D9A)

    /** Red text for destructive actions (remove the dot). Contrast is covered by ThemeContrastTest. */
    val Danger = Color(0xFFFF8A8A)
    val Text = Color(0xFFEAF0FF)
    val TextDim = Color(0xFFC3CCE6)

    /** Dark text on light fills (accent button, error button, violet fill). */
    val OnAccent = Color(0xFF00201C)
    val OnBad = Color(0xFF1A0A0C)
    val OnAccent2 = Color(0xFF0B1020)

    /** Card: a dark tint (70 percent opaque) plus a very slight light sheen. */
    val CardTint = Color(0xFF080C1C)
    const val CardAlpha = 0.70f
    const val CardSheenAlpha = 0.08f
    val CardFill = CardTint.copy(alpha = CardAlpha)

    /** The floating window (overlay) sits over other apps, which may be white: nearly opaque. */
    val PanelFill = Color(0xEB080C1C)

    /** Dialogs and menus: opaque. */
    val DialogSurface = Color(0xFF161C36)
    val SurfaceHigh = Color(0xFF1D2442)
    const val ChipSelectedAlpha = 0.18f
    val ChipOffFill = Color(0x14FFFFFF)
    val GlassFill = Color(0x1AFFFFFF)
    val GlassEdgeTop = Color(0x66FFFFFF)
    val GlassEdgeBottom = Color(0x1AFFFFFF)
}

/** Complete color scheme. No role stays on the default (there, text would be black or almost transparent). */
fun glassColorScheme(): androidx.compose.material3.ColorScheme = darkColorScheme(
    primary = GlassColors.Accent, onPrimary = GlassColors.OnAccent,
    primaryContainer = Color(0xFF14504A), onPrimaryContainer = GlassColors.Text,
    secondary = GlassColors.Accent2, onSecondary = GlassColors.OnAccent2,
    secondaryContainer = Color(0xFF2E3466), onSecondaryContainer = GlassColors.Text,
    tertiary = GlassColors.Warn, onTertiary = Color(0xFF2A1A00),
    tertiaryContainer = Color(0xFF4A3510), onTertiaryContainer = GlassColors.Text,
    background = GlassColors.BgTop, onBackground = GlassColors.Text,
    surface = GlassColors.DialogSurface, onSurface = GlassColors.Text,
    surfaceVariant = GlassColors.SurfaceHigh, onSurfaceVariant = GlassColors.TextDim,
    surfaceTint = GlassColors.Accent,
    inverseSurface = GlassColors.Text, inverseOnSurface = GlassColors.BgTop, inversePrimary = Color(0xFF00695F),
    error = GlassColors.Bad, onError = GlassColors.OnBad,
    errorContainer = Color(0xFF5A1F28), onErrorContainer = GlassColors.Text,
    outline = Color(0xFF8D98BA), outlineVariant = Color(0xFF3A4366),
    scrim = Color(0xFF000000),
    surfaceBright = GlassColors.SurfaceHigh, surfaceDim = GlassColors.BgTop,
    surfaceContainerLowest = GlassColors.BgTop, surfaceContainerLow = GlassColors.DialogSurface,
    surfaceContainer = GlassColors.DialogSurface, surfaceContainerHigh = GlassColors.DialogSurface,
    surfaceContainerHighest = GlassColors.SurfaceHigh,
)

private val GlassTypography = Typography().let { t ->
    t.copy(
        titleMedium = t.titleMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = 0.2.sp),
        titleLarge = t.titleLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp),
        bodyMedium = t.bodyMedium.copy(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = t.bodySmall.copy(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = t.labelLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium),
    )
}

/**
 * The theme sets the text color in one place: LocalContentColor is light. Without that, any text without an explicit color
 * (a scaffold with a transparent background, an overlay without a surface) would be drawn in default black, so black on dark glass (bug in 0.2.2).
 * Applies to the app, the overlay (OverlayService uses GlassTheme), and dialogs (Compose dialogs inherit the CompositionLocals).
 */
@Composable
fun GlassTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = glassColorScheme(), typography = GlassTypography) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides GlassColors.Text,
            androidx.compose.foundation.text.selection.LocalTextSelectionColors provides androidx.compose.foundation.text.selection.TextSelectionColors(
                handleColor = GlassColors.Accent, backgroundColor = GlassColors.Accent.copy(alpha = 0.35f),
            ),
            content = content,
        )
    }
}

/**
 * Background: a gradient plus soft colored blobs. On Android 12+ (API 31) the blobs are really
 * blurred with Modifier.blur; on older versions they are only a radial gradient. Compose cannot provide a real blur window BEHIND each card;
 * the glass look comes from the gradient, the blobs, transparency, and a light edge.
 */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val animate = LocalGlassAnimate.current
    val drift = if (animate) {
        val t = rememberInfiniteTransition(label = "blobs")
        val d by t.animateFloat(0f, 1f, infiniteRepeatable(tween(14000), RepeatMode.Reverse), label = "drift")
        d
    } else 0.5f
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(GlassColors.BgTop, GlassColors.BgBottom)))) {
        val blurMod = if (Build.VERSION.SDK_INT >= 31) Modifier.blur(60.dp) else Modifier
        Box(Modifier.align(Alignment.TopStart).offset(x = (-60 + 40 * drift).dp, y = (40 + 30 * drift).dp).size(260.dp).then(blurMod)
            .background(Brush.radialGradient(listOf(GlassColors.Accent.copy(alpha = 0.45f), Color.Transparent)), CircleShape))
        Box(Modifier.align(Alignment.CenterEnd).offset(x = (70 - 40 * drift).dp, y = (60 * drift).dp).size(300.dp).then(blurMod)
            .background(Brush.radialGradient(listOf(GlassColors.Accent2.copy(alpha = 0.45f), Color.Transparent)), CircleShape))
        Box(Modifier.align(Alignment.BottomStart).offset(x = (20 * drift).dp, y = (40 - 30 * drift).dp).size(240.dp).then(blurMod)
            .background(Brush.radialGradient(listOf(Color(0xFFFF6FB5).copy(alpha = 0.25f), Color.Transparent)), CircleShape))
        content()
    }
}

/** Card of dark glass (70 percent opaque tint, slight sheen, light edge). [highlight] emphasizes the card with an accent edge (for example setup). */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    corner: Dp = 22.dp,
    fill: Color = GlassColors.CardFill,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    val edge = if (highlight) Brush.linearGradient(listOf(GlassColors.Accent, GlassColors.Accent2))
    else Brush.verticalGradient(listOf(GlassColors.GlassEdgeTop, GlassColors.GlassEdgeBottom))
    Box(
        modifier
            .clip(shape)
            .background(fill)
            .background(Brush.linearGradient(listOf(Color.White.copy(alpha = GlassColors.CardSheenAlpha), Color.White.copy(alpha = 0.02f))))
            .border(BorderStroke(if (highlight) 1.5.dp else 1.dp, edge), shape),
    ) { content() }
}

@Composable
fun Section(title: String, highlight: Boolean = false, content: @Composable () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), highlight) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (highlight) GlassColors.Accent else GlassColors.Text)
            content()
        }
    }
}

@Composable
fun WarningCard(text: String) {
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(Modifier.background(GlassColors.Warn.copy(alpha = 0.14f)).padding(14.dp)) {
            Text(text, color = GlassColors.Warn, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun GlassChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (selected) GlassColors.Accent.copy(alpha = GlassColors.ChipSelectedAlpha) else GlassColors.ChipOffFill, label = "chipBg")
    val fg by animateColorAsState(if (selected) GlassColors.Accent else GlassColors.TextDim, label = "chipFg")
    Box(
        modifier.clip(RoundedCornerShape(50)).background(bg)
            .border(1.dp, if (selected) GlassColors.Accent.copy(alpha = 0.7f) else Color(0x33FFFFFF), RoundedCornerShape(50))
            // A2: at least 48 dp tall, button role, selected state for TalkBack
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg, style = MaterialTheme.typography.labelLarge) }
}

/** Small status line with a pulsing dot while something is running. */
@Composable
fun StatusPill(text: String, running: Boolean, error: Boolean = false) {
    val a = if (LocalGlassAnimate.current && running) {
        val t = rememberInfiniteTransition(label = "pulse")
        val v by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulseA")
        v
    } else 1f
    val c = when { error -> GlassColors.Bad; running -> GlassColors.Accent; else -> GlassColors.Ok }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).alpha(if (running) a else 1f).background(c, CircleShape))
        Text(text, color = GlassColors.Text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun StepRow(done: Boolean, title: String, hint: String) {
    // A3: the state is not only color, but also text for TalkBack and a checkmark in the circle
    Row(
        Modifier.semantics(mergeDescendants = true) { stateDescription = if (done) "erledigt" else "offen" },
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(18.dp).background(if (done) GlassColors.Ok.copy(alpha = 0.9f) else Color(0x22FFFFFF), CircleShape)
                .border(1.dp, if (done) GlassColors.Ok else Color(0x55FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (done) Box(Modifier.size(6.dp).background(GlassColors.OnAccent, CircleShape)) }
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (done) GlassColors.TextDim else GlassColors.Text)
            if (!done && hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
        }
    }
}
