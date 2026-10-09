package app.chatlens.assist

/** Audio file of a voice message (source still open, see PLAN.md section 15, voice messages). */
class AudioClip(val path: String?, val durationMs: Long?, val mime: String?, val bytes: ByteArray? = null)

class TranscriptResult(val text: String?, val language: String?, val note: String)

/**
 * Interface for transcribing voice messages. Real implementation from version 0.2.6:
 * [app.chatlens.asr.ParakeetTranscriber] (Parakeet TDT 0.6B v3 via sherpa-onnx, see PLAN.md section 20).
 */
interface Transcriber {
    val name: String
    val available: Boolean
    suspend fun transcribe(audio: AudioClip): TranscriptResult
}

/** Placeholder: reports that it is not available and returns no text. */
object NoopTranscriber : Transcriber {
    override val name: String = "keine Transkription"
    override val available: Boolean = false
    override suspend fun transcribe(audio: AudioClip): TranscriptResult =
        TranscriptResult(null, null, "Transkription nicht eingerichtet")
}
