package app.chatlens.service

import kotlin.math.max
import kotlin.math.min

/**
 * Reine Rechenregeln fuer den schwebenden Punkt (ab 0.3.0), ohne Android-Klassen und damit testbar:
 * Begrenzung auf den sichtbaren Bereich, Einrasten am linken oder rechten Rand, Position als Anteil der Hoehe (ueberlebt Drehung).
 * Alle Werte in Pixeln, ausser [Side] und Anteile.
 */
object OverlayGeometry {
    /** Sichtbarer Bereich: Bildschirmgroesse und Systemleisten (Statusleiste, Navigationsleiste, Ausschnitt). */
    data class Area(val width: Int, val height: Int, val insetLeft: Int = 0, val insetTop: Int = 0, val insetRight: Int = 0, val insetBottom: Int = 0) {
        val left get() = insetLeft
        val top get() = insetTop
        val right get() = width - insetRight
        val bottom get() = height - insetBottom
    }

    enum class Side(val value: Int) { LEFT(0), RIGHT(1) }

    fun sideOf(v: Int): Side = if (v == 0) Side.LEFT else Side.RIGHT

    /** Begrenzt die linke obere Ecke eines Fensters der Groesse [w] mal [h] auf den Bereich. Ist das Fenster groesser als der Bereich, gilt die obere linke Kante. */
    fun clamp(x: Int, y: Int, w: Int, h: Int, a: Area): Pair<Int, Int> {
        val cx = max(a.left, min(x, a.right - w))
        val cy = max(a.top, min(y, a.bottom - h))
        return (if (a.right - w < a.left) a.left else cx) to (if (a.bottom - h < a.top) a.top else cy)
    }

    /** Naechster Rand nach dem Loslassen, gemessen an der Mitte des Fensters. */
    fun snapSide(x: Int, w: Int, a: Area): Side = if (x + w / 2 < (a.left + a.right) / 2) Side.LEFT else Side.RIGHT

    /** x-Position am Rand; [margin] Abstand zum Rand. */
    fun xAtSide(side: Side, w: Int, a: Area, margin: Int): Int = if (side == Side.LEFT) a.left + margin else a.right - w - margin

    /** Hoehenanteil 0..1 der Mitte des Fensters innerhalb der nutzbaren Hoehe. */
    fun yFraction(y: Int, h: Int, a: Area): Float {
        val span = (a.bottom - a.top - h).coerceAtLeast(1)
        return ((y - a.top).toFloat() / span).coerceIn(0f, 1f)
    }

    fun yFromFraction(f: Float, h: Int, a: Area): Int {
        val span = (a.bottom - a.top - h).coerceAtLeast(0)
        return a.top + (f.coerceIn(0f, 1f) * span).toInt()
    }

    /**
     * Ring: Das Ringfenster ([ring]) waechst um den Punkt ([dot]). Liegt der Punkt nah am Rand, wuerde der Ring abgeschnitten;
     * dann rueckt das Fenster nach innen (der Punkt wandert mit). Rueckgabe: linke obere Ecke des Ringfensters.
     */
    fun ringOrigin(dotX: Int, dotY: Int, dot: Int, ring: Int, a: Area): Pair<Int, Int> =
        clamp(dotX - (ring - dot) / 2, dotY - (ring - dot) / 2, ring, ring, a)
}
