package app.chatlens.llm

import android.content.Context
import app.chatlens.data.AppLog
import app.chatlens.data.LocalAccel
import app.chatlens.models.ModelAdvisor
import app.chatlens.models.ModelCatalog
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lokales Modell ueber LiteRT-LM (Google AI Edge), Kotlin-API com.google.ai.edge.litertlm.
 * Vorgesehen: Gemma 4 E4B (litert-community/gemma-4-E4B-it-litert-lm, Datei gemma-4-E4B-it.litertlm).
 * Spaeterer Benchmark-Punkt: Qwen3-0.6B (litert-community/Qwen3-0.6B). Beide sind dieselbe Schnittstelle.
 *
 * STATUS: Kompiliert gegen litertlm-android:0.17.1. Auf einem echten Geraet NICHT ausgefuehrt.
 * Bildeingabe: wird nur gesendet, wenn [vision] aktiv ist und die Modelldatei Vision-Teile enthaelt
 * (Header der Datei gemma-4-E4B-it.litertlm nennt tf_lite_vision_encoder und tf_lite_vision_adapter).
 * Der Engine bleibt zwischen Laeufen geladen, bis [release] aufgerufen wird.
 */
class LiteRtLmBackend(
    private val context: Context,
    private val modelPath: String,
    private val accel: LocalAccel,
    private val vision: Boolean,
    /** 0 = automatisch (groesste Stufe, die das Telefon schafft), sonst die gewaehlte Obergrenze. Siehe [ContextPlanner]. */
    private val maxNumTokens: Int,
    private val maxImages: Int,
) : LlmBackend {

    override val name: String = "Lokal LiteRT-LM (${File(modelPath).name}, $accel${if (vision) ", Vision" else ""})"
    override val sendsDataOffDevice: Boolean = false
    override val supportsImages: Boolean = vision

    /** Qwen3 kennt den Soft-Switch /no_think; Gemma braucht ihn nicht, Qwen3.5 hat ihn nicht (Denken ist dort im Modell abgeschaltet). */
    val wantsNoThinkSuffix: Boolean get() = noThinkSuffixFor(File(modelPath).name)

    override suspend fun generate(request: LlmRequest): LlmResult {
        if (modelPath.isBlank() || !File(modelPath).isFile) {
            throw LlmException("Lokale Modelldatei fehlt. In den Einstellungen eine .litertlm-Datei waehlen.")
        }
        return withContext(Dispatchers.Default) {
            coroutineScope {
                val engine = getEngine()
                val conv = try {
                    engine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(request.system),
                            thinkingConfig = ThinkingConfig(enableThinking = false),
                        ),
                    )
                } catch (e: Throwable) {
                    throw LlmException("Konversation konnte nicht erstellt werden: ${e.message}", e)
                }
                val watcher = launch(Dispatchers.IO) {
                    try { awaitCancellation() } finally { runCatching { conv.cancelProcess() } }
                }
                try {
                    val t0 = System.currentTimeMillis()
                    val contents = if (vision && request.images.isNotEmpty()) {
                        val parts = ArrayList<Content>()
                        for (img in request.images.take(maxImages)) {
                            parts.add(Content.Text(img.label + ":"))
                            parts.add(Content.ImageBytes(img.jpeg))
                        }
                        parts.add(Content.Text(request.user))
                        Contents.of(parts)
                    } else {
                        Contents.of(request.user)
                    }
                    val reply = conv.sendMessage(contents).toString()
                    val dt = System.currentTimeMillis() - t0
                    LlmResult(stripThink(reply), "Lokal, ${"%.1f".format(dt / 1000.0)} s, ${File(modelPath).name}")
                } catch (e: Throwable) {
                    val lvl = activeLevel
                    if (lvl > ContextPlanner.LEVELS.last() && Regex("(?i)memory|alloc|oom|out of").containsMatchIn(e.javaClass.simpleName + " " + e.message)) {
                        // Speichermangel bei grosser Stufe: merken und freigeben, der naechste Lauf nimmt eine kleinere Stufe
                        val rem = ContextPlanner.afterFailure(RememberedLevel.decode(prefs().getString(levelKey, null)), lvl, System.currentTimeMillis())
                        prefs().edit().putString(levelKey, rem.encode()).apply()
                        AppLog.w("KONTEXT: Speichermangel bei Stufe $lvl Token in der Inferenz, die Stufe wird fuer den naechsten Lauf gesenkt.")
                        release()
                        throw LlmException("Speichermangel bei Kontextstufe $lvl Token. Der naechste Lauf nutzt automatisch eine kleinere Stufe.", e)
                    }
                    throw LlmException("Lokale Inferenz fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message}", e)
                } finally {
                    watcher.cancel()
                    runCatching { conv.close() }
                }
            }
        }
    }

    /** Laedt das Modell (falls noetig) und liefert die tatsaechlich genutzte Kontextstufe in Token. Aufrufer: vor dem Bau des Prompts. */
    suspend fun ensureLoaded(): Int = withContext(Dispatchers.Default) { getEngine(); activeLevel }

    private fun prefs() = context.getSharedPreferences("ctx_levels", Context.MODE_PRIVATE)
    private val levelKey: String get() = "${File(modelPath).name}|$accel"

    private fun planLevels(now: Long): ContextPlan {
        val name = File(modelPath).name
        val entry = runCatching { ModelCatalog.load(context).models.firstOrNull { it.file == name } }.getOrNull()
        val modelCtx = entry?.contextTokens ?: (if (maxNumTokens > 0) maxNumTokens else 8192)
        val base = entry?.let { ModelAdvisor.planRamMb(it, accel == LocalAccel.GPU).first } ?: (File(modelPath).length() / 1_000_000L * 12 / 10 + 500).toInt()
        val mi = android.app.ActivityManager.MemoryInfo()
        val am = runCatching { context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager }.getOrNull()
        val known = am != null && runCatching { am.getMemoryInfo(mi) }.isSuccess && mi.totalMem > 0
        val avail = if (known) mi.availMem / 1_000_000L else -1L
        val total = if (known) mi.totalMem / 1_000_000L else 0L
        val rem = RememberedLevel.decode(prefs().getString(levelKey, null))
        val plan = ContextPlanner.plan(modelCtx, maxNumTokens, base, avail, total, rem, now)
        AppLog.i("KONTEXT: Plan ${plan.candidates.joinToString(" > ")} Token (${plan.reason}); freier RAM ${if (known) "$avail von $total MB" else "unbekannt"}, Modellbasis etwa $base MB, KV-Annahme ${ContextPlanner.KV_MB_PER_TOKEN} MB je Token (Schaetzung).")
        return plan
    }

    private val engineKey: String get() = "$modelPath|$accel|$maxNumTokens|$vision|$maxImages"

    /** Wird das Modell fuer den naechsten Aufruf erst geladen (Fortschrittsanzeige "Modell laden")? */
    fun needsLoad(): Boolean = synchronized(LiteRtLmBackend::class.java) {
        !(cached != null && cachedKey == engineKey && cached!!.isInitialized())
    }

    private fun getEngine(): Engine {
        synchronized(LiteRtLmBackend::class.java) {
            val key = engineKey
            if (cached != null && cachedKey == key && cached!!.isInitialized()) return cached!!
            release()
            AppLog.i("Lade lokales Modell (kann laut Google-Doku bis zu ca. 10 s dauern, bei grossen Modellen laenger) ...")
            val be = if (accel == LocalAccel.GPU) Backend.GPU() else Backend.CPU()
            val now = System.currentTimeMillis()
            val plan = planLevels(now)
            var rem = RememberedLevel.decode(prefs().getString(levelKey, null))
            val top = plan.candidates.first()
            var lastErr: Throwable? = null
            for ((i, level) in plan.candidates.withIndex()) {
                val cfg = EngineConfig(
                    modelPath = modelPath,
                    backend = be,
                    visionBackend = if (vision) be else null,
                    maxNumTokens = level,
                    maxNumImages = if (vision) maxImages else null,
                    cacheDir = context.cacheDir.path,
                )
                AppLog.i("KONTEXT: versuche Stufe $level Token (${i + 1} von ${plan.candidates.size}).")
                var e: Engine? = null
                try {
                    e = Engine(cfg)
                    loadListener?.invoke(true)
                    e.initialize()
                    loadListener?.invoke(false)
                    cached = e
                    cachedKey = key
                    activeLevel = level
                    activeNote = (if (maxNumTokens > 0) "manuell" else "automatisch") + (if (level < top) ", Rueckfall von $top" else "")
                    rem = ContextPlanner.afterSuccess(rem, level, top, now)
                    prefs().edit().putString(levelKey, rem.encode()).apply()
                    AppLog.i("Lokales Modell geladen. KONTEXT: genutzte Stufe $level Token ($activeNote).")
                    return e
                } catch (t: Throwable) {
                    runCatching { e?.close() }
                    loadListener?.invoke(false)
                    lastErr = t
                    rem = ContextPlanner.afterFailure(rem, level, now)
                    prefs().edit().putString(levelKey, rem.encode()).apply()
                    val next = plan.candidates.getOrNull(i + 1)
                    AppLog.w("KONTEXT: Stufe $level Token fehlgeschlagen (${t.javaClass.simpleName}: ${t.message?.take(160)}). " + (next?.let { "Rueckfall auf $it Token." } ?: "Keine kleinere Stufe."))
                }
            }
            activeLevel = 0
            throw LlmException("Modell konnte nicht geladen werden (Stufen ${plan.candidates.joinToString(", ")} Token versucht): ${lastErr?.javaClass?.simpleName}: ${lastErr?.message}", lastErr)
        }
    }

    /** Entfernt eventuelle Denkbloecke. */
    private fun stripThink(s: String): String = s.replace(Regex("(?s)<think>.*?</think>"), "").trim()

    companion object {
        /** Meldet Beginn (true) und Ende (false) des Modellladens an die Fortschrittsanzeige. */
        @Volatile var loadListener: ((Boolean) -> Unit)? = null

        /** Tatsaechlich genutzte Kontextstufe in Token (0 = nicht geladen) und wie sie zustande kam. Fuer Status und Markdown-Log. */
        @Volatile var activeLevel: Int = 0
        @Volatile var activeNote: String = ""

        fun contextInfo(): String = if (activeLevel > 0) "$activeLevel Token ($activeNote)" else "noch nicht geladen"

        private var cached: Engine? = null
        private var cachedKey: String? = null

        fun release() {
            synchronized(LiteRtLmBackend::class.java) {
                runCatching { cached?.close() }
                cached = null
                cachedKey = null
                activeLevel = 0
            }
        }
    }
}
