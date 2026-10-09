package app.chatlens.assist

/** Audiodatei einer Sprachnachricht (Quelle noch offen, siehe PLAN.md Abschnitt 15, Sprachnachrichten). */
class AudioClip(val path: String?, val durationMs: Long?, val mime: String?, val bytes: ByteArray? = null)

class TranscriptResult(val text: String?, val language: String?, val note: String)

/**
 * Schnittstelle fuer die Transkription von Sprachnachrichten. Echte Umsetzung ab Version 0.2.6:
 * [app.chatlens.asr.ParakeetTranscriber] (Parakeet TDT 0.6B v3 ueber sherpa-onnx, siehe PLAN.md Abschnitt 20).
 */
interface Transcriber {
    val name: String
    val available: Boolean
    suspend fun transcribe(audio: AudioClip): TranscriptResult
}

/** Platzhalter: meldet "nicht verfuegbar" und liefert keinen Text. */
object NoopTranscriber : Transcriber {
    override val name: String = "keine Transkription"
    override val available: Boolean = false
    override suspend fun transcribe(audio: AudioClip): TranscriptResult =
        TranscriptResult(null, null, "Transkription nicht eingerichtet")
}
