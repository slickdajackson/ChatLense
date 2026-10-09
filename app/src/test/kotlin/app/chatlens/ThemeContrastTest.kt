package app.chatlens

import androidx.compose.ui.graphics.Color
import app.chatlens.ui.GlassColors
import app.chatlens.ui.glassColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * Prueft die Kontrastverhaeltnisse der Theme-Farben nach WCAG 2.x (AA: mindestens 4,5:1 fuer normalen Text).
 * Schrift liegt auf Karten (70 Prozent dunkle Toenung ueber dem Hintergrund). Als Hintergrund unter der Karte werden die unguenstigsten
 * Stellen des Glas-Hintergrunds angenommen: Verlaufsenden, die hellsten Farbflecken und deren Ueberlagerung.
 */
class ThemeContrastTest {
    private fun lin(c: Float): Double = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun lum(c: Color): Double = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val la = lum(a)
        val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** fg mit Alpha ueber bg gelegt (bg deckend angenommen). */
    private fun over(fg: Color, bg: Color): Color {
        val a = fg.alpha
        return Color(fg.red * a + bg.red * (1 - a), fg.green * a + bg.green * (1 - a), fg.blue * a + bg.blue * (1 - a), 1f)
    }

    private fun opaque(c: Color) = Color(c.red, c.green, c.blue, 1f)

    /** Hintergruende, die unter einer Karte liegen koennen: Verlaufsenden und Farbflecken (Spitzenwert 45 bzw. 25 Prozent, auch ueberlagert). */
    private val backdrops: Map<String, Color> = run {
        val top = GlassColors.BgTop
        val bot = GlassColors.BgBottom
        val pinkBlob = Color(0xFFFF6FB5)
        mapOf(
            "BgTop" to top,
            "BgBottom" to bot,
            "Akzent-Fleck ueber BgTop" to over(GlassColors.Accent.copy(alpha = 0.45f), top),
            "Akzent-Fleck ueber BgBottom" to over(GlassColors.Accent.copy(alpha = 0.45f), bot),
            "Violett-Fleck ueber BgBottom" to over(GlassColors.Accent2.copy(alpha = 0.45f), bot),
            "Akzent+Violett" to over(GlassColors.Accent2.copy(alpha = 0.45f), over(GlassColors.Accent.copy(alpha = 0.45f), bot)),
            "Rosa-Fleck" to over(pinkBlob.copy(alpha = 0.25f), bot),
            "Akzent+Rosa" to over(pinkBlob.copy(alpha = 0.25f), over(GlassColors.Accent.copy(alpha = 0.45f), bot)),
        )
    }

    /** Karte so, wie sie GlassCard zeichnet: dunkle Toenung, darueber der hellste Schimmer. */
    private fun card(backdrop: Color): Color {
        val tinted = over(GlassColors.CardFill, backdrop)
        return over(Color.White.copy(alpha = GlassColors.CardSheenAlpha), tinted)
    }

    private val aa = 4.5

    private val textColors: Map<String, Color> = mapOf(
        "Text" to GlassColors.Text,
        "TextDim" to GlassColors.TextDim,
        "Accent" to GlassColors.Accent,
        "Accent2Text" to GlassColors.Accent2Text,
        "Ok" to GlassColors.Ok,
        "Warn" to GlassColors.Warn,
        "Bad" to GlassColors.Bad,
        "Danger" to GlassColors.Danger,
    )

    @Test fun cardIsAtLeast55AndAtMost72PercentOpaque() {
        assertTrue(GlassColors.CardAlpha in 0.55f..0.72f)
        assertTrue("Panel (Overlay) deckender als Karte", GlassColors.PanelFill.alpha > GlassColors.CardAlpha)
    }

    @Test fun allTextColorsReachAAOnCardsOverTheWorstBackgrounds() {
        val failures = ArrayList<String>()
        for ((bn, b) in backdrops) for ((tn, t) in textColors) {
            val r = ratio(t, card(b))
            if (r < aa) failures.add("%s auf Karte ueber %s: %.2f".format(tn, bn, r))
        }
        assertTrue("Zu geringer Kontrast: $failures", failures.isEmpty())
    }

