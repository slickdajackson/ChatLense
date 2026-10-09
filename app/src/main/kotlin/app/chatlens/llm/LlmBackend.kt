package app.chatlens.llm

/** Image for vision models (JPEG bytes). */
class LlmImage(val label: String, val jpeg: ByteArray)

class LlmRequest(
    val system: String,
    val user: String,
    val images: List<LlmImage> = emptyList(),
)

class LlmResult(val text: String, val info: String)

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Abstraction for all models. Implementations: OpenAiCompatBackend (API), LiteRtLmBackend (local). */
interface LlmBackend {
    /** Short name for logs and the UI. */
    val name: String

    /** Does the chat content leave the device? Shown in the UI. */
    val sendsDataOffDevice: Boolean

    val supportsImages: Boolean

    /** Does not block the main thread. Cancellable through coroutine cancellation. */
    suspend fun generate(request: LlmRequest): LlmResult
}
