package app.chatlens.agent

import android.content.Context
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import app.chatlens.core.ChatMessage
import app.chatlens.core.Direction
import app.chatlens.core.Kind
import app.chatlens.core.ParsedPage
import app.chatlens.assist.Assist
import app.chatlens.auto.LlmGate
import app.chatlens.core.ScrollRunConfig
import app.chatlens.core.TaskMode
import app.chatlens.llm.LlmFactory
import app.chatlens.memory.ChatMemory
import app.chatlens.memory.MemoryUpdater
import app.chatlens.data.MemoryRepo
import app.chatlens.core.StopMode
import app.chatlens.core.UiNode
import app.chatlens.data.ScrollMethod
import app.chatlens.data.AppLog
import app.chatlens.data.AppSettings
import app.chatlens.data.BackendChoice
import app.chatlens.data.SettingsRepo
import app.chatlens.llm.ContextBuilder
import app.chatlens.llm.LiteRtLmBackend
import app.chatlens.llm.LlmBackend
import app.chatlens.llm.LlmImage
import app.chatlens.llm.LlmRequest
import app.chatlens.llm.OpenAiCompatBackend
import app.chatlens.llm.PromptBuilder
import app.chatlens.parse.ChatParser
import app.chatlens.parse.TranscriptMerger
import app.chatlens.service.ChatAccessibilityService
import app.chatlens.service.DebugDumper
import app.chatlens.service.ShotResult
import app.chatlens.vision.ImageTools
import app.chatlens.vision.Ocr
import app.chatlens.asr.VoiceRuntime
import app.chatlens.models.MeasuredStats
import app.chatlens.memory.Disc
import app.chatlens.memory.SelfAnalysis
import app.chatlens.memory.IchLogic
import app.chatlens.prompts.PromptLimits
import app.chatlens.prompts.PromptMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * Flow of a run: open the chat, scroll backward N times, read, capture images, call the LLM.
 * Nothing is sent or written in WhatsApp (only the search field, to choose the chat).
 */
class RunResult(val messages: List<ChatMessage>, val text: String, val memory: ChatMemory?, val info: String)

class ChatRunner(private val ctx: Context, private val repo: SettingsRepo) {

    private val memoryRepo get() = MemoryRepo.get(ctx)

    private companion object {
        const val LONG_WAIT_MS = 3_000L
    }

    private fun log(msg: String) {
        AppLog.i(msg)
        AgentState.update { it.copy(message = msg) }
    }

    private fun imageDir(): File = File(ctx.cacheDir, "chatlens/images")

    fun clearCachedImages() {
        imageDir().deleteRecursively()
    }

    /**
     * Mode "Chat ist schon geoeffnet": waits until the user has switched to WhatsApp.
     * Triggers: countdown (start-delay setting) or "Jetzt lesen" (notification or app).
     * With a start delay of 0 there is no countdown, only "Jetzt lesen" (at most 5 minutes of waiting).
     * After that, the code waits up to 60 s for WhatsApp to be in the foreground. WhatsApp is never started via intent.
     */
    private suspend fun awaitUserSwitch(s: AppSettings, nav: WhatsAppNavigator, svc: ChatAccessibilityService) {
        AgentState.update { it.copy(phase = Phase.WAITING) }
        val delaySec = s.startDelaySec
        AppLog.i("WARTEN: Modus Chat schon geoeffnet, kein WhatsApp-Start per Intent. Start-Verzoegerung ${delaySec} s (0 = nur Jetzt lesen).")
        val started = System.currentTimeMillis()
        val limitMs = if (delaySec > 0) delaySec * 1000L else 300_000L
        var lastShown = -1L
        var byButton = false
        while (true) {
            coroutineContext.ensureActive()
            if (AgentController.consumeReadNow()) {
                byButton = true
                break
            }
            val left = limitMs - (System.currentTimeMillis() - started)
            if (left <= 0) {
                if (delaySec > 0) break
                throw AgentException("Keine Aktion nach 5 Minuten. Chat in WhatsApp oeffnen und in der Benachrichtigung \"Jetzt lesen\" tippen.")
            }
            val sec = (left + 999) / 1000
            if (sec != lastShown) {
                lastShown = sec
                val msg = if (delaySec > 0) "Noch $sec s: jetzt zu WhatsApp wechseln (Chat bleibt offen)." else "Bereit. In WhatsApp den Chat oeffnen, dann \"Jetzt lesen\" in der Benachrichtigung tippen."
                AgentState.update { it.copy(message = msg) }
            }
            delay(200)
        }
        AppLog.i("WARTEN beendet (${if (byButton) "Jetzt lesen" else "Countdown"}) nach ${System.currentTimeMillis() - started} ms.")
        AgentState.update { it.copy(phase = Phase.NAVIGATING, message = "Warte auf WhatsApp im Vordergrund ...") }
        delay(700) // let the notification shade collapse
        if (!nav.waitForWhatsApp(60_000)) {
            nav.guard.logState("WARTEN", force = true)
            throw AgentException("WhatsApp kam nicht in den Vordergrund (60 s gewartet, ${nav.guard.describe()}). Zu WhatsApp wechseln und erneut starten. Es wurde nichts angetippt.")
        }
        var state = ScreenState.OTHER
        for (i in 1..5) {
            delay(700)
            nav.guard.ensure(allowLaunch = false, what = "Modus Chat schon geoeffnet")
            state = nav.classify(svc.snapshot())
            AppLog.i("WARTEN: Pruefung $i, Bildschirmzustand $state, ${nav.guard.describe()}")
            if (state == ScreenState.CHAT) return
        }
        val name = runCatching { DebugDumper.dumpNow(ctx, true)?.name }.getOrNull()
        throw AgentException(
            "Kein geoeffneter Chat erkannt (Zustand $state, kein Nachrichtenfeld unten). Erst den Chat in WhatsApp oeffnen, dann ChatLens ausloesen." +
                (if (name != null) " Diagnose-Baum (maskiert) gespeichert: $name." else ""),
        )
    }

