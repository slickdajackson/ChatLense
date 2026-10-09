package app.chatlens.asr

import app.chatlens.models.DlStatus

/**
 * Wird "Sprachnachrichten transkribieren" eingeschaltet und Parakeet ist nicht vollstaendig und geprueft vorhanden, startet der Download von selbst.
 * Standard nur im WLAN; bei mobilen Daten (oder unbekanntem Netz) wird einmal nachgefragt. Reine Logik, deshalb ohne Android testbar.
 */
object VoiceAutoDownload {
    enum class Action { NOTHING, ALREADY_RUNNING, START, ASK_METERED }

    /** [modelReady]: alle Dateien da und geprueft. [dl]: Zustand des laufenden oder letzten Downloads. [unmetered]: true = WLAN, false = Mobilfunk, null = unbekannt. */
    fun decide(modelReady: Boolean, dl: DlStatus, unmetered: Boolean?): Action = when {
        modelReady -> Action.NOTHING
        dl == DlStatus.RUNNING || dl == DlStatus.VERIFYING -> Action.ALREADY_RUNNING
        unmetered == true -> Action.START
        else -> Action.ASK_METERED
    }

    fun sizeText(sizeBytes: Long): String = "${sizeBytes / 1_000_000L} MB"

    /** Kurzer Hinweis beim Einschalten. */
    fun hint(sizeBytes: Long, action: Action): String = when (action) {
        Action.START -> "Parakeet wird geladen (${sizeText(sizeBytes)}, WLAN erkannt). Die Transkription startet erst nach erfolgreicher Prüfung."
        Action.ASK_METERED -> "Parakeet braucht einmalig ${sizeText(sizeBytes)}. Empfehlung: WLAN."
        Action.ALREADY_RUNNING -> "Der Parakeet-Download läuft bereits."
        Action.NOTHING -> ""
    }

    /**
     * Fuehrt die Entscheidung aus. [start] bekommt "mobile Daten erlaubt". Bei ASK_METERED passiert nichts, bis [confirmMetered] aufgerufen wird.
     * Das Log nennt nur Groesse und Netzart, keine Inhalte.
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

    /** Fehlermeldung mit Wiederholen-Hinweis. */
    fun failedText(message: String): String = "Download fehlgeschlagen: " + message.trim().ifEmpty { "unbekannter Fehler" } + " Mit Wiederholen erneut versuchen, Geladenes bleibt erhalten."
}
