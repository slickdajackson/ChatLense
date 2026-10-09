package app.chatlens.service

import java.util.concurrent.atomic.AtomicInteger

/** Zaehler fuer das Wachhalten des Bildschirms: verschachtelte Laeufe (Auto ruft ChatRunner) halten den Bildschirm bis zum letzten Ende. */
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