    suspend fun run(cfg: ScrollRunConfig, s0: AppSettings): RunResult {
        // Keep the screen awake for the whole run (otherwise the lock screen, com.android.systemui, is in front after the screen timeout)
        app.chatlens.service.ScreenAwake.acquire()
        try {
            return runInner(cfg, s0)
        } finally {
            app.chatlens.service.ScreenAwake.release()
        }
    }

    private suspend fun runInner(cfg: ScrollRunConfig, s0: AppSettings): RunResult {
        // Memory runs read text only: no screenshots, faster
        val s = if (cfg.task == TaskMode.MEMORY || cfg.task == TaskMode.SELF) s0.copy(captureImages = false) else s0
        val svc = ChatAccessibilityService.instance
            ?: throw AgentException("Bedienungshilfe ChatLens ist nicht aktiv (Systemeinstellungen, Bedienungshilfen).")
        if (!cfg.inQueue) {
            val now = System.currentTimeMillis()
            val recent = repo.runTimestamps().count { now - it < 3_600_000L }
            if (recent >= s.maxRunsPerHour) {
                throw AgentException("Ratenlimit: $recent Laeufe in der letzten Stunde (Maximum ${s.maxRunsPerHour}). Spaeter erneut versuchen oder Limit in den Einstellungen anheben.")
            }
            repo.recordRun(now)
        }

        val targetMode = cfg.stopMode == StopMode.TARGET
        val target = cfg.targetMessages.coerceIn(1, 5000)
        val cap = s.maxScrollCap
        val n = cfg.scrollCount.coerceIn(0, cap)
        if (!targetMode && n != cfg.scrollCount) log("Scroll-Anzahl auf Obergrenze ${s.maxScrollCap} begrenzt.")
        AppLog.i(
            "LAUF: Modus ${if (targetMode) "Zielmenge $target Nachrichten" else "feste Scroll-Schritte $n"}, Sicherheitsobergrenze $cap Schritte, " +
                "Methode ${s.scrollMethod}, Selbstkalibrierung ${s.selfCalibrate}, Ziel-Ueberlappung ${s.targetOverlapPercent} Prozent, feste Schrittweite ${s.scrollStepPercent} Prozent, " +
                "Swipe ${s.swipeDurMs} ms, Halten ${s.holdMs} ms, Warten max ${s.settleMaxMs} ms, Poll ${s.pollMs} ms, " +
                "Zusatzpause ${s.pauseMinMs} bis ${s.pauseMaxMs} ms",
        )

        val profile = ProfileStore.load(ctx)
        if (profile.calibrationStatus != "CALIBRATED") {
            AppLog.w("Selektor-Profil ist nicht als kalibriert markiert (${profile.calibrationStatus}). Ergebnisse pruefen.")
        }
        val parser = ChatParser(profile, svc.density, s.groupChat)
        val nav = WhatsAppNavigator(
            svc, profile, parser,
            searchWaitMs = s.searchWaitMs.toLong(),
            diagDump = { DebugDumper.dumpNow(ctx, true)?.name },
        )
        clearCachedImages()
        val merger = TranscriptMerger()
        val mem0: ChatMemory? = if (cfg.task == TaskMode.MEMORY || cfg.useMemory) runCatching { memoryRepo.loadByTitle(cfg.chatTitle) }.getOrNull() else null
        val anchor: List<String> = if (cfg.incremental) mem0?.anchor.orEmpty() else emptyList()

        var imageCount = 0

        AgentState.update {
            it.copy(
                phase = Phase.NAVIGATING, scrollDone = 0, scrollTotal = if (targetMode) 0 else n,
                targetMessages = if (targetMode) target else 0, message = "Starte ...",
            )
        }
        run {
            val local = s.backend == BackendChoice.LOCAL
            AgentState.beginProgress(
                RunProgress.plan(cfg.task, cfg.chatAlreadyOpen, cfg.askPrompt, s.voiceTranscribe, local),
                RunProgress.llmLabel(cfg.task, File(s.localModelPath).name, local),
            )
        }
        try {
            if (cfg.chatAlreadyOpen) {
                // No intent launch of WhatsApp: the opened chat stays unchanged.
                awaitUserSwitch(s, nav, svc)
                log("Chat ist bereits geoeffnet (manueller Modus).")
            } else if (cfg.fromList) {
                // Setup, self-analysis: search first, no guessing and no prompt (unattended); fallback is the visible row
                nav.openChatForRun(cfg.chatTitle, { log(it) }, null)
            } else {
                nav.openChatForRun(cfg.chatTitle, { log(it) }) { found, percent ->
                    ConfirmBroker.ask(
                        "Chat nicht exakt gefunden",
                        "Gesucht: \"${cfg.chatTitle}\"\nBester Treffer: \"$found\" ($percent Prozent Übereinstimmung)\nDiesen Chat nehmen?",
                    )
                }
            }

            // Before every read and scroll: is WhatsApp really in front? Otherwise (except in manual mode) bring it back via intent,
            // and abort cleanly after 3 attempts. In mode "Chat schon geoeffnet", ChatLens never brings WhatsApp to the front itself.
            suspend fun ensureForeground() {
                nav.guard.ensure(allowLaunch = !cfg.chatAlreadyOpen, what = "Lesen")
            }

            // Reads the visible slice (or an already available snapshot), merges it, and captures images.
            suspend fun readAndMerge(pre: UiNode? = null, allowGap: Boolean = true): PageRead {
                ensureForeground()
                val snap = pre ?: svc.snapshot() ?: throw AgentException("Kein Accessibility-Baum verfuegbar.")
                val page = parser.parse(snap)
                if (!page.listFound) throw AgentException("Keine scrollbare Nachrichtenliste im Baum gefunden. Debug-Baum exportieren und Profil kalibrieren.")
                val res = merger.add(page.items, allowGap)
                if (res.noOverlap && !allowGap) return PageRead(res, page, snap)
                if (res.gapInserted) log("Warnung: Seiten ueberlappen nicht, moegliche Luecke.")
                if (s.captureImages && s.maxImages > 0) {
                    imageCount += captureImages(svc, page, res.canonical, s, imageCount)
                }
                return PageRead(res, page, snap)
            }

            fun counted() = merger.messages.count { TranscriptMerger.isCountable(it) }
            var effective = 0 // scroll steps that actually changed the tree
            if (cfg.incremental) log(if (anchor.isEmpty()) "Kein Anker im Gedaechtnis: voller Lesevorgang." else "Inkrementell: lese bis zum Anker (hoechstens $target Nachrichten).")
            fun reached() = when {
                anchor.isNotEmpty() -> MemoryUpdater.anchorReached(merger.messages, anchor) || counted() >= target
                targetMode -> counted() >= target
                else -> effective >= n
            }

            AgentState.update { it.copy(phase = Phase.SCROLLING) }
            AgentState.step(StepKind.COLLECT)
            var cur = readAndMerge()
            log("Seite 0 gelesen: ${cur.res.added} Zeilen, ${page(cur.page)} Zeilen sichtbar, angeschnitten: ${merger.incompleteCount()}.")
            publishCounts(merger.messages, imageCount)

            val device = AndroidScrollDevice(svc, nav.guard)
            val hooks = object : ScrollHooks {
                override fun log(msg: String) = this@ChatRunner.log(msg)
                override fun warn(msg: String) = AppLog.w(msg)
                override suspend fun ensureForeground() = ensureForeground()
                override suspend fun afterMerge(read: PageRead) {
                    if (s.captureImages && s.maxImages > 0) {
                        imageCount += captureImages(svc, read.page, read.res.canonical, s, imageCount)
                    }
                    publishCounts(merger.messages, imageCount)
                }
            }
            val sc = ScrollConfig(
                method = s.scrollMethod,
                selfCalibrate = s.selfCalibrate,
                fixedStepFraction = ScrollPlan.clampStep(s.scrollStepPercent / 100.0),
                targetOverlap = (s.targetOverlapPercent / 100.0).coerceIn(0.2, 0.6),
                swipeMs = s.swipeDurMs.toLong(),
                holdMs = s.holdMs.toLong(),
                settleMaxMs = s.settleMaxMs.toLong(),
                pollMs = s.pollMs.toLong(),
                longWaitMs = LONG_WAIT_MS,
                pauseMinMs = s.pauseMinMs.toLong(),
                pauseMaxMs = s.pauseMaxMs.toLong(),
                endOnStatic = s.endOnStatic,
            )
            val ctl = ScrollController(
                device, parser, merger,
                startNotice = { merger.messages.take(6).any { it.kind == Kind.SYSTEM && profile.isChatStartNotice(it.text) } },
                cfg = sc, hooks = hooks, initial = cur,
            )
            val maxAttempts = (cap * 3).coerceAtLeast(10)
            val scrollStartedAt = System.currentTimeMillis()

            suspend fun scrollOnce(): Boolean {
                val go = ctl.scrollOnce()
                cur = ctl.cur
                effective = ctl.stats.effective
                AgentState.update { it.copy(scrollDone = effective, scrollInfo = scrollInfoLine(ctl, merger)) }
                publishCounts(merger.messages, imageCount)
                return go
            }

            while (!reached() && effective < cap && ctl.stats.attempts < maxAttempts) {
                if (!scrollOnce()) break
            }
            val attempts = ctl.stats.attempts
            val atStart = ctl.atStart
            val stuck = ctl.stuck
            val hitCap = !reached() && ctl.endKind == null
            // Finish: if the oldest message is still clipped, take one more step (it then becomes fully visible)
            if (reached() && !atStart && ctl.stats.attempts < maxAttempts &&
                merger.messages.firstOrNull { it.kind != Kind.DATE }?.incomplete == true
            ) {
                log("Ziel erreicht, aelteste Nachricht ist angeschnitten: ein weiterer Schritt zur Vervollstaendigung.")
                scrollOnce()
            }
            if (targetMode && !atStart) {
                val dropped = merger.trimIncompleteHead(target)
                if (dropped > 0) log("$dropped angeschnittene aelteste Zeile(n) verworfen (Ziel bleibt erfuellt).")
            }
            val open = merger.incompleteCount()
            run {
                val inc = merger.messages.filter { it.incomplete }
                val gaps = merger.messages.count { it.kind == Kind.GAP }
                val trunc = merger.messages.count { it.truncated }
                log(
                    "Statistik: ${merger.messages.size} Zeilen, davon final unvollstaendig ${inc.size} " +
                        "(oben angeschnitten ${inc.count { it.clipTop }}, unten ${inc.count { it.clipBottom }}, Bilder ${inc.count { it.kind == Kind.IMAGE }}), " +
                        "Mehr-lesen-gekuerzt $trunc, Luecken $gaps. Scrollsteuerung: ${ctl.summary()}.",
                )
                AgentState.update { it.copy(scrollInfo = scrollInfoLine(ctl, merger)) }
            }
            val summary = when {
                ctl.endKind != null -> ctl.endReason
                hitCap -> "Sicherheitsobergrenze erreicht ($cap Schritte oder $maxAttempts Versuche)"
                targetMode -> "Zielmenge erreicht"
                else -> "$n Scroll-Schritte ausgefuehrt"
            }
            val dur = (System.currentTimeMillis() - scrollStartedAt) / 1000.0
            val gapsNow = merger.messages.count { it.kind == Kind.GAP }
            log(
                "ENDE: Grund: ${summary.trimEnd('.')}. Nachrichten gesamt ${counted()}" + (if (targetMode) " (Ziel $target)" else "") +
                    ", final unvollstaendig ${merger.incompleteCount()}, Luecken $gapsNow, Dauer ${"%.1f".format(dur)} s, " +
                    "Scroll-Schritte $effective, Versuche $attempts. Die Analyse startet mit dem bisher Erfassten.",
            )
            if (!atStart && !reached()) {
                AgentState.update { it.copy(message = "Ende ohne Ziel: $summary") }
            }

            // Mark images that were not captured
            for (m in merger.messages) {
                if (m.kind == Kind.IMAGE && m.imagePath == null && m.imageNote == null) {
                    m.imageNote = "nicht erfasst (nur teilweise sichtbar, Limit oder Bilderfassung aus)"
                }
            }
        } finally {
            val shown = ContextBuilder.build(merger.messages, cfg.chatTitle, Int.MAX_VALUE / 4, true, 1000)
            AgentState.update {
                it.copy(
                    transcript = shown.transcript,
                    messageCount = shown.messageCount,
                    imageCount = merger.messages.count { m -> m.imagePath != null },
                )
            }
            // After reading, go back cleanly to the chat list (back, Chats tab, close search), except for suggest/adviser, which work in the open chat
            if (!cfg.chatAlreadyOpen && (cfg.inQueue || (cfg.task != TaskMode.SUGGEST && cfg.task != TaskMode.ADVISE)) && ChatAccessibilityService.instance != null) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { nav.returnToList { log(it) } }
            }
        }

