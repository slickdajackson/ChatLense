package app.chatlens.agent

import app.chatlens.data.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Probe for the foreground check. On the device it is fed by the accessibility service, in tests a fake. */
interface ForegroundProbe {
    /** Package of the root of the active window (rootInActiveWindow), null when there is none. This is authoritative. */
    fun rootPackage(): String?

    /** Package of the last window-change event (only events of the allowed packages are delivered) and its age. */
    fun eventPackage(): String?
    fun eventAgeMs(): Long

    /** Class name (activity) of the last window-change event, for the log only. */
    fun activity(): String?

    /** Short overview of the windows (type, layer, and package only; no contents), for the log only. */
    fun windowsSummary(): String

    /** Brings the target app to the front via intent (NEW_TASK + REORDER_TO_FRONT). true when the launch was started. */
    fun launch(): Boolean

    /** Closes the notification shade or the control center (DISMISS_NOTIFICATION_SHADE, then back). */
    fun dismissSystemUi(): Boolean = false

    /** Last resort when the system UI stays stuck: the home button. */
    fun goHome(): Boolean = false
}

/**
 * Before every read, click, or back press, makes sure the target app (WhatsApp) is really in front.
 * If a foreign window is active, the app is brought to the front via intent at most [maxAttempts] times; after that the run
 * aborts with a clear message. Nothing is ever clicked in a foreign window: this guard does not click anything.
 */