    @Test fun primaryTextHasAtLeast7to1AaaOnCards() {
        for ((bn, b) in backdrops) {
            val r = ratio(GlassColors.Text, card(b))
            assertTrue("Text auf Karte ueber $bn: $r", r >= 7.0)
        }
    }

    @Test fun textColorsReachAAOnPlainBackgroundAndOnOverlayPanelOverWhite() {
        for ((tn, t) in textColors) {
            assertTrue("$tn auf BgTop", ratio(t, GlassColors.BgTop) >= aa)
            assertTrue("$tn auf BgBottom", ratio(t, GlassColors.BgBottom) >= aa)
            // Overlay liegt ueber fremden Apps; im unguenstigsten Fall ist darunter reines Weiss
            val panelOverWhite = over(GlassColors.PanelFill, Color.White)
            assertTrue("$tn auf Overlay ueber Weiss: ${ratio(t, panelOverWhite)}", ratio(t, panelOverWhite) >= aa)
        }
    }

    @Test fun progressGraphicsReachThreeToOneAndRingRemoveLabelReachesAA() {
        // Fortschrittsanzeige: Schrittbalken und Wartesymbol sind Grafik (WCAG 1.4.11, mindestens 3:1), die Aussage steht zusaetzlich im Text.
        val panelOverWhite = over(GlassColors.PanelFill, Color.White)
        for ((bn, b) in backdrops) for ((n, col) in mapOf("Accent" to GlassColors.Accent, "Ok" to GlassColors.Ok, "Warn" to GlassColors.Warn, "Bad" to GlassColors.Bad)) {
            assertTrue("$n als Grafik ueber $bn", ratio(col, card(b)) >= 3.0)
        }
        for ((n, col) in mapOf("Accent" to GlassColors.Accent, "Ok" to GlassColors.Ok)) assertTrue("$n auf Panel ueber Weiss", ratio(col, panelOverWhite) >= 3.0)
        // Beschriftung "Entfernen" im Ring: rot auf der dunklen Ringflaeche und auf dem Panel ueber Weiss
        assertTrue(ratio(GlassColors.Danger, Color(0xFF0B1020)) >= aa)
        assertTrue(ratio(GlassColors.Danger, Color(0xFF1B2447)) >= aa)
        assertTrue(ratio(GlassColors.Danger, panelOverWhite) >= aa)
    }

    @Test fun tintedBadgesChipsAndWarningAreStillReadable() {
        for ((bn, b) in backdrops) {
            val c = card(b)
            for ((n, col) in mapOf("Ok" to GlassColors.Ok, "Accent" to GlassColors.Accent, "Accent2Text" to GlassColors.Accent2Text, "Warn" to GlassColors.Warn)) {
                val badge = over(col.copy(alpha = 0.18f), c)
                assertTrue("Badge $n ueber $bn: ${ratio(col, badge)}", ratio(col, badge) >= aa)
            }
            val warnCard = over(GlassColors.Warn.copy(alpha = 0.14f), c)
            assertTrue("WarningCard ueber $bn", ratio(GlassColors.Warn, warnCard) >= aa)
            val chipSel = over(GlassColors.Accent.copy(alpha = GlassColors.ChipSelectedAlpha), c)
            assertTrue("Chip gewaehlt ueber $bn", ratio(GlassColors.Accent, chipSel) >= aa)
            val chipOff = over(GlassColors.ChipOffFill, c)
            assertTrue("Chip ungewaehlt ueber $bn", ratio(GlassColors.TextDim, chipOff) >= aa)
        }
    }

