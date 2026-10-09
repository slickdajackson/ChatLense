package app.chatlens.data

import app.chatlens.llm.ContextPlanner
import android.content.Context
import android.content.SharedPreferences
import app.chatlens.core.StopMode

enum class BackendChoice { API, LOCAL, EXTRACT_ONLY }

/**
 * Release policy (from 0.3.0): the Play build has no API mode ([app.chatlens.BuildConfig.API_MODE] is false there).
 * A stored API mode becomes "Nur Auslesen" on load, so chat content never goes to a server.
 */
object BackendPolicy {
    val apiAllowed: Boolean get() = app.chatlens.BuildConfig.API_MODE
    fun effective(c: BackendChoice, allowed: Boolean = apiAllowed): BackendChoice = if (c == BackendChoice.API && !allowed) BackendChoice.EXTRACT_ONLY else c
    /** Experimental sending (send button) exists only in the full flavor. The Play flavor never performs a send action. */
    val sendAllowed: Boolean get() = app.chatlens.BuildConfig.API_MODE
    fun choices(allowed: Boolean = apiAllowed): List<BackendChoice> = BackendChoice.entries.filter { allowed || it != BackendChoice.API }
}

enum class LocalAccel { CPU, GPU }

/**
 * AUTO: controlled swipe; on repeated loss of overlap, precise; when it has no effect, ACTION_SCROLL_BACKWARD.
 * SWIPE: controlled swipe only. ACTION: ACTION_SCROLL_BACKWARD only. GENAU: micro-swipes only (slow, without gaps).
 */
enum class ScrollMethod { AUTO, SWIPE, ACTION, GENAU }

