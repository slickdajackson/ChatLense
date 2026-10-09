package app.chatlens.agent

import app.chatlens.core.Bounds

/** Swipe path in screen coordinates. */
data class Swipe(val x: Int, val fromY: Int, val toY: Int) {
    val distance: Int get() = kotlin.math.abs(toY - fromY)
}

/**
 * Plans the swipe path so the step size is at most [MAX_STEP] of the list height.
 * Consecutive pages therefore overlap by at least 30 percent of the list height, and a message
 * up to 30 percent of the list height was guaranteed to be fully visible at least once. Taller messages
 * (long texts, large images) can stay clipped and are then marked.
 * Note: the actual scroll distance is smaller than the swipe path by the system's touch slop (about 8 dp).
 */
object ScrollPlan {
    const val MAX_STEP = 0.70
    const val MIN_STEP = 0.30
    private const val MARGIN = 0.12
    private const val EDGE = 0.06

    /** Largest commanded swipe distance. The measured scroll distance is regulated separately to at most about 70 percent. */
    const val MAX_COMMAND = 0.85

    fun clampStep(fraction: Double): Double = fraction.coerceIn(MIN_STEP, MAX_STEP)

    /** Finger moves down: the content slides down, older messages appear at the top. */
    fun older(list: Bounds, fraction: Double): Swipe {
        val h = list.height
        val from = list.t + (h * MARGIN).toInt()
        val to = from + (h * clampStep(fraction)).toInt()
        return Swipe(list.centerX, from, to)
    }

    /** Swipe path with the requested distance in pixels; the distance is capped at 85 percent of the list height, and the path stays inside the list. */
    fun olderPx(list: Bounds, distancePx: Int): Swipe {
        val h = list.height
        val d = distancePx.coerceIn(1, (h * MAX_COMMAND).toInt())
        val from = list.t + (h * EDGE).toInt()
        return Swipe(list.centerX, from, from + d)
    }

    fun newerPx(list: Bounds, distancePx: Int): Swipe {
        val h = list.height
        val d = distancePx.coerceIn(1, (h * MAX_COMMAND).toInt())
        val from = list.b - (h * EDGE).toInt()
        return Swipe(list.centerX, from, from - d)
    }

    /** Finger moves up: newer messages appear again (step back). */
    fun newer(list: Bounds, fraction: Double): Swipe {
        val h = list.height
        val from = list.b - (h * MARGIN).toInt()
        val to = from - (h * clampStep(fraction)).toInt()
        return Swipe(list.centerX, from, to)
    }
}

/**
 * Stop rule for scrolling backward.
 * A failed attempt is a scroll with no new messages. The run stops only after [failuresNeeded] failed attempts
 * in a row AND a real start-of-chat signal (encryption notice, scrolling no longer possible,
 * or an unchanged tree after a long wait). Without a signal, "stuck" applies only after [hardFailures] failed attempts.
 */
class ScrollStopPolicy(private val failuresNeeded: Int = 3, private val hardFailures: Int = 6) {
    enum class Decision { CONTINUE, CHAT_START, STUCK }

    var failures = 0
        private set

    fun record(newContent: Boolean) {
        if (newContent) failures = 0 else failures++
    }

    /** From the third failed attempt on, a long wait is worth it, so "unchanged" can be established with confidence. */
    fun wantsLongWait(): Boolean = failures >= failuresNeeded

    fun decide(startSignal: Boolean): Decision = when {
        failures >= failuresNeeded && startSignal -> Decision.CHAT_START
        failures >= hardFailures -> Decision.STUCK
        else -> Decision.CONTINUE
    }
}