class ForegroundGuard(
    private val probe: ForegroundProbe,
    val expectedPackage: String,
    private val ownPackage: String = "app.chatlens",
    private val log: (String) -> Unit = { AppLog.i("FG: $it") },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val maxAttempts: Int = 3,
    private val settleMs: Long = 3_000,
    private val stepMs: Long = 300,
) {
    private var lastDescribed = ""

    /** Foreign packages that appeared as the active window during the current check (in order), for a precise error message. */
    private val seenForeign = LinkedHashSet<String>()

    /** Total number of intent launches (for tests and the log). */
    var launches = 0
        private set

    fun activity(): String? = probe.activity()
    fun eventAgeMs(): Long = probe.eventAgeMs()

    /** Synchronous instant check with no waiting. true only when the root and a (fresh) event both match the target app. */
    fun isForeground(): Boolean {
        if (probe.rootPackage() != expectedPackage) return false
        val ev = probe.eventPackage()
        if (ev != null && ev != expectedPackage && ev != ownPackage && !ignorable(ev) && probe.eventAgeMs() < EVENT_FRESH_MS) return false
        return true
    }

    private fun ignorable(pkg: String) =
        pkg == "android" || pkg == "com.android.systemui" || pkg.contains("inputmethod", true) || pkg.contains("keyboard", true)

    fun describe(): String {
        val root = probe.rootPackage() ?: "keines"
        val act = probe.activity() ?: "unbekannt"
        val ev = probe.eventPackage() ?: "keines"
        return "aktives Paket $root, Ereignis-Paket $ev, Aktivitaet $act"
    }

    /** Writes the description to the log when it has changed (or when [force] is set). */
    fun logState(tag: String, force: Boolean = false) {
        val d = describe()
        if (force || d != lastDescribed) {
            lastDescribed = d
            log("$tag: $d")
        }
    }

    /**
     * Waits briefly for the foreground and otherwise brings the app to the front via intent up to [maxAttempts] times ([allowLaunch]).
     * Throws [AgentException] with a clear message if that fails.
     */
    suspend fun ensure(allowLaunch: Boolean = true, what: String = "") {
        if (isForeground()) {
            logState("Vordergrund")
            return
        }
        logState("Vordergrund", force = true)
        seenForeign.clear()
        probe.rootPackage()?.let { seenForeign.add(it) }
        // Wait out the transition (for example a window change still in progress)
        if (waitUntilForeground(800)) {
            logState("Vordergrund")
            return
        }
        probe.rootPackage()?.let { seenForeign.add(it) }
        if (!allowLaunch) {
            // Mode "Chat schon geoeffnet": WhatsApp is never brought forward via intent. If the system UI (shade, lock screen,
            // control center) is in front of it, that is closed first (shade dismiss and back, only while systemui is in front), then the run continues.
            val cur = probe.rootPackage()
            if (isSystemUi(cur)) {
                val d = probe.dismissSystemUi()
                log("Systemoberflaeche $cur vorn (Modus Chat schon geoeffnet): schliessen ausgefuehrt=$d. Fenster: ${probe.windowsSummary()}.")
                sleep(600)
                if (waitUntilForeground(1_200)) {
                    log("Ziel-App ist nach dem Schliessen der Systemoberflaeche wieder vorn.")
                    return
                }
                probe.rootPackage()?.let { seenForeign.add(it) }
            }
            throw NavigationException(foreignMessage(false, 0, what))
        }
        for (attempt in 1..maxAttempts) {
            coroutineContext.ensureActive()
            log("Fremdes Fenster: ${describe()}. Fenster: ${probe.windowsSummary()}. Hole ${expectedPackage} nach vorn, Versuch $attempt von $maxAttempts.")
            val cur = probe.rootPackage()
            if (isSystemSurface(cur)) {
                // The notification shade, control center, or launcher is on top: close it first, or it swallows the launch.
                val d = probe.dismissSystemUi()
                log("Systemoberflaeche $cur vorn: schliessen (Schatten-Dismiss und Zurueck) ausgefuehrt=$d.")
                sleep(500)
                if (isForeground()) {
                    log("Ziel-App ist nach dem Schliessen wieder vorn.")
                    return
                }
                if (attempt == maxAttempts && isSystemSurface(probe.rootPackage())) {
                    log("Systemoberflaeche haengt weiter: letzter Ausweg Home-Taste ausgefuehrt=${probe.goHome()}.")
                    sleep(600)
                }
            }
            val started = probe.launch()
            launches++
            if (!started) log("Intent-Start nicht moeglich (kein Startintent oder vom System abgelehnt).")
            if (waitUntilForeground(settleMs)) {
                log("Ziel-App ist wieder vorn nach Versuch $attempt.")
                logState("Vordergrund", force = true)
                return
            }
        }
        throw NavigationException(foreignMessage(true, maxAttempts, what))
    }

    /** Only the system UI itself (notification shade, control center, lock screen), not the launcher. */
    fun isSystemUi(pkg: String?): Boolean = pkg != null && (pkg == "com.android.systemui" || pkg.endsWith(".systemui"))

    /** Packages of the system UI and launchers that can end up in front after an unlucky swipe. */
    fun isSystemSurface(pkg: String?): Boolean =
        pkg != null && (pkg == "com.android.systemui" || pkg.endsWith(".systemui") || pkg == "com.miui.home" || pkg.contains("launcher", true) || pkg == "com.android.settings.intelligence")

    /**
     * After a gesture: if a foreign window is in front, the trigger ([trigger], for example "Wisch von (x,y) nach (x,y)") is logged with the package
     * and the window list, the system UI is closed, and WhatsApp is brought back ([ensure]). No click.
     */
    suspend fun recoverAfter(trigger: String, allowLaunch: Boolean = true): Boolean {
        if (isForeground()) return false
        if (waitUntilForeground(500)) return false
        log("AUSLOESER: Nach $trigger ist ${describe()} vorn. Fenster: ${probe.windowsSummary()}.")
        ensure(allowLaunch, "Nach $trigger")
        return true
    }

    private suspend fun waitUntilForeground(totalMs: Long): Boolean {
        val end = now() + totalMs
        while (true) {
            coroutineContext.ensureActive()
            if (isForeground()) return true
            if (now() >= end) return false
            sleep(stepMs)
        }
    }

    private fun foreignMessage(launched: Boolean, attempts: Int, what: String): String {
        val pkg = probe.rootPackage()
        fun name(p: String) = when {
            p == ownPackage -> "ChatLens selbst ($p)"
            isSystemUi(p) -> "die Systemoberflaeche ($p: Benachrichtigungsleiste, Sperrbildschirm oder Kontrollzentrum)"
            p == "com.whatsapp.w4b" && expectedPackage == "com.whatsapp" -> "WhatsApp Business (com.whatsapp.w4b), ChatLens ist auf com.whatsapp eingestellt"
            else -> "ein anderes Fenster (Paket $p)"
        }
        // A different window may have been in front earlier than the one now (for example the lock screen, then ChatLens): name both, the trigger first
        val first = seenForeign.firstOrNull()
        val who = when {
            first != null && pkg != null && first != pkg -> "zuerst ${name(first)}, zuletzt ${name(pkg)}"
            pkg == null && first != null -> name(first)
            pkg == null -> "kein lesbares Fenster"
            else -> name(pkg)
        }
        val prefix = if (what.isBlank()) "" else "$what: "
        val tail = if (launched) {
            "WhatsApp liess sich in $attempts Versuchen nicht in den Vordergrund holen. "
        } else {
            "Im Modus \"Chat schon geoeffnet\" holt ChatLens WhatsApp nie selbst nach vorn. "
        }
        return prefix + "Im Vordergrund ist $who statt WhatsApp. " + tail +
            "Es wurde nichts angetippt und nichts gelesen. Bitte WhatsApp selbst oeffnen (Chatliste) und den Lauf neu starten."
    }

    companion object {
        /** An event from another app counts as contrary evidence only for this long (after that, the root alone decides). */
        const val EVENT_FRESH_MS = 1_500L
    }
}