        // Voice messages: assign them and transcribe locally (before the LLM, so the model is released again afterward)
        // Applies to every run that goes through ChatRunner: single chat/dot, setup, auto, self-analysis, chat by name.
        run {
            val voices = merger.messages.count { it.kind == Kind.VOICE }
            val vs = VoiceRuntime.status(ctx, s)
            val d = app.chatlens.asr.VoicePlan.decide(s.voiceTranscribe, voices, vs.modelDir != null, vs.folderSet, vs.folderGranted)
            if (!d.run) {
                // Skipped cleanly: remove the step from the bar, and put the reason and the action hint in the log and the display
                AgentState.dropStep(StepKind.TRANSCRIBE)
                if (d.skipReason != null) {
                    log("STIMME: uebersprungen. ${d.skipReason} ${d.todo.orEmpty()}")
                    VoiceState.set(null, "Nicht ausgeführt. ${d.skipReason} ${d.todo.orEmpty()}")
                    AgentState.update { it.copy(voiceNote = (d.skipReason + " " + d.todo.orEmpty()).trim()) }
                }
            } else {
                AgentState.step(StepKind.TRANSCRIBE)
                AgentState.update { it.copy(message = "Sprachnachrichten werden transkribiert ...", voiceNote = "") }
                val rep = withContext(Dispatchers.Default) {
                    VoiceRuntime.transcribeMessages(ctx, s, merger.messages, { log(it) }, { !isActive }, { n, m -> AgentState.stepDetail("Nachricht $n von $m") })
                }
                if (rep != null && rep.transcribed > 0) {
                    val shown = ContextBuilder.build(merger.messages, cfg.chatTitle, Int.MAX_VALUE / 4, true, 1000)
                    AgentState.update { it.copy(transcript = shown.transcript) }
                }
                if (rep != null) AgentState.update { it.copy(voiceNote = "Sprachnachrichten: ${rep.transcribed} von ${rep.voiceMessages} transkribiert.") }
            }
        }

