package app.chatlens.data

import app.chatlens.llm.ContextPlanner
import android.content.Context
import android.content.SharedPreferences
import app.chatlens.core.StopMode

enum class BackendChoice { API, LOCAL, EXTRACT_ONLY }

/**
 * Ausgabe-Richtlinie (ab 0.3.0): Die Play-Ausgabe hat keinen API-Modus ([app.chatlens.BuildConfig.API_MODE] ist dort falsch).
 * Ein gespeicherter Modus API wird dort beim Laden zu Nur Auslesen, damit nie Chatinhalt an einen Server geht.
 */
object BackendPolicy {
    val apiAllowed: Boolean get() = app.chatlens.BuildConfig.API_MODE
    fun effective(c: BackendChoice, allowed: Boolean = apiAllowed): BackendChoice = if (c == BackendChoice.API && !allowed) BackendChoice.EXTRACT_ONLY else c
    /** Experimentelles Senden (Senden-Knopf) gibt es nur im Full-Flavor. Der Play-Flavor fuehrt nie eine Sendeaktion aus. */
    val sendAllowed: Boolean get() = app.chatlens.BuildConfig.API_MODE
    fun choices(allowed: Boolean = apiAllowed): List<BackendChoice> = BackendChoice.entries.filter { allowed || it != BackendChoice.API }
}

enum class LocalAccel { CPU, GPU }