data class AppSettings(
    val backend: BackendChoice = BackendChoice.EXTRACT_ONLY,
    val apiBaseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val apiModel: String = "",
    val apiVision: Boolean = false,
    val localModelPath: String = "",
    val localAccel: LocalAccel = LocalAccel.CPU,
    val localVision: Boolean = true,
    /** Context level of the local model in tokens. 0 = "automatisch" maximum (default from 0.2.9), otherwise a manual cap of 4096, 8192, 16384, or 32768. */
    val localMaxTokens: Int = 0,
    val captureImages: Boolean = true,
    val ocrImages: Boolean = true,
    val maxImages: Int = 6,
    val groupChat: Boolean = false,
    /** Extra pause after every scroll. Default 0 = as fast as possible, no random pauses. */
    val pauseMinMs: Int = 0,
    val pauseMaxMs: Int = 0,
    val scrollMethod: ScrollMethod = ScrollMethod.AUTO,
    /** Swipe step size in percent of the list height, at most 70, so messages overlap. */
    val scrollStepPercent: Int = 60,
    /** Duration of the swipe. Long and steady, so no fling builds up. */
    val swipeDurMs: Int = 450,
    /** Finger hold time at the end of the movement. */
    val holdMs: Int = 80,
    /** Adjust the swipe distance from the measured scroll travel (self-calibration). Off: fixed step size. */
    val selfCalibrate: Boolean = true,
    /** Desired overlap of two pages in percent of the list height (30 to 50). */
    val targetOverlapPercent: Int = 35,
    /** EXPERIMENTAL, off by default: allows a send button in the app (only with confirmation per text). Can violate the WhatsApp terms. */
    val experimentalSend: Boolean = false,
    /** Show the floating dot (overlay) when the app starts. */
    val overlayEnabled: Boolean = false,
    /** Checkup when the app starts (once per process start, at most every 10 minutes) and the count X of chats read. */
    val checkupOnStart: Boolean = false, // off from 0.2.9; also runs only after the wizard is finished
    /** Setup wizard: current page (resumable), finished, skip chosen. */
    val wizardStep: String = "WELCOME",
    val wizardDone: Boolean = false,
    val wizardSkipped: Boolean = false,
    /** Explicit consent for the accessibility service (disclosure in the wizard, from 0.3.0). Evidence for the Play declaration. */
    val a11yConsent: Boolean = false,
    /** Notification permission was already requested (with a reason). */
    val notifAsked: Boolean = false,
    /** Developer option: shows the Debug tab (hidden from 0.3.0; tap the version seven times). */
    val developerMode: Boolean = false,
    /** Allowed messengers, ids separated by commas (default is whatsapp only). See MessengerRegistry. */
    val enabledMessengers: String = "whatsapp",
    /** Position of the floating dot: side (0 left, 1 right) and height fraction 0..1, remembered, and it snaps to the edge. */
    val dotSide: Int = 1,
    val dotYFraction: Float = 0.33f,
    val checkupCount: Int = 50,
    /** Transcribe voice messages locally (Parakeet via sherpa-onnx). Needs the loaded model and the granted folder. */
    val voiceTranscribe: Boolean = true, // on by default from 0.2.9 (migration for existing users)
    /** Folder "WhatsApp Voice Notes" chosen via folder grant (SAF) (tree URI), empty = none. */
    val voiceTreeUri: String = "",
    /** Maximum number of transcribed voice messages per run and chat, newest first. */
    val voiceMaxPerChat: Int = 20,
    /** Longer voice messages are not transcribed (seconds). */
    val voiceMaxSeconds: Int = 180,
    /** Time difference in minutes between the message time and the file's modification time. */
    val voiceToleranceMin: Int = 3,
    val voiceThreads: Int = 4,
    /** Setup: number of newest chats (quick pick in the checkup menu). */
    val setupCount: Int = 20,
    val setupTarget: Int = 100,
    val setupIncludeGroups: Boolean = false,
    val setupPinnedCounts: Boolean = true,
    /** Auto mode: target count per chat. */
    val autoTarget: Int = 100,
    val autoNames: String = "",
    val lastTask: app.chatlens.core.TaskMode = app.chatlens.core.TaskMode.ANALYSE,
    val lastGoal: String = "",
    val setupDone: Boolean = false,
    /** When the content is unchanged (end of the loaded transcript), finish cleanly after waiting and retrying, and continue with what was captured. */
    val endOnStatic: Boolean = true,
    /** Maximum wait after a scroll until the tree changes and settles. */
    val settleMaxMs: Int = 2500,
    /** Interval of tree reads while waiting (adaptive polling). */
    val pollMs: Int = 40,
    val maxScrollCap: Int = 100,
    /** Cap of the profile card per chat in characters (1500 to 12000). */
    val memoryMaxChars: Int = 6000,
    /** DISC estimate of the other person in memory (cautious, one-to-one chats only). */
    val memoryDisc: Boolean = true,
    /** Use the self profile: collect traits of the user that hold across chats and pass a short form into the advisor and suggestions. */
    val ichEnabled: Boolean = true,
    val maxRunsPerHour: Int = 10,
    val contextCharsApi: Int = 24_000,
    /** Character budget of the transcript in the local prompt. 0 = "automatisch" from the context level (3 characters per token). */
    val contextCharsLocal: Int = 0,
    val maskDebugText: Boolean = true,
    val privacyAcknowledged: Boolean = false,
    val lastChatTitle: String = "",
    val lastScrollCount: Int = 5,
    val lastInstruction: String = "Analysiere die Absichten des Gegenübers.",
    val lastChatAlreadyOpen: Boolean = false,
    val lastStopMode: StopMode = StopMode.TARGET,
    val lastTargetMessages: Int = 100,
    /** Mode "Chat schon geoeffnet": seconds of countdown after start, during which the user switches to WhatsApp. 0 = only via "Jetzt lesen". */
    val startDelaySec: Int = 5,
    /** Maximum wait for search hits after tapping in the search field, in milliseconds. */
    val searchWaitMs: Int = 4000,
)