        // LLM
        val all = merger.messages.toList()
        if (s.backend == BackendChoice.EXTRACT_ONLY) {
            if (cfg.task == TaskMode.MEMORY) throw AgentException("Für Gedächtnis, Setup und Auto-Modus in den Einstellungen ein Modell wählen (lokal oder API), nicht \"nur Auslesen\".")
            AgentState.update { it.copy(phase = Phase.DONE, message = "Fertig (nur Auslesen, kein LLM-Aufruf).", result = "", resultInfo = "") }
            return RunResult(all, "", null, "nur Auslesen")
        }
        // Choice after collection (in the box): standard prompt or custom prompt. Nothing is chosen silently.
        var instruction = cfg.instruction
        if (cfg.askPrompt && cfg.task == TaskMode.ANALYSE) {
            AgentState.step(StepKind.CHOOSE)
            AgentState.update { it.copy(phase = Phase.CHOOSING, message = "Nachrichten gesammelt (${merger.messages.count { m -> TranscriptMerger.isCountable(m) }}). Bitte Prompt wählen.") }
            PromptBookState.ensureLoaded(ctx)
            when (val ch = PromptChoiceBroker.ask(cfg.chatTitle, merger.messages.count { TranscriptMerger.isCountable(it) })) {
                is PromptChoice.Standard -> {
                    PromptBookState.update(ctx) { it.withUsed(PromptMode.STANDARD, "") }
                    log("ANALYSE: Auswahl Standardprompt (Aufgabe aus den Einstellungen, ${cfg.instruction.length} Zeichen).")
                }
                is PromptChoice.Custom -> {
                    instruction = PromptLimits.clean(ch.text)
                    PromptBookState.update(ctx) { it.withUsed(PromptMode.CUSTOM, instruction) }
                    log("ANALYSE: Auswahl eigener Prompt, ${instruction.length} Zeichen (der Text steht nicht im Log).")
                }
                is PromptChoice.Cancel -> {
                    log("ANALYSE: Auswahl abgebrochen oder ohne Antwort, kein Modellaufruf.")
                    AgentState.update { it.copy(phase = Phase.CANCELLED, message = "Abgebrochen bei der Auswahl. Die gelesenen Nachrichten bleiben sichtbar, es wurde nichts an das Modell gegeben.", error = "") }
                    return RunResult(all, "", null, "Auswahl abgebrochen")
                }
            }
        }
        val backend: LlmBackend = LlmFactory.create(ctx, s)
        var loadStartedAt = 0L
        var loadMs = 0L
        run {
            val local = backend is LiteRtLmBackend
            AgentState.setLlmLabel(RunProgress.llmLabel(cfg.task, backend.name, local))
            if (backend is LiteRtLmBackend && backend.needsLoad()) {
                AgentState.step(StepKind.LOAD_MODEL)
                LiteRtLmBackend.loadListener = { loading ->
                    if (loading) loadStartedAt = System.currentTimeMillis() else { loadMs = System.currentTimeMillis() - loadStartedAt; AgentState.step(StepKind.LLM) }
                }
            } else {
                AgentState.dropStep(StepKind.LOAD_MODEL)
                AgentState.step(StepKind.LLM)
            }
        }
        // Load the local model first: only then is the actual context level known (automatic: the largest the phone can handle)
        var ctxLevel = 0
        if (backend is LiteRtLmBackend) {
            try {
                ctxLevel = LlmGate.exclusive { backend.ensureLoaded() }
            } catch (e: Exception) {
                LiteRtLmBackend.loadListener = null
                throw e
            }
            log("KONTEXT: ${LiteRtLmBackend.contextInfo()}.")
            AgentState.update { it.copy(contextInfo = LiteRtLmBackend.contextInfo()) }
        }
        val maxChars = if (backend.sendsDataOffDevice) s.contextCharsApi else app.chatlens.llm.ContextPlanner.effectiveChars(s.contextCharsLocal, ctxLevel)
        // Short Ich block (mirror the style) and budget for the chat memory in the prompt (KV budget): at most one third of the context
        IchState.ensureLoaded(ctx)
        val ichText = if (s.ichEnabled && cfg.task != TaskMode.MEMORY && cfg.task != TaskMode.ANALYSE) IchState.profile.value.toPromptBlock().ifBlank { null } else null
        val memBudget = minOf(s.memoryMaxChars, maxChars / 3).coerceAtLeast(800)
        val noThink = (backend as? LiteRtLmBackend)?.wantsNoThinkSuffix == true