/**
 * AUTO: geregelter Swipe; bei wiederholtem Ueberlappungsverlust Genau, bei Wirkungslosigkeit ACTION_SCROLL_BACKWARD.
 * SWIPE: nur geregelter Swipe. ACTION: nur ACTION_SCROLL_BACKWARD. GENAU: nur Mikro-Wischer (langsam, lueckenlos).
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
    /** Kontextstufe des lokalen Modells in Token. 0 = automatisch maximal (Standard ab 0.2.9), sonst manuelle Obergrenze 4096, 8192, 16384 oder 32768. */
    val localMaxTokens: Int = 0,
    val captureImages: Boolean = true,
    val ocrImages: Boolean = true,
    val maxImages: Int = 6,
    val groupChat: Boolean = false,
    /** Zusatzpause nach jedem Scroll. Standard 0 = maximal schnell, keine Zufallspausen. */
    val pauseMinMs: Int = 0,
    val pauseMaxMs: Int = 0,
    val scrollMethod: ScrollMethod = ScrollMethod.AUTO,
    /** Schrittweite des Swipes in Prozent der Listenhoehe, hoechstens 70, damit Nachrichten ueberlappen. */
    val scrollStepPercent: Int = 60,
    /** Dauer der Wischbewegung. Lang und gleichmaessig, damit kein Nachschwung (Fling) entsteht. */
    val swipeDurMs: Int = 450,
    /** Fingerhaltezeit am Ende der Bewegung. */
    val holdMs: Int = 80,
    /** Wischstrecke aus gemessenem Scrollweg nachregeln (Selbstkalibrierung). Aus: feste Schrittweite. */
    val selfCalibrate: Boolean = true,
    /** Gewuenschte Ueberlappung zweier Seiten in Prozent der Listenhoehe (30 bis 50). */
    val targetOverlapPercent: Int = 35,
    /** EXPERIMENTELL, standardmaessig aus: erlaubt einen Senden-Knopf in der App (nur mit Bestaetigung je Text). Kann gegen die WhatsApp-AGB verstossen. */
    val experimentalSend: Boolean = false,
    /** Schwebender Punkt (Overlay) beim Start der App einblenden. */
    val overlayEnabled: Boolean = false,
    /** Checkup beim Start der App (einmal je Prozessstart, hoechstens alle 10 Minuten) und Anzahl X der gelesenen Chats. */
    val checkupOnStart: Boolean = false, // ab 0.2.9 aus; laeuft zudem nur nach abgeschlossenem Assistenten
    /** Einrichtungsassistent: aktuelle Seite (fortsetzbar), abgeschlossen, ueberspringen gewaehlt. */
    val wizardStep: String = "WELCOME",
    val wizardDone: Boolean = false,
    val wizardSkipped: Boolean = false,
    /** Ausdrückliche Zustimmung zur Bedienungshilfe (Offenlegung im Assistenten, ab 0.3.0). Nachweis fuer die Play-Erklaerung. */
    val a11yConsent: Boolean = false,
    /** Benachrichtigungserlaubnis wurde schon (mit Begruendung) angefragt. */
    val notifAsked: Boolean = false,
    /** Entwickleroption: zeigt den Tab Debug (ab 0.3.0 versteckt; siebenmal auf die Version tippen). */
    val developerMode: Boolean = false,
    /** Freigegebene Messenger, Kennungen mit Komma getrennt (Vorgabe nur whatsapp). Siehe MessengerRegistry. */
    val enabledMessengers: String = "whatsapp",
    /** Position des schwebenden Punktes: Seite (0 links, 1 rechts) und Hoehenanteil 0..1, wird gemerkt und rastet am Rand ein. */
    val dotSide: Int = 1,
    val dotYFraction: Float = 0.33f,
    val checkupCount: Int = 50,
    /** Sprachnachrichten lokal transkribieren (Parakeet ueber sherpa-onnx). Braucht das geladene Modell und den freigegebenen Ordner. */
    val voiceTranscribe: Boolean = true, // ab 0.2.9 standardmaessig an (Migration bei Bestandsnutzern)
    /** Per Ordnerfreigabe (SAF) gewaehlter Ordner "WhatsApp Voice Notes" (Tree-URI), leer = keiner. */
    val voiceTreeUri: String = "",
    /** Hoechstzahl transkribierter Sprachnachrichten je Lauf und Chat, die neuesten zuerst. */
    val voiceMaxPerChat: Int = 20,
    /** Laengere Sprachnachrichten werden nicht transkribiert (Sekunden). */
    val voiceMaxSeconds: Int = 180,
    /** Zeitabweichung in Minuten zwischen der Uhrzeit der Nachricht und dem Aenderungsdatum der Datei. */
    val voiceToleranceMin: Int = 3,
    val voiceThreads: Int = 4,
    /** Setup: Anzahl der neuesten Chats (Schnellwahl im Checkup-Menue). */
    val setupCount: Int = 20,
    val setupTarget: Int = 100,
    val setupIncludeGroups: Boolean = false,
    val setupPinnedCounts: Boolean = true,
    /** Auto-Modus: Zielmenge je Chat. */
    val autoTarget: Int = 100,
    val autoNames: String = "",
    val lastTask: app.chatlens.core.TaskMode = app.chatlens.core.TaskMode.ANALYSE,
    val lastGoal: String = "",
    val setupDone: Boolean = false,
    /** Bei unveraendertem Inhalt (Ende des geladenen Verlaufs) nach Warten und Wiederholung sauber beenden und mit dem Erfassten weiterarbeiten. */
    val endOnStatic: Boolean = true,
    /** Maximale Wartezeit nach einem Scroll, bis sich der Baum aendert und beruhigt hat. */
    val settleMaxMs: Int = 2500,
    /** Abstand der Baumabfragen beim Warten (adaptives Polling). */
    val pollMs: Int = 40,
    val maxScrollCap: Int = 100,
    /** Obergrenze des Steckbriefs je Chat in Zeichen (1500 bis 12000). */
    val memoryMaxChars: Int = 6000,
    /** DISC-Einschaetzung des Gegenuebers im Gedaechtnis (vorsichtig, nur Einzelchats). */
    val memoryDisc: Boolean = true,
    /** Ich-Profil nutzen: chatuebergreifende Merkmale des Nutzers sammeln und kurz in Berater und Vorschlaege geben. */
    val ichEnabled: Boolean = true,
    val maxRunsPerHour: Int = 10,
    val contextCharsApi: Int = 24_000,
    /** Zeichenbudget des Verlaufs im lokalen Prompt. 0 = automatisch aus der Kontextstufe (3 Zeichen je Token). */
    val contextCharsLocal: Int = 0,
    val maskDebugText: Boolean = true,
    val privacyAcknowledged: Boolean = false,
    val lastChatTitle: String = "",
    val lastScrollCount: Int = 5,
    val lastInstruction: String = "Analysiere die Absichten des Gegenübers.",
    val lastChatAlreadyOpen: Boolean = false,
    val lastStopMode: StopMode = StopMode.TARGET,
    val lastTargetMessages: Int = 100,
    /** Modus "Chat schon geoeffnet": Sekunden Countdown nach Start, in denen zu WhatsApp gewechselt wird. 0 = nur per "Jetzt lesen". */
    val startDelaySec: Int = 5,
    /** Maximale Wartezeit auf Suchtreffer nach dem Tippen im Suchfeld, in Millisekunden. */
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
            // neue Schluessel: alte Standardpausen (1200 bis 2800 ms) aus Version 0.1.0/0.1.1 werden bewusst nicht uebernommen
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

    /** Ratenlimit: Zeitstempel der letzten Laeufe (Millisekunden). */
    fun runTimestamps(): List<Long> =
        (sp.getString("runTimes", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }

    fun recordRun(now: Long) {
        val keep = runTimestamps().filter { now - it < 3_600_000L } + now
        sp.edit().putString("runTimes", keep.joinToString(",")).apply()
    }
}
