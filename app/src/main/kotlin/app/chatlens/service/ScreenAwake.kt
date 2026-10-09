package app.chatlens.service

import java.util.concurrent.atomic.AtomicInteger

/** Counter for keeping the screen awake: nested runs (auto calls ChatRunner) keep the screen on until the last one ends. */
object ScreenAwake {
    private val users = AtomicInteger(0)

    fun acquire() {
        if (users.getAndIncrement() == 0) ChatAccessibilityService.instance?.setKeepScreenOn(true)
    }

    fun release() {
        val n = users.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (n == 0) ChatAccessibilityService.instance?.setKeepScreenOn(false)
    }

    fun active(): Int = users.get()
}