        // Messages for the prompt: with incremental memory, only those since the anchor
        var forPrompt = all
        var incrementalUsed = false
        var nothingNew = false
        if (cfg.task == TaskMode.MEMORY && anchor.isNotEmpty()) {
            val since = MemoryUpdater.messagesSince(all, anchor)
            if (since.found) {
                forPrompt = since.newer
                incrementalUsed = true
                nothingNew = since.newer.none { TranscriptMerger.isCountable(it) }
                log("Seit dem Anker ${since.newer.count { TranscriptMerger.isCountable(it) }} neue Nachrichten.")
            } else {
                log("Anker im gelesenen Verlauf nicht gefunden: nutze den gelesenen Verlauf komplett.")
            }
        }
        if (cfg.task == TaskMode.MEMORY && nothingNew && mem0 != null) {
            AgentState.update { it.copy(phase = Phase.DONE, message = "Keine neuen Nachrichten seit dem letzten Stand.", result = mem0.toPromptBlock(), resultChat = cfg.chatTitle) }
            return RunResult(all, mem0.toPromptBlock(), mem0, "keine neuen Nachrichten")
        }

        val built = ContextBuilder.build(forPrompt, cfg.chatTitle, maxChars, cfg.task == TaskMode.ANALYSE && backend.supportsImages, s.maxImages)
        val mem = mem0
        val req = when (cfg.task) {
            TaskMode.SELF -> {
                // Only the user's own messages, with little context, without names and times; the log gets numbers only
                val chunks = SelfAnalysis.packChunks(SelfAnalysis.ownBlocks(forPrompt), maxChars)
                val n = chunks.sumOf { it.second }
                log("SELBSTANALYSE: $n eigene Nachrichten in ${chunks.size} Abschnitt(en) (Budget $maxChars Zeichen je Abschnitt, Inhalt nicht im Log).")
                if (n == 0) throw AgentException("Keine eigenen Nachrichten im gelesenen Abschnitt.")
                if (chunks.size == 1) {
                    LlmRequest(system = SelfAnalysis.chatSystem(cfg.instruction), user = SelfAnalysis.chatUser(chunks[0].first) + if (noThink) "\n\n/no_think" else "")
                } else {
                    // Map-reduce: one call per section, then a merge
                    val parts = ArrayList<String>()
                    for ((i, c) in chunks.withIndex()) {
                        coroutineContext.ensureActive()
                        log("SELBSTANALYSE: Abschnitt ${i + 1} von ${chunks.size} (${c.second} Nachrichten, ${c.first.length} Zeichen).")
                        val r = LlmGate.exclusive { backend.generate(LlmRequest(system = SelfAnalysis.chatSystem(cfg.instruction), user = SelfAnalysis.chatUser(c.first) + if (noThink) "\n\n/no_think" else "")) }
                        parts.add(r.text)
                    }
                    log("SELBSTANALYSE: fuehre ${parts.size} Abschnittsergebnisse zusammen.")
                    LlmRequest(system = SelfAnalysis.mergeSystem(), user = SelfAnalysis.reduceUser(parts) + if (noThink) "\n\n/no_think" else "")
                }
            }
            TaskMode.ANALYSE -> LlmRequest(
                system = PromptBuilder.system(cfg.chatTitle),
                user = PromptBuilder.user(instruction, built, noThinkSuffix = noThink),
                images = built.images.mapIndexedNotNull { i, m ->
                    m.imagePath?.let { p -> runCatching { LlmImage("Bild ${i + 1}", File(p).readBytes()) }.getOrNull() }
                },
            )
            TaskMode.MEMORY -> LlmRequest(
                system = MemoryUpdater.system(disc = s.memoryDisc && !s.groupChat, ich = s.ichEnabled),
                user = MemoryUpdater.user(mem, built.transcript, incrementalUsed, cfg.chatTitle, s.memoryMaxChars + 600) + if (noThink) "\n\n/no_think" else "",
            )
            TaskMode.SUGGEST -> LlmRequest(
                system = Assist.suggestSystem(cfg.chatTitle),
                user = Assist.suggestUser(cfg.goal, if (cfg.useMemory) mem else null, built.transcript, ichText, memBudget) + if (noThink) "\n\n/no_think" else "",
            )
            TaskMode.ADVISE -> LlmRequest(
                system = Assist.adviserSystem(cfg.chatTitle),
                user = Assist.adviserUser(cfg.goal, if (cfg.useMemory) mem else null, built.transcript, ichText, memBudget) + if (noThink) "\n\n/no_think" else "",
            )
        }
        AgentState.update {
            it.copy(
                phase = Phase.LLM,
                message = "Rufe ${backend.name} auf ...",
                contextPreview = req.system + "\n\n" + req.user + if (req.images.isNotEmpty()) "\n\n[+ ${req.images.size} Bild(er) als JPEG]" else "",
            )
        }
        log("LLM-Aufruf (${cfg.task}): ${backend.name}, ${req.user.length} Zeichen, ${req.images.size} Bilder, Daten verlassen Geraet: ${backend.sendsDataOffDevice}.")
        // Only one model call at a time
        val genStart = System.currentTimeMillis()
        val result = try { LlmGate.exclusive { backend.generate(req) } } finally { LiteRtLmBackend.loadListener = null }
        // Measurement for the model list: local models only, without load time, numbers only
        if (backend is LiteRtLmBackend) {
            val sec = (System.currentTimeMillis() - genStart - loadMs) / 1000.0
            runCatching { MeasuredStats.record(ctx, File(s.localModelPath).name, sec, req.user.length) }
        }

