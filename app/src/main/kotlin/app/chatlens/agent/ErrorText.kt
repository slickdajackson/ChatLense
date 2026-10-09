package app.chatlens.agent

/**
 * Readable error text for the UI (0.3.0, N3). The technical message stays in the log (AppLog), and no longer appears in the panel
 * or in the notification. Pure logic, no Android.
 */
object ErrorText {
    fun friendly(e: Throwable): String = when (e) {
        is OutOfMemoryError -> "Der Speicher reichte nicht aus. Im Tab Modelle oder in den Einstellungen eine kleinere Kontextstufe wählen."
        is SecurityException -> "Eine Berechtigung fehlt oder wurde entzogen. In den Einstellungen die Berechtigungen prüfen."
        is java.io.FileNotFoundException -> "Eine Datei wurde nicht gefunden. Modell oder Ordner neu wählen."
        is java.io.IOException -> "Ein Lese- oder Schreibfehler ist aufgetreten. Speicherplatz prüfen und erneut versuchen."
        is java.util.concurrent.TimeoutException -> "Die Antwort hat zu lange gedauert. Bitte erneut versuchen."
        is IllegalStateException -> "ChatLens war in einem unerwarteten Zustand. Bitte den Auftrag erneut starten."
        else -> "Unerwarteter Fehler. Bitte erneut versuchen. Das Protokoll im Tab Debug (Entwickleroption) enthält die Details."
    }
}
