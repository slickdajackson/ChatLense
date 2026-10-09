package app.chatlens.llm

import android.content.Context
import app.chatlens.data.AppSettings
import app.chatlens.data.BackendChoice

object LlmFactory {
    fun create(ctx: Context, s: AppSettings): LlmBackend = when (s.backend) {
        BackendChoice.API -> OpenAiCompatBackend(s.apiBaseUrl, s.apiKey, s.apiModel, s.apiVision && s.captureImages)
        BackendChoice.LOCAL -> LiteRtLmBackend(
            ctx, s.localModelPath, s.localAccel,
            vision = s.localVision && s.captureImages, maxNumTokens = s.localMaxTokens, maxImages = s.maxImages.coerceAtLeast(1),
        )
        BackendChoice.EXTRACT_ONLY -> throw LlmException("Kein Modell gewählt (Einstellungen: lokal oder API).")
    }
}
