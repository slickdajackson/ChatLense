package app.chatlens.agent

import app.chatlens.data.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Messpunkt fuer die Vordergrundpruefung. Auf dem Geraet vom Bedienungshilfe-Dienst gespeist, in Tests ein Fake. */
interface ForegroundProbe {
    /** Paket der Wurzel des aktiven Fensters (rootInActiveWindow), null wenn es keine gibt. Massgeblich. */
    fun rootPackage(): String?

    /** Paket des letzten Fensterwechsel-Ereignisses (nur Ereignisse der erlaubten Pakete werden geliefert) und dessen Alter. */
    fun eventPackage(): String?
    fun eventAgeMs(): Long

    /** Klassenname (Aktivitaet) des letzten Fensterwechsel-Ereignisses, nur fuer das Log. */
    fun activity(): String?

    /** Kurzuebersicht der Fenster (nur Typ, Ebene, Paket; keine Inhalte), nur fuer das Log. */
    fun windowsSummary(): String

    /** Holt die Ziel-App per Intent nach vorn (NEW_TASK + REORDER_TO_FRONT). true, wenn der Start angestossen wurde. */
    fun launch(): Boolean

    /** Schliesst Benachrichtigungsleiste oder Kontrollzentrum (DISMISS_NOTIFICATION_SHADE, dann Zurueck). */
    fun dismissSystemUi(): Boolean = false

    /** Letzter Ausweg bei festhaengender Systemoberflaeche: Home-Taste. */
    fun goHome(): Boolean = false
}

/**
 * Stellt vor jedem Lesen, Klicken oder Zurueck sicher, dass wirklich die Ziel-App (WhatsApp) vorn ist.
 * Ist ein fremdes Fenster aktiv, wird die App hoechstens [maxAttempts] mal per Intent nach vorn geholt; danach bricht der Lauf
 * mit einer klaren Meldung ab. Es wird nie in ein fremdes Fenster geklickt: Dieser Waechter klickt gar nichts.
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

    /** Fremde Pakete, die waehrend der laufenden Pruefung als aktives Fenster auftraten (in Reihenfolge), fuer eine genaue Fehlermeldung. */
    private val seenForeign = LinkedHashSet<String>()

    /** Anzahl der Intent-Starts insgesamt (fuer Tests und Log). */
    var launches = 0
        private set

    fun activity(): String? = probe.activity()
    fun eventAgeMs(): Long = probe.eventAgeMs()

    /** Synchrone Momentpruefung ohne Warten. true nur, wenn Wurzel und (frisches) Ereignis zur Ziel-App passen. */
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

    /** Schreibt die Beschreibung ins Log, wenn sie sich geaendert hat (oder [force]). */
    fun logState(tag: String, force: Boolean = false) {
        val d = describe()
        if (force || d != lastDescribed) {
            lastDescribed = d
            log("$tag: $d")
        }
    }

    /**
     * Wartet kurz auf den Vordergrund und holt die App sonst bis zu [maxAttempts] mal per Intent nach vorn ([allowLaunch]).
     * Wirft [AgentException] mit klarer Meldung, wenn das nicht gelingt.
     */
    suspend fun ensure(allowLaunch: Boolean = true, what: String = "") {
        if (isForeground()) {
            logState("Vordergrund")
            return
        }
        logState("Vordergrund", force = true)
        seenForeign.clear()
        probe.rootPackage()?.let { seenForeign.add(it) }
        // Uebergang abwarten (z. B. Fensterwechsel gerade im Gange)
        if (waitUntilForeground(800)) {
            logState("Vordergrund")
            return
        }
        probe.rootPackage()?.let { seenForeign.add(it) }
        if (!allowLaunch) {
            // Modus "Chat schon geoeffnet": WhatsApp wird nie per Intent geholt. Liegt aber die Systemoberflaeche (Leiste, Sperrbildschirm,
            // Kontrollzentrum) davor, wird sie zuerst geschlossen (Schatten-Dismiss und Zurueck, nur bei systemui vorn), dann geht es weiter.
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
                // Benachrichtigungsleiste, Kontrollzentrum oder Launcher liegen oben: erst schliessen, sonst verschluckt sie den Start.
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

    /** Nur die Systemoberflaeche selbst (Benachrichtigungsleiste, Kontrollzentrum, Sperrbildschirm), nicht der Launcher. */
    fun isSystemUi(pkg: String?): Boolean = pkg != null && (pkg == "com.android.systemui" || pkg.endsWith(".systemui"))

    /** Pakete von Systemoberflaeche und Launchern, die nach einem unguenstigen Wisch vorn sein koennen. */
    fun isSystemSurface(pkg: String?): Boolean =
        pkg != null && (pkg == "com.android.systemui" || pkg.endsWith(".systemui") || pkg == "com.miui.home" || pkg.contains("launcher", true) || pkg == "com.android.settings.intelligence")

    /**
     * Nach einer Geste: Ist ein fremdes Fenster vorn, wird der Ausloeser ([trigger], z. B. "Wisch von (x,y) nach (x,y)") mit Paket
     * und Fensterliste geloggt, die Systemoberflaeche geschlossen und WhatsApp zurueckgeholt ([ensure]). Kein Klick.
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
        // Zuerst war moeglicherweise ein anderes Fenster vorn als jetzt (z. B. Sperrbildschirm, danach ChatLens): beide nennen, den Ausloeser zuerst
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
        /** Ein Ereignis einer anderen App gilt nur so lange als Gegenbeweis (danach entscheidet allein die Wurzel). */
        const val EVENT_FRESH_MS = 1_500L
    }
}
