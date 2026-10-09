package app.chatlens.llm

/** Bild fuer Vision-Modelle (JPEG-Bytes). */
class LlmImage(val label: String, val jpeg: ByteArray)

class LlmRequest(
    val system: String,
    val user: String,
    val images: List<LlmImage> = emptyList(),
)

class LlmResult(val text: String, val info: String)

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Abstraktion fuer alle Modelle. Implementierungen: OpenAiCompatBackend (API), LiteRtLmBackend (lokal). */
interface LlmBackend {
    /** Kurzname fuer Logs und UI. */
    val name: String

    /** Verlaesst der Chatinhalt das Geraet? Wird in der UI angezeigt. */
    val sendsDataOffDevice: Boolean

    val supportsImages: Boolean

    /** Blockiert nicht den Main-Thread; abbrechbar durch Coroutine-Cancellation. */
    suspend fun generate(request: LlmRequest): LlmResult
}