    @Test fun colorSchemePairsReachAA() {
        val s = glassColorScheme()
        val pairs = listOf(
            Triple("onPrimary/primary", s.onPrimary, s.primary),
            Triple("onSecondary/secondary", s.onSecondary, s.secondary),
            Triple("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer),
            Triple("onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer),
            Triple("onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer),
            Triple("onTertiary/tertiary", s.onTertiary, s.tertiary),
            Triple("onError/error", s.onError, s.error),
            Triple("onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer),
            Triple("onBackground/background", s.onBackground, s.background),
            Triple("onSurface/surface", s.onSurface, s.surface),
            Triple("onSurfaceVariant/surface", s.onSurfaceVariant, s.surface),
            Triple("onSurface/surfaceContainerHigh (Dialog)", s.onSurface, s.surfaceContainerHigh),
            Triple("onSurfaceVariant/surfaceContainerHigh (Dialogtext)", s.onSurfaceVariant, s.surfaceContainerHigh),
            Triple("primary/surfaceContainerHigh (Dialog-Knoepfe)", s.primary, s.surfaceContainerHigh),
            Triple("error/surfaceContainerHigh", s.error, s.surfaceContainerHigh),
            Triple("onSurfaceVariant/surfaceContainerHighest (Feld, Platzhalter)", s.onSurfaceVariant, s.surfaceContainerHighest),
            Triple("onSurface/surfaceVariant", s.onSurface, s.surfaceVariant),
            Triple("inverseOnSurface/inverseSurface", s.inverseOnSurface, s.inverseSurface),
        )
        val bad = pairs.filter { (_, fg, bg) -> ratio(fg, opaque(bg)) < aa }.map { "%s: %.2f".format(it.first, ratio(it.second, opaque(it.third))) }
        assertTrue("Schema-Paare unter AA: $bad", bad.isEmpty())
        // Keine durchsichtigen Schriftfarben im Schema
        listOf(s.onSurface, s.onSurfaceVariant, s.onBackground, s.onPrimary, s.onSecondary, s.onError).forEach { assertEquals(1f, it.alpha, 0f) }
        // Dialog- und Flaechenfarben deckend
        listOf(s.surface, s.surfaceContainerHigh, s.surfaceContainerHighest, s.surfaceVariant).forEach { assertEquals(1f, it.alpha, 0f) }
    }

    @Test fun buttonTextOnFilledButtonsReachesAA() {
        assertTrue(ratio(GlassColors.OnAccent, GlassColors.Accent) >= aa)
        assertTrue(ratio(GlassColors.OnBad, GlassColors.Bad) >= aa)
        assertTrue(ratio(GlassColors.OnAccent2, GlassColors.Accent2) >= aa)
    }

    @Test fun wcagFormulaMatchesKnownValues() {
        assertEquals(21.0, ratio(Color.White, Color.Black), 0.01)
        assertEquals(1.0, ratio(Color.White, Color.White), 0.001)
        // Schwarz auf dem dunklen Hintergrund: der Fehler aus 0.2.2 waere durchgefallen
        assertTrue(ratio(Color.Black, card(GlassColors.BgBottom)) < aa)
    }

    @Test fun uiSourcesHaveNoHardcodedDarkTextAndTheThemeSetsContentColor() {
        val glass = File("src/main/kotlin/app/chatlens/ui/Glass.kt").readText()
        assertTrue("LocalContentColor muss im Theme gesetzt sein", glass.contains("LocalContentColor provides GlassColors.Text"))
        assertTrue(File("src/main/kotlin/app/chatlens/MainActivity.kt").readText().contains("contentColor = GlassColors.Text"))
        val offenders = ArrayList<String>()
        val roots = listOf(File("src/main/kotlin/app/chatlens/ui"), File("src/main/kotlin/app/chatlens/service"), File("src/main/kotlin/app/chatlens/MainActivity.kt"))
        roots.flatMap { if (it.isDirectory) it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") }.toList() else listOf(it) }.forEach { f ->
            f.readLines().forEachIndexed { i, l ->
                val textColor = Regex("""color\s*=\s*(Color\.Black|androidx\.compose\.ui\.graphics\.Color\.Black|Color\(0xFF000000\)|Color\(0xFFB71C1C\)|Color\.DarkGray)""")
                if (textColor.containsMatchIn(l)) offenders.add("${f.name}:${i + 1}")
            }
        }
        assertTrue("Harte dunkle Schriftfarben: $offenders", offenders.isEmpty())
        val theme = File("src/main/res/values/themes.xml").readText()
        assertTrue("Fenstertheme muss dunkel sein", !theme.contains("Material.Light"))
    }
}
