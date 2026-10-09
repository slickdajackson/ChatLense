package app.chatlens.asr

import app.chatlens.assist.AudioClip
import app.chatlens.assist.TranscriptResult
import app.chatlens.assist.Transcriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Transcriber for voice messages: bytes, then a decoder (Concentus first, then an optional fallback such as MediaCodec), then speech recognition.
 * The engine is created on the first call and released again with [close] (memory for the LLM).
 */
class ParakeetTranscriber(
    private val engineFactory: () -> AsrEngine,
    private val decoders: List<AudioDecoder> = listOf(ConcentusOpusDecoder),
) : Transcriber, AutoCloseable {
    private var engine: AsrEngine? = null
    override val name: String = "Parakeet TDT 0.6B v3 (lokal)"
    override val available: Boolean = true

    /** Last decoder used and duration of the last recognition, for the log. */
    @Volatile var lastDecoder: String = ""
    @Volatile var lastDecodeMs: Long = 0
    @Volatile var lastRecognizeMs: Long = 0
    @Volatile var lastAudioSec: Double = 0.0

    /** Model load time in milliseconds, 0 until it has been loaded. */
    @Volatile var loadMs: Long = 0

    fun decode(bytes: ByteArray): DecodedAudio {
        var last: Exception? = null
        val t0 = System.nanoTime()
        for (d in decoders) {
            try {
                val a = d.decode(bytes)
                lastDecoder = d.name
                lastDecodeMs = (System.nanoTime() - t0) / 1_000_000
                return a
            } catch (e: Exception) {
                last = e
            }
        }
        throw AudioDecodeException("Kein Dekoder konnte die Datei lesen: ${last?.message}", last)
    }

    fun recognize(a: DecodedAudio): String {
        val e = engine ?: run {
            val t = System.nanoTime()
            engineFactory().also { engine = it; loadMs = (System.nanoTime() - t) / 1_000_000 }
        }
        val t0 = System.nanoTime()
        val text = e.transcribe(a.samples, a.sampleRate)
        lastRecognizeMs = (System.nanoTime() - t0) / 1_000_000
        lastAudioSec = a.durationSec
        return text
    }

    override suspend fun transcribe(audio: AudioClip): TranscriptResult = withContext(Dispatchers.Default) {
        val bytes = audio.bytes ?: return@withContext TranscriptResult(null, null, "Keine Audiodaten")
        try {
            val a = decode(bytes)
            val text = recognize(a)
            if (text.isBlank()) TranscriptResult(null, null, "Kein Sprachtext erkannt") else TranscriptResult(text, null, "ok")
        } catch (e: AudioDecodeException) {
            TranscriptResult(null, null, "Audio nicht lesbar: ${e.message}")
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }
}
