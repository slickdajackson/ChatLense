package app.chatlens.agent

import app.chatlens.core.Bounds
import app.chatlens.core.UiNode

/**
 * Interface to the device: read the tree and move the list. The real implementation is [AndroidScrollDevice];
 * tests use a simulated device (with fling, overshoot, and page jumps).
 */
interface ScrollDevice {
    fun snapshot(): UiNode?

    /** true: scrolling backward (toward older messages) is still possible. false: no longer. null: unknown. */
    fun canScrollBack(list: Bounds?): Boolean?

    fun canScrollForward(list: Bounds?): Boolean?

    /** Swipe gesture with a planned path. [older]=true: finger moves down, older messages appear. Returns whether the gesture was performed. */
    suspend fun swipe(list: Bounds, older: Boolean, distancePx: Int, durationMs: Long, holdMs: Long): Boolean

    /** ACTION_SCROLL_BACKWARD (older=true) or ACTION_SCROLL_FORWARD (older=false): one page. */
    suspend fun action(list: Bounds, older: Boolean): Boolean
}
