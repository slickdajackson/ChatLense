package app.chatlens.asr

/**
 * Decision before the step "Sprachnachrichten transkribieren". Pure logic (JVM-testable).
 * The step runs only when the switch, the model, and the folder are in order and the chat has voice messages. Otherwise it is skipped cleanly,
 * and the display names the reason (and what to do) instead of quietly doing nothing.
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
