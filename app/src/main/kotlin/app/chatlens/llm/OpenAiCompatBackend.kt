package app.chatlens.llm

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * OpenAI-kompatibler Chat-Completions-Endpunkt: POST {baseUrl}/chat/completions.
 * Base-URL, Key und Modellname kommen aus den Einstellungen; es gibt keine Voreinstellung fuer das Modell.
 */
class OpenAiCompatBackend(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val sendImages: Boolean,
    private val temperature: Double = 0.2,
) : LlmBackend {

    override val name: String = "API ($model)"
    override val sendsDataOffDevice: Boolean = true
    override val supportsImages: Boolean = sendImages

    val host: String get() = runCatching { URI(baseUrl).host }.getOrNull() ?: baseUrl

    override suspend fun generate(request: LlmRequest): LlmResult {
        if (baseUrl.isBlank()) throw LlmException("Base-URL fehlt (Einstellungen).")
        if (model.isBlank()) throw LlmException("Modellname fehlt (Einstellungen).")
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val body = buildBody(request)
        return withContext(Dispatchers.IO) { coroutineScope {
            val conn = (URI(url).toURL().openConnection() as HttpURLConnection)
            // Beobachter: trennt die Verbindung bei Abbruch (Notaus), weil Socket-IO nicht abbrechbar ist
            val watcher = launch(Dispatchers.IO) { try { awaitCancellation() } finally { conn.disconnect() } }
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 20_000
                conn.readTimeout = 180_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    throw LlmException("HTTP $code von ${host}: ${text.take(400)}")
                }
                parse(text)
            } catch (e: LlmException) {
                throw e
            } catch (e: Exception) {
                throw LlmException("API-Aufruf fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                watcher.cancel()
                conn.disconnect()
            }
        } }
    }

    private fun buildBody(r: LlmRequest): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", r.system))
        val user = JSONObject().put("role", "user")
        if (sendImages && r.images.isNotEmpty()) {
            val parts = JSONArray()
            parts.put(JSONObject().put("type", "text").put("text", r.user))
            for (img in r.images) {
                parts.put(JSONObject().put("type", "text").put("text", img.label + ":"))
                val b64 = Base64.encodeToString(img.jpeg, Base64.NO_WRAP)
                parts.put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")),
                )
            }
            user.put("content", parts)
        } else {
            user.put("content", r.user)
        }
        messages.put(user)
        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", temperature)
            .toString()
    }

    private fun parse(json: String): LlmResult {
        val o = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw LlmException("Antwort ist kein JSON: ${json.take(200)}")
        }
        val choices = o.optJSONArray("choices")
        if (choices == null || choices.length() == 0) throw LlmException("Antwort ohne choices: ${json.take(300)}")
        val msg = choices.getJSONObject(0).optJSONObject("message")
            ?: throw LlmException("Antwort ohne message: ${json.take(300)}")
        val content = msg.opt("content")
        val text = when (content) {
            is String -> content
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    if (part.optString("type") == "text") append(part.optString("text"))
                }
            }
            else -> ""
        }
        val usage = o.optJSONObject("usage")
        val info = buildString {
            append("Modell: ").append(o.optString("model", model))
            if (usage != null) {
                append("; Tokens Eingabe ").append(usage.optInt("prompt_tokens", -1))
                append(", Ausgabe ").append(usage.optInt("completion_tokens", -1))
            }
        }
        return LlmResult(text.trim(), info)
    }
}