        var savedMemory: ChatMemory? = null
        var text = result.text
        var suggestions: List<String> = emptyList()
        when (cfg.task) {
            TaskMode.MEMORY -> {
                val seen = forPrompt.count { TranscriptMerger.isCountable(it) }
                val updated = MemoryUpdater.apply(
                    mem, result.text, cfg.chatTitle, System.currentTimeMillis(),
                    MemoryUpdater.anchorOf(all), all.lastOrNull { TranscriptMerger.isCountable(it) }?.time.orEmpty(), seen,
                    maxChars = s.memoryMaxChars,
                    partnerMessages = forPrompt.count { it.direction == Direction.IN && TranscriptMerger.isCountable(it) },
                    discEnabled = s.memoryDisc && !s.groupChat,
                )
                AgentState.step(StepKind.SAVE)
                memoryRepo.save(updated)
                savedMemory = updated
                text = updated.toPromptBlock()
                log("Gedaechtnis gespeichert (${updated.generatedLength} Zeichen, verschluesselt auf dem Geraet).")
                if (s.ichEnabled) updateIch(result.text, forPrompt, updated, cfg.chatTitle)
            }
            TaskMode.SUGGEST -> {
                suggestions = Assist.parseSuggestions(result.text)
                log("${suggestions.size} Antwortentwuerfe. Es wird nichts gesendet.")
            }
            else -> {}
        }
        AgentState.update {
            it.copy(
                phase = Phase.DONE, message = "Fertig.", result = text, suggestions = suggestions, resultChat = cfg.chatTitle,
                resultInfo = result.info + "; " + built.notes.joinToString(" "),
            )
        }
        log("Fertig: ${result.text.length} Zeichen Antwort.")
        return RunResult(all, text, savedMemory, result.info)
    }

    /**
     * Ich profile after a memory run: only traits that apply across chats, and every candidate goes through the check ([IchLogic.clean]) against names from
     * the contact list, chat names, and memory, and against the facts of this chat. The log gets numbers only.
     */
    private fun updateIch(output: String, msgs: List<ChatMessage>, chatMem: ChatMemory, title: String) {
        val (found, discLine) = IchLogic.parseOutput(output)
        val blocked = IchLogic.blockedFrom(
            listOf(title, chatMem.displayName),
            runCatching { memoryRepo.list().map { it.displayName } }.getOrDefault(emptyList()),
            CheckupState.state.value.items.map { it.entry.title },
        )
        val own = msgs.count { it.direction == Direction.OUT && TranscriptMerger.isCountable(it) }
        val now = System.currentTimeMillis()
        val ownDisc = discLine?.let { Disc.parse(it, own, now) }
        IchState.ensureLoaded(ctx)
        val before = IchState.profile.value
        IchState.update(ctx) { IchLogic.merge(it, found, ownDisc, chatMem.chatKey, blocked, IchLogic.chatTextsOf(chatMem), now) }
        val after = IchState.profile.value
        log("ICH: ${found.values.sumOf { it.size }} Kandidaten, Eintraege ${before.entries.size} zu ${after.entries.size}, aus ${after.chatCount} Chats (Inhalte nicht im Log).")
    }

    private fun page(p: ParsedPage) = p.rowCount

    private fun scrollInfoLine(ctl: ScrollController, merger: TranscriptMerger): String {
        val st = ctl.stats
        return "Modus ${ctl.mode}, Schritte ${st.effective}, Verluste ${st.losses} (Gegenschritt ${st.recoveredByCounterStep}, Luecken ${st.gapsAccepted}), " +
            "angeschnitten offen ${merger.incompleteCount()}"
    }

    private fun publishCounts(msgs: List<ChatMessage>, images: Int) {
        AgentState.update {
            it.copy(
                messageCount = msgs.count { m -> m.kind == Kind.TEXT || m.kind == Kind.IMAGE || m.kind == Kind.VOICE },
                imageCount = images,
            )
        }
    }

    /** One screenshot per page, cropping every fully visible image node that has not been captured yet. */
    private suspend fun captureImages(
        svc: ChatAccessibilityService,
        page: ParsedPage,
        canonical: List<ChatMessage>,
        s: AppSettings,
        alreadyCaptured: Int,
    ): Int {
        val todo = page.items.indices.filter { i ->
            val it = page.items[i]
            it.imageBounds != null && it.imageFullyVisible && canonical[i].imagePath == null && canonical[i].imageNote == null
        }
        if (todo.isEmpty()) return 0
        val room = s.maxImages - alreadyCaptured
        if (room <= 0) return 0
        AgentState.update { it.copy(phase = Phase.CAPTURING) }
        val shot = svc.screenshot()
        AgentState.update { it.copy(phase = Phase.SCROLLING) }
        if (shot is ShotResult.Fail) {
            log("Screenshot fehlgeschlagen: ${shot.reason}")
            todo.forEach { canonical[it].imageNote = "Screenshot fehlgeschlagen: ${shot.reason}" }
            return 0
        }
        val screen = (shot as ShotResult.Ok).bitmap
        var got = 0
        try {
            for (i in todo) {
                if (got >= room) break
                val msg = canonical[i]
                val crop = ImageTools.crop(screen, page.items[i].imageBounds!!) ?: continue
                try {
                    val scaled = ImageTools.downscale(crop, 1024)
                    val f = File(imageDir(), "img_${System.currentTimeMillis()}_${alreadyCaptured + got}.jpg")
                    ImageTools.saveJpeg(scaled, f)
                    msg.imagePath = f.absolutePath
                    if (s.ocrImages) {
                        msg.ocrText = Ocr.recognize(crop)?.take(800)
                    }
                    got++
                    if (scaled !== crop) scaled.recycle()
                } finally {
                    crop.recycle()
                }
            }
        } finally {
            screen.recycle()
        }
        if (got > 0) log("$got Bild(er) erfasst.")
        return got
    }
}
