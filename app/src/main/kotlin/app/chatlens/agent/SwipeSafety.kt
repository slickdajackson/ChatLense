package app.chatlens.agent

import app.chatlens.core.Bounds

/** Screen dimensions for gesture planning (pixels). [statusBar] is the status bar height, [gestureBottom] is the height of the bottom system gesture zone. */
class ScreenInsets(val width: Int, val height: Int, val statusBar: Int, val gestureBottom: Int)

/**
 * Safe window for swipe gestures. A gesture must never start or end near system zones, or the system reacts instead of WhatsApp:
 * at the top the notification shade and the control center, at the bottom gesture navigation (home, recent apps), at the sides the back gesture.
 *
 * Rules: distance from the top edge at least max(12 percent of screen height, status bar + 150 px), from the bottom edge at least
 * max(12 percent, gesture zone + 250 px), and at the sides at least 15 percent of the width. Every gesture also stays inside the list bounds
 * (with a 4 percent margin). If the distance is not enough, it is split into several swipes of equal length; one swipe is at most
 * [MAX_SEGMENT] of the usable window long.
 */
object SwipeSafety {
    const val MIN_EDGE_FRACTION = 0.12

    /** A swipe never starts in the top 15 percent of the screen (notification zone, heads-up cards). Since 0.2.9. */
    const val TOP_START_FRACTION = 0.15

    /** A swipe toward older messages (finger moving down) starts only from this fraction of the window height from the top, never at the top edge of the window. Since 0.2.9. */
    const val OLDER_START_OFFSET = 0.25
    const val STATUS_EXTRA_PX = 150
    const val GESTURE_EXTRA_PX = 250
    const val SIDE_FRACTION = 0.15
    const val LIST_MARGIN = 0.04
    const val MAX_SEGMENT = 0.70

    /** Allowed y range (top, bottom) for gestures on this list. Can be empty (top >= bottom). */
    fun window(list: Bounds, ins: ScreenInsets): Pair<Int, Int> {
        val topLimit = maxOf((ins.height * TOP_START_FRACTION).toInt(), ins.statusBar + STATUS_EXTRA_PX)
        val bottomLimit = ins.height - maxOf((ins.height * MIN_EDGE_FRACTION).toInt(), ins.gestureBottom + GESTURE_EXTRA_PX)
        val top = maxOf(list.t + (list.height * LIST_MARGIN).toInt(), topLimit)
        val bottom = minOf(list.b - (list.height * LIST_MARGIN).toInt(), bottomLimit)
        return top to bottom
    }

    /** x of the gesture: middle of the list, but never closer than 15 percent of the width to the edge. */
    fun x(list: Bounds, ins: ScreenInsets): Int =
        list.centerX.coerceIn((ins.width * SIDE_FRACTION).toInt(), (ins.width * (1 - SIDE_FRACTION)).toInt())

    /** Is a point (for example a tap target) inside the allowed area? */
    fun pointSafe(xv: Int, yv: Int, ins: ScreenInsets, list: Bounds? = null): Boolean {
        val topLimit = maxOf((ins.height * MIN_EDGE_FRACTION).toInt(), ins.statusBar + STATUS_EXTRA_PX)
        val bottomLimit = ins.height - maxOf((ins.height * MIN_EDGE_FRACTION).toInt(), ins.gestureBottom + GESTURE_EXTRA_PX)
        if (yv < topLimit || yv > bottomLimit) return false
        if (xv < (ins.width * 0.05).toInt() || xv > (ins.width * 0.95).toInt()) return false
        if (list != null && (yv < list.t || yv > list.b)) return false
        return true
    }

    /** Segments: total [distancePx] (clamped to the window), older messages means the finger moves down ([older]). */
    fun plan(list: Bounds, ins: ScreenInsets, older: Boolean, distancePx: Int, maxSegment: Double = MAX_SEGMENT): List<Swipe> {
        val (top, bottom) = window(list, ins)
        val usable = bottom - top
        if (usable < 100) return emptyList()
        val x = x(list, ins)
        // Older messages: start only at 25 percent of the window height, so the finger never lands just below the notification zone.
        // The path downward is then shorter; if needed, the plan splits into two swipes.
        val startOffset = if (older) (usable * OLDER_START_OFFSET).toInt() else 0
        val room = usable - startOffset
        val segMax = minOf((usable * maxSegment).toInt(), room).coerceAtLeast(50)
        val total = distancePx.coerceIn(1, minOf((usable * 1.9).toInt(), 2 * segMax)) // at most two swipes
        val n = (total + segMax - 1) / segMax
        val seg = (total / n).coerceAtLeast(1)
        return List(n) { if (older) Swipe(x, top + startOffset, top + startOffset + seg) else Swipe(x, bottom, bottom - seg) }
    }
}
