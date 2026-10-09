package app.chatlens.asr

/**
 * Entscheidung vor dem Schritt "Sprachnachrichten transkribieren". Reine Logik (JVM-testbar).
 * Der Schritt laeuft nur, wenn Schalter, Modell und Ordner stimmen und der Chat Sprachnachrichten hat. Sonst wird er sauber uebersprungen,
 * und die Anzeige nennt den Grund (und was zu tun ist), statt still nichts zu tun.
 */
class VoiceDecision(val run: Boolean, val skipReason: String?, val todo: String?)

object VoicePlan {
    fun decide(enabled: Boolean, voices: Int, modelReady: Boolean, folderSet: Boolean, folderGranted: Boolean): VoiceDecision = when {
        !enabled -> VoiceDecision(false, if (voices > 0) "Sprachnachrichten nicht transkribiert: Schalter aus ($voices im Chat)." else null, if (voices > 0) "Einstellungen, Sprachnachrichten: Schalter einschalten." else null)
        voices == 0 -> VoiceDecision(false, null, null)
        !folderSet -> VoiceDecision(false, "Sprachnachrichten nicht transkribiert: Ordner wählen ($voices im Chat).", "Ordner wählen: Einstellungen, Sprachnachrichten, Ordner der Sprachnachrichten freigeben.")
        !folderGranted -> VoiceDecision(false, "Sprachnachrichten nicht transkribiert: Freigabe des Ordners verloren, Ordner neu wählen ($voices im Chat).", "Ordner neu wählen.")
        !modelReady -> VoiceDecision(false, "Sprachnachrichten nicht transkribiert: Parakeet-Modell fehlt oder lädt noch ($voices im Chat).", "Tab Modelle: Parakeet laden.")
        else -> VoiceDecision(true, null, null)
    }
}
