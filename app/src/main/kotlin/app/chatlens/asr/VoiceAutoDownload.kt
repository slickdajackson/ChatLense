package app.chatlens.asr

import app.chatlens.models.DlStatus

/**
 * When "Sprachnachrichten transkribieren" is turned on and Parakeet is not fully present and verified, the download starts by itself.
 * Default is Wi-Fi only. On mobile data (or an unknown network) it asks once. Pure logic, so testable without Android.
 */
object VoiceAutoDownload {
    enum class Action { NOTHING, ALREADY_RUNNING, START, ASK_METERED }

    /** [modelReady]: all files present and verified. [dl]: state of the current or last download. [unmetered]: true = Wi-Fi, false = mobile data, null = unknown. */
    fun decide(modelReady: Boolean, dl: DlStatus, unmetered: Boolean?): Action = when {
        modelReady -> Action.NOTHING
        dl == DlStatus.RUNNING || dl == DlStatus.VERIFYING -> Action.ALREADY_RUNNING
        unmetered == true -> Action.START
        else -> Action.ASK_METERED
    }

    fun sizeText(sizeBytes: Long): String = "${sizeBytes / 1_000_000L} MB"

    /** Short hint when turning it on. */
    fun hint(sizeBytes: Long, action: Action): String = when (action) {
        Action.START -> "Parakeet wird geladen (${sizeText(sizeBytes)}, WLAN erkannt). Die Transkription startet erst nach erfolgreicher Prüfung."
        Action.ASK_METERED -> "Parakeet braucht einmalig ${sizeText(sizeBytes)}. Empfehlung: WLAN."
        Action.ALREADY_RUNNING -> "Der Parakeet-Download läuft bereits."
        Action.NOTHING -> ""
    }

    /**
     * Carries out the decision. [start] receives whether mobile data is allowed. On ASK_METERED nothing happens until [confirmMetered] is called.
     * The log names only the size and the network type, no contents.
     */
    fun trigger(
        modelReady: Boolean, dl: DlStatus, unmetered: Boolean?, sizeBytes: Long,
        start: (meteredOk: Boolean) -> Unit, log: (String) -> Unit,
    ): Action {
        val a = decide(modelReady, dl, unmetered)
        if (a == Action.START) {
            log("STIMME: Download gestartet (Parakeet, ${sizeText(sizeBytes)}, WLAN)")
            start(false)
        } else if (a == Action.ASK_METERED) {
            log("STIMME: Download wartet auf Bestätigung (kein WLAN erkannt, ${sizeText(sizeBytes)})")
        }
        return a
    }

    fun confirmMetered(sizeBytes: Long, start: (meteredOk: Boolean) -> Unit, log: (String) -> Unit) {
        log("STIMME: Download gestartet (Parakeet, ${sizeText(sizeBytes)}, mobile Daten bestätigt)")
        start(true)
    }

    /** Error message with a retry hint. */
    fun failedText(message: String): String = "Download fehlgeschlagen: " + message.trim().ifEmpty { "unbekannter Fehler" } + " Mit Wiederholen erneut versuchen, Geladenes bleibt erhalten."
}
