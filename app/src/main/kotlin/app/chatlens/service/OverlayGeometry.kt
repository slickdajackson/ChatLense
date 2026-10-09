package app.chatlens.service

import kotlin.math.max
import kotlin.math.min

/**
 * Pure calculation rules for the floating dot (from 0.3.0), with no Android classes and therefore testable:
 * clamp to the visible area, snap to the left or right edge, and store the position as a fraction of the height (survives rotation).
 * All values are in pixels, except [Side] and fractions.
 */
object OverlayGeometry {
    /** Visible area: screen size and system bars (status bar, navigation bar, cutout). */
    data class Area(val width: Int, val height: Int, val insetLeft: Int = 0, val insetTop: Int = 0, val insetRight: Int = 0, val insetBottom: Int = 0) {
        val left get() = insetLeft
        val top get() = insetTop
        val right get() = width - insetRight
        val bottom get() = height - insetBottom
    }

    enum class Side(val value: Int) { LEFT(0), RIGHT(1) }

    fun sideOf(v: Int): Side = if (v == 0) Side.LEFT else Side.RIGHT

    /** Clamps the top-left corner of a window of size [w] by [h] to the area. If the window is larger than the area, the top-left edge applies. */
    fun clamp(x: Int, y: Int, w: Int, h: Int, a: Area): Pair<Int, Int> {
        val cx = max(a.left, min(x, a.right - w))
        val cy = max(a.top, min(y, a.bottom - h))
        return (if (a.right - w < a.left) a.left else cx) to (if (a.bottom - h < a.top) a.top else cy)
    }

    /** Nearest edge after release, measured at the center of the window. */
    fun snapSide(x: Int, w: Int, a: Area): Side = if (x + w / 2 < (a.left + a.right) / 2) Side.LEFT else Side.RIGHT

    /** x position at the edge; [margin] is the distance from the edge. */
    fun xAtSide(side: Side, w: Int, a: Area, margin: Int): Int = if (side == Side.LEFT) a.left + margin else a.right - w - margin

    /** Height fraction, from 0 to 1, of the window center within the usable height. */
    fun yFraction(y: Int, h: Int, a: Area): Float {
        val span = (a.bottom - a.top - h).coerceAtLeast(1)
        return ((y - a.top).toFloat() / span).coerceIn(0f, 1f)
    }

    fun yFromFraction(f: Float, h: Int, a: Area): Int {
        val span = (a.bottom - a.top - h).coerceAtLeast(0)
        return a.top + (f.coerceIn(0f, 1f) * span).toInt()
    }

    /**
     * Ring: the ring window ([ring]) grows around the dot ([dot]). If the dot is near the edge, the ring would be clipped;
     * the window then shifts inward (the dot moves with it). Returns the top-left corner of the ring window.
     */
    fun ringOrigin(dotX: Int, dotY: Int, dot: Int, ring: Int, a: Area): Pair<Int, Int> =
        clamp(dotX - (ring - dot) / 2, dotY - (ring - dot) / 2, ring, ring, a)
}