class SettingsRepo(context: Context) {
    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences("chatlens", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            backend = BackendPolicy.effective(runCatching { BackendChoice.valueOf(sp.getString("backend", d.backend.name)!!) }.getOrDefault(d.backend)),
            apiBaseUrl = sp.getString("apiBaseUrl", d.apiBaseUrl)!!,
            apiKey = SecretStore.decrypt(sp.getString("apiKeyEnc", "")!!),
            apiModel = sp.getString("apiModel", d.apiModel)!!,
            apiVision = sp.getBoolean("apiVision", d.apiVision),
            localModelPath = sp.getString("localModelPath", d.localModelPath)!!,
            localAccel = runCatching { LocalAccel.valueOf(sp.getString("localAccel", d.localAccel.name)!!) }.getOrDefault(d.localAccel),
            localVision = sp.getBoolean("localVision", d.localVision),
            localMaxTokens = ContextPlanner.loadTokens(sp.getInt("localMaxTokens", d.localMaxTokens), sp.getBoolean("ctxAutoMigrated", false)),
            captureImages = sp.getBoolean("captureImages", d.captureImages),
            ocrImages = sp.getBoolean("ocrImages", d.ocrImages),
            maxImages = sp.getInt("maxImages", d.maxImages),
            groupChat = sp.getBoolean("groupChat", d.groupChat),
            // new keys: the old default pauses (1200 to 2800 ms) from version 0.1.0/0.1.1 are deliberately not carried over
            pauseMinMs = sp.getInt("scrollPauseMinMs", d.pauseMinMs),
            pauseMaxMs = sp.getInt("scrollPauseMaxMs", d.pauseMaxMs),
            scrollMethod = runCatching { ScrollMethod.valueOf(sp.getString("scrollMethod", d.scrollMethod.name)!!) }.getOrDefault(d.scrollMethod),
            scrollStepPercent = sp.getInt("scrollStepPercent", d.scrollStepPercent),
            swipeDurMs = sp.getInt("swipeDurMs", d.swipeDurMs),
            holdMs = sp.getInt("holdMs", d.holdMs),
            selfCalibrate = sp.getBoolean("selfCalibrate", d.selfCalibrate),
            targetOverlapPercent = sp.getInt("targetOverlapPercent", d.targetOverlapPercent),
            endOnStatic = sp.getBoolean("endOnStatic", d.endOnStatic),
            experimentalSend = BackendPolicy.sendAllowed && sp.getBoolean("experimentalSend", d.experimentalSend),
            overlayEnabled = sp.getBoolean("overlayEnabled", d.overlayEnabled),
            checkupOnStart = app.chatlens.wizard.StartPolicy.checkupOnStartLoaded(sp.getBoolean("checkupOnStart", false), sp.getBoolean("consentMig", false)),
            wizardStep = sp.getString("wizardStep", d.wizardStep)!!,
            wizardDone = sp.getBoolean("wizardDone", d.wizardDone),
            wizardSkipped = sp.getBoolean("wizardSkipped", d.wizardSkipped),
            a11yConsent = sp.getBoolean("a11yConsent", d.a11yConsent),
            notifAsked = sp.getBoolean("notifAsked", d.notifAsked),
            developerMode = sp.getBoolean("developerMode", d.developerMode),
            enabledMessengers = sp.getString("enabledMessengers", d.enabledMessengers) ?: d.enabledMessengers,
            dotSide = sp.getInt("dotSide", d.dotSide).coerceIn(0, 1),
            dotYFraction = sp.getFloat("dotYFraction", d.dotYFraction).coerceIn(0f, 1f),
            checkupCount = sp.getInt("checkupCount", d.checkupCount),
            voiceTranscribe = app.chatlens.wizard.StartPolicy.voiceLoaded(sp.getBoolean("voiceTranscribe", true), sp.getBoolean("consentMig", false)),
            voiceTreeUri = sp.getString("voiceTreeUri", d.voiceTreeUri) ?: "",
            voiceMaxPerChat = sp.getInt("voiceMaxPerChat", d.voiceMaxPerChat),
            voiceMaxSeconds = sp.getInt("voiceMaxSeconds", d.voiceMaxSeconds),
            voiceToleranceMin = sp.getInt("voiceToleranceMin", d.voiceToleranceMin),
            voiceThreads = sp.getInt("voiceThreads", d.voiceThreads),
            setupCount = sp.getInt("setupCount", d.setupCount),
            setupTarget = sp.getInt("setupTarget", d.setupTarget),
            setupIncludeGroups = sp.getBoolean("setupIncludeGroups", d.setupIncludeGroups),
            setupPinnedCounts = sp.getBoolean("setupPinnedCounts", d.setupPinnedCounts),
            autoTarget = sp.getInt("autoTarget", d.autoTarget),
            autoNames = sp.getString("autoNames", d.autoNames) ?: "",
            lastTask = runCatching { app.chatlens.core.TaskMode.valueOf(sp.getString("lastTask", d.lastTask.name) ?: "") }.getOrDefault(d.lastTask),
            lastGoal = sp.getString("lastGoal", d.lastGoal) ?: "",
            setupDone = sp.getBoolean("setupDone", d.setupDone),
            settleMaxMs = sp.getInt("settleMaxMs2", d.settleMaxMs),
            pollMs = sp.getInt("pollMs", d.pollMs),
            maxScrollCap = sp.getInt("maxScrollCap", d.maxScrollCap),
            memoryMaxChars = sp.getInt("memoryMaxChars", d.memoryMaxChars).coerceIn(1500, 12000),
            memoryDisc = sp.getBoolean("memoryDisc", d.memoryDisc),
            ichEnabled = sp.getBoolean("ichEnabled", d.ichEnabled),
            maxRunsPerHour = sp.getInt("maxRunsPerHour", d.maxRunsPerHour),
            contextCharsApi = sp.getInt("contextCharsApi", d.contextCharsApi),
            contextCharsLocal = ContextPlanner.loadChars(sp.getInt("contextCharsLocal", d.contextCharsLocal), sp.getBoolean("ctxAutoMigrated", false)),
            maskDebugText = sp.getBoolean("maskDebugText", d.maskDebugText),
            privacyAcknowledged = sp.getBoolean("privacyAcknowledged", d.privacyAcknowledged),
            lastChatTitle = sp.getString("lastChatTitle", d.lastChatTitle)!!,
            lastScrollCount = sp.getInt("lastScrollCount", d.lastScrollCount),
            lastInstruction = sp.getString("lastInstruction", d.lastInstruction)!!,
            lastChatAlreadyOpen = sp.getBoolean("lastChatAlreadyOpen", d.lastChatAlreadyOpen),
            lastStopMode = runCatching { StopMode.valueOf(sp.getString("lastStopMode", d.lastStopMode.name)!!) }.getOrDefault(d.lastStopMode),
            lastTargetMessages = sp.getInt("lastTargetMessages", d.lastTargetMessages),
            startDelaySec = sp.getInt("startDelaySec", d.startDelaySec),
            searchWaitMs = sp.getInt("searchWaitMs", d.searchWaitMs),
        )
    }

    fun save(s: AppSettings) {
        sp.edit()
            .putString("backend", s.backend.name)
            .putString("apiBaseUrl", s.apiBaseUrl.trim())
            .putString("apiKeyEnc", SecretStore.encrypt(s.apiKey.trim()))
            .putString("apiModel", s.apiModel.trim())
            .putBoolean("apiVision", s.apiVision)
            .putString("localModelPath", s.localModelPath)
            .putString("localAccel", s.localAccel.name)
            .putBoolean("localVision", s.localVision)
            .putInt("localMaxTokens", if (s.localMaxTokens <= 0) 0 else s.localMaxTokens.coerceIn(1024, 32768))
            .putBoolean("ctxAutoMigrated", true)
            .putBoolean("captureImages", s.captureImages)
            .putBoolean("ocrImages", s.ocrImages)
            .putInt("maxImages", s.maxImages.coerceIn(0, 20))
            .putBoolean("groupChat", s.groupChat)
            .putInt("scrollPauseMinMs", s.pauseMinMs.coerceIn(0, 20_000))
            .putInt("scrollPauseMaxMs", s.pauseMaxMs.coerceIn(s.pauseMinMs.coerceIn(0, 20_000), 30_000))
            .putString("scrollMethod", s.scrollMethod.name)
            .putInt("scrollStepPercent", s.scrollStepPercent.coerceIn(30, 70))
            .putInt("swipeDurMs", s.swipeDurMs.coerceIn(100, 1500))
            .putInt("holdMs", s.holdMs.coerceIn(0, 300))
            .putBoolean("selfCalibrate", s.selfCalibrate)
            .putBoolean("endOnStatic", s.endOnStatic)
            .putBoolean("experimentalSend", s.experimentalSend)
            .putBoolean("overlayEnabled", s.overlayEnabled)
            .putBoolean("checkupOnStart", s.checkupOnStart)
            .putBoolean("consentMig", true)
            .putString("wizardStep", s.wizardStep)
            .putBoolean("wizardDone", s.wizardDone)
            .putBoolean("wizardSkipped", s.wizardSkipped)
            .putBoolean("a11yConsent", s.a11yConsent)
            .putBoolean("notifAsked", s.notifAsked)
            .putBoolean("developerMode", s.developerMode)
            .putString("enabledMessengers", s.enabledMessengers)
            .putInt("dotSide", s.dotSide.coerceIn(0, 1))
            .putFloat("dotYFraction", s.dotYFraction.coerceIn(0f, 1f))
            .putInt("checkupCount", s.checkupCount.coerceIn(5, 200))
            .putBoolean("voiceTranscribe", s.voiceTranscribe)
            .putString("voiceTreeUri", s.voiceTreeUri)
            .putInt("voiceMaxPerChat", s.voiceMaxPerChat.coerceIn(1, 100))
            .putInt("voiceMaxSeconds", s.voiceMaxSeconds.coerceIn(10, 600))
            .putInt("voiceToleranceMin", s.voiceToleranceMin.coerceIn(0, 30))
            .putInt("voiceThreads", s.voiceThreads.coerceIn(1, 8))
            .putInt("setupCount", s.setupCount.coerceIn(1, 200))
            .putInt("setupTarget", s.setupTarget.coerceIn(10, 2000))
            .putBoolean("setupIncludeGroups", s.setupIncludeGroups)
            .putBoolean("setupPinnedCounts", s.setupPinnedCounts)
            .putInt("autoTarget", s.autoTarget.coerceIn(10, 2000))
            .putString("autoNames", s.autoNames)
            .putString("lastTask", s.lastTask.name)
            .putString("lastGoal", s.lastGoal)
            .putBoolean("setupDone", s.setupDone)
            .putInt("targetOverlapPercent", s.targetOverlapPercent.coerceIn(30, 50))
            .putInt("settleMaxMs2", s.settleMaxMs.coerceIn(300, 10_000))
            .putInt("pollMs", s.pollMs.coerceIn(10, 500))
            .putInt("maxScrollCap", s.maxScrollCap.coerceIn(1, 500))
            .putInt("memoryMaxChars", s.memoryMaxChars.coerceIn(1500, 12000))
            .putBoolean("memoryDisc", s.memoryDisc)
            .putBoolean("ichEnabled", s.ichEnabled)
            .putInt("maxRunsPerHour", s.maxRunsPerHour.coerceIn(1, 60))
            .putInt("contextCharsApi", s.contextCharsApi.coerceIn(1_000, 400_000))
            .putInt("contextCharsLocal", if (s.contextCharsLocal <= 0) 0 else s.contextCharsLocal.coerceIn(500, 100_000))
            .putBoolean("maskDebugText", s.maskDebugText)
            .putBoolean("privacyAcknowledged", s.privacyAcknowledged)
            .putString("lastChatTitle", s.lastChatTitle)
            .putInt("lastScrollCount", s.lastScrollCount)
            .putString("lastInstruction", s.lastInstruction)
            .putBoolean("lastChatAlreadyOpen", s.lastChatAlreadyOpen)
            .putString("lastStopMode", s.lastStopMode.name)
            .putInt("lastTargetMessages", s.lastTargetMessages.coerceIn(1, 5000))
            .putInt("startDelaySec", s.startDelaySec.coerceIn(0, 60))
            .putInt("searchWaitMs", s.searchWaitMs.coerceIn(1_000, 15_000))
            .apply()
    }

    /** Rate limit: timestamps of the last runs (milliseconds). */
    fun runTimestamps(): List<Long> =
        (sp.getString("runTimes", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }

    fun recordRun(now: Long) {
        val keep = runTimestamps().filter { now - it < 3_600_000L } + now
        sp.edit().putString("runTimes", keep.joinToString(",")).apply()
    }
}
