package app.chatlens.agent

import android.content.Context
import app.chatlens.auto.AutoQueue
import app.chatlens.auto.AutoQueueRunner
import app.chatlens.auto.FatalAutoException
import app.chatlens.auto.ItemStatus
import app.chatlens.auto.PausedAutoException
import app.chatlens.auto.QueueKind
import app.chatlens.core.ScrollRunConfig
import app.chatlens.core.StopMode
import app.chatlens.core.TaskMode
import app.chatlens.data.AppLog
import app.chatlens.data.AppSettings
import app.chatlens.data.MemoryRepo
import app.chatlens.data.SettingsRepo
import app.chatlens.checkup.CheckupSelection
import app.chatlens.match.ChatListEntry
import app.chatlens.match.NameMatcher
import app.chatlens.match.ChatListSelector
import app.chatlens.match.PinnedMode
import app.chatlens.parse.ChatParser
import app.chatlens.service.ChatAccessibilityService
import app.chatlens.service.DebugDumper
import app.chatlens.memory.ChatMemory
import app.chatlens.memory.IchLogic
import app.chatlens.memory.SelfAnalysis
import app.chatlens.llm.LlmFactory
import app.chatlens.auto.LlmGate
import app.chatlens.llm.LlmRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime
import kotlin.coroutines.coroutineContext

class QueueItemView(val title: String, val status: ItemStatus, val error: String, val info: String)

data class AutoUiState(
    val running: Boolean = false,
    val kind: QueueKind? = null,
    val message: String = "",
    val items: List<QueueItemView> = emptyList(),
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    /** Gelesene Chatliste (zum Ankreuzen im Auto-Modus). */
    val listEntries: List<ChatListEntry> = emptyList(),
    /** Uebersicht der angelegten Chat-Profile nach dem Lauf. */
    val overview: List<ChatMemory> = emptyList(),
    val finished: Boolean = false,
    val resumable: Boolean = false,
)

object AutoState {
    private val _state = MutableStateFlow(AutoUiState())
    val state: StateFlow<AutoUiState> = _state
    fun update(f: (AutoUiState) -> AutoUiState) { _state.value = f(_state.value) }
}

/** Auftrag fuer den Dienst. */
sealed class AutoStart {
    /** Setup: neueste [count] Chats aus der Chatliste, je [target] Nachrichten, direkt aus der Liste geoeffnet. */
    class Setup(
        val count: Int, val target: Int, val includeGroups: Boolean, val pinnedCounts: Boolean,
        /** Ab 0.2.5: die im Checkup-Menue gewaehlten Chats (Listenreihenfolge). null: wie bisher die neuesten [count] aus der Liste. */
        val selectedTitles: List<String>? = null,
    ) : AutoStart()

    /** Checkup: nur die Chatliste lesen (bis zu [limit] Chats), keinen Chat oeffnen. Ergebnis ist das Auswahlmenue. */
    class Checkup(val limit: Int) : AutoStart()

    /** Auto-Modus: die Namen der Liste, inkrementell (nur neue Nachrichten seit dem Anker), Namenssuche mit Nachfrage bei Unschaerfe. */
    class Names(val titles: List<String>, val target: Int) : AutoStart()

    /**
     * Selbstanalyse: die im Checkup gewaehlten Chats [titles] (nur aus der Checkup-Liste), je [perChat] Nachrichten, nur die eigenen Nachrichten werden ausgewertet.
     * [focus] ist der freie Auftragstext des Nutzers (nur Schwerpunkt).
     */
    class SelfScan(val titles: List<String>, val perChat: Int, val focus: String) : AutoStart()

    /** Wiederaufnahme der gespeicherten Warteschlange. */
    class Resume(val retryFailed: Boolean) : AutoStart()

    /** Nur die Chatliste lesen (zum Ankreuzen). */
    object ReadList : AutoStart()
}

/**
 * Setup und Auto-Modus: arbeitet Chats seriell ab. Je Chat ein Lesevorgang (ChatRunner) und genau ein Modellaufruf
 * (LlmGate), Ergebnis ist das verschluesselte Gedaechtnis. Der Zustand der Warteschlange wird nach jedem Schritt verschluesselt
 * gespeichert, damit nach einem Abbruch fortgesetzt werden kann.
 */
class AutoRunner(private val ctx: Context, private val repo: SettingsRepo) {

    private val mem get() = MemoryRepo.get(ctx)

    private fun log(msg: String) {
        AppLog.i("AUTO: $msg")
        AutoState.update { it.copy(message = msg) }
        AgentState.update { it.copy(message = msg) }
    }

    private fun publish(q: AutoQueue, current: String = "") {
        AutoState.update {
            it.copy(
                kind = q.kind,
                items = q.items.map { i -> QueueItemView(i.title, i.status, i.error, i.info) },
                done = q.done, total = q.total,
                current = current.ifEmpty { q.items.firstOrNull { i -> i.status == ItemStatus.LAEUFT }?.title.orEmpty() },
            )
        }
    }

    private fun services(s: AppSettings): Triple<ChatAccessibilityService, app.chatlens.profile.SelectorProfile, WhatsAppNavigator> {
        val svc = ChatAccessibilityService.instance
            ?: throw AgentException("Bedienungshilfe ChatLens ist nicht aktiv (Systemeinstellungen, Bedienungshilfen).")
        val profile = ProfileStore.load(ctx)
        val parser = ChatParser(profile, svc.density, s.groupChat)
        val nav = WhatsAppNavigator(svc, profile, parser, searchWaitMs = s.searchWaitMs.toLong(), diagDump = { DebugDumper.dumpNow(ctx, true, tag = "tree-auto")?.name })
        return Triple(svc, profile, nav)
    }

    suspend fun run(start: AutoStart, s: AppSettings) {
        AutoState.update { AutoUiState(running = true, message = "Starte ...", listEntries = it.listEntries) }
        app.chatlens.service.ScreenAwake.acquire()
        try {
            when (start) {
                AutoStart.ReadList -> readList(s)
                is AutoStart.Checkup -> checkup(start, s)
                is AutoStart.Setup -> setup(start, s)
                is AutoStart.Names -> names(start, s)
                is AutoStart.SelfScan -> selfAnalysis(start, s)
                is AutoStart.Resume -> resume(start, s)
            }
        } finally {
            app.chatlens.service.ScreenAwake.release()
            AutoState.update { it.copy(running = false, resumable = mem.loadQueue()?.finished == false) }
        }
    }

    private suspend fun readList(s: AppSettings) {
        val (_, _, nav) = services(s)
        AgentState.update { it.copy(phase = Phase.NAVIGATING) }
        log("Pruefe, ob WhatsApp vorn ist ...")
        nav.guard.ensure(true, "Chatliste lesen")
        delay(800)
        val entries = nav.readChatList(60) { log(it) }
        AutoState.update { it.copy(listEntries = entries, message = "Chatliste gelesen: ${entries.size} Eintraege.") }
        AgentState.update { it.copy(progress = ProgressUi(), phase = Phase.DONE, message = "Chatliste gelesen: ${entries.size} Einträge.") }
    }

    private suspend fun checkup(st: AutoStart.Checkup, s: AppSettings) {
        val (_, _, nav) = services(s)
        AgentState.update { it.copy(phase = Phase.NAVIGATING) }
        val limit = st.limit.coerceIn(1, 200)
        log("Checkup: lese bis zu $limit Chats aus der Chatliste (kein Chat wird geoeffnet).")
        nav.guard.ensure(true, "Checkup")
        delay(800)
        val scan = nav.checkupScan(limit) { log(it) }
        if (scan.entries.isEmpty()) throw AgentException("Checkup: Es wurde kein Chat in der Chatliste gelesen (${scan.pages} Seiten). Debug-Baum der Chatliste exportieren.")
        val prev = CheckupState.stored ?: runCatching { mem.loadCheckup() }.getOrNull()
        val items = CheckupSelection.reconcile(scan.entries, prev)
        val now = System.currentTimeMillis()
        val warn = scan.warning(limit)
        val note = "${scan.entries.size} Chats gelesen (${scan.reason.text}), ${items.count { it.selected }} vorgewählt, ${items.count { it.isNew }} neu." + (warn?.let { " WARNUNG: $it" } ?: "")
        CheckupState.restore(prev)
        CheckupState.applyScan(items, now, note)
        runCatching { CheckupState.stored?.let { mem.saveCheckup(it) } }
        if (scan.reason == app.chatlens.checkup.StopReason.SCROLL_FAILED) {
            // Die erste Seite bleibt waehlbar, der Lauf gilt aber als Fehler: ein Listenende ist nicht erwiesen
            AutoState.update { it.copy(listEntries = scan.entries, message = "Checkup unvollstaendig: $note") }
            throw AgentException("Checkup unvollstaendig: Scrollen nicht moeglich, nur ${scan.entries.size} Zeilen gelesen. ${warn ?: ""}")
        }
        log("Checkup fertig: $note")
        AutoState.update { it.copy(listEntries = scan.entries, message = "Checkup fertig: $note") }
        AgentState.update { it.copy(progress = ProgressUi(), phase = Phase.DONE, message = "Checkup: $note") }
    }

    private suspend fun setup(st: AutoStart.Setup, s: AppSettings) {
        val (_, _, nav) = services(s)
        AgentState.update { it.copy(phase = Phase.NAVIGATING) }
        checkRate(s)
        log("Pruefe, ob WhatsApp vorn ist ...")
        nav.guard.ensure(true, "Setup")
        delay(800)
        val picked = st.selectedTitles
        // Reihenfolge erzwingen: Setup arbeitet nur Chats aus der Checkup-Liste ab. Kein Rueckfall mehr auf die "obersten N" der Live-Liste.
        val cuTitles = CheckupState.state.value.items.map { it.entry.title }
        Workflow.lockReason("das Setup", cuTitles.size, picked?.size ?: 0)?.let { throw AgentException(it) }
        if (picked != null) {
            val titles = Workflow.fromCheckup(picked, cuTitles)
            if (titles.isEmpty()) throw AgentException("Keine Chats ausgewählt. Zuerst den Checkup ausführen und Chats ankreuzen.")
            log("Setup mit ${titles.size} im Checkup gewählten Chats (nicht einfach die obersten).")
            val q = AutoQueue.of(QueueKind.SETUP, titles, st.target, System.currentTimeMillis(), fromList = true)
            mem.saveQueue(q)
            process(q, s)
            return
        }
        log("Lese die Chatliste (ohne die Suche anzutippen) ...")
        val entries = nav.readChatList((st.count * 2 + 10).coerceAtMost(120)) { log(it) }
        val chosen = ChatListSelector.select(
            entries, st.count, st.includeGroups,
            if (st.pinnedCounts) PinnedMode.COUNT_NORMALLY else PinnedMode.EXCLUDE, LocalDateTime.now(),
        )
        if (chosen.isEmpty()) throw AgentException("Aus der Chatliste konnte kein Chat gewählt werden (${entries.size} Zeilen gelesen). Debug-Baum der Chatliste exportieren.")
        AutoState.update { it.copy(listEntries = entries) }
        log("${chosen.size} Chats gewählt (neueste zuerst, Archiv ausgeschlossen, Gruppen ${if (st.includeGroups) "eingeschlossen" else "ausgeschlossen"}).")
        val q = AutoQueue.of(QueueKind.SETUP, chosen.map { it.title }, st.target, System.currentTimeMillis(), fromList = true)
        mem.saveQueue(q)
        process(q, s)
    }

    private suspend fun selfAnalysis(st: AutoStart.SelfScan, s: AppSettings) {
        services(s)
        checkRate(s)
        val cuTitles = CheckupState.state.value.items.map { it.entry.title }
        Workflow.lockReason("die Selbstanalyse", cuTitles.size, 0, needsSelection = false)?.let { throw AgentException(it) }
        val titles = Workflow.fromCheckup(st.titles, cuTitles)
        if (titles.isEmpty()) throw AgentException("Keine Chats für die Selbstanalyse. Zuerst den Checkup ausführen und Chats ankreuzen.")
        SelfState.clear(ctx)
        val q = AutoQueue.of(QueueKind.SELF, titles, st.perChat, System.currentTimeMillis(), fromList = true)
        q.note = st.focus
        mem.saveQueue(q)
        AppLog.i("AUTO: Selbstanalyse mit ${titles.size} Chats, je ${st.perChat} Nachrichten, Schwerpunkttext ${st.focus.length} Zeichen (Text nicht im Log).")
        process(q, s)
    }

    private suspend fun names(st: AutoStart.Names, s: AppSettings) {
        services(s)
        checkRate(s)
        Workflow.lockReason("der Auto-Modus", CheckupState.state.value.items.size, 0, needsSelection = false)?.let { throw AgentException(it) }
        val titles = st.titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (titles.isEmpty()) throw AgentException("Keine Chatnamen angegeben.")
        val q = AutoQueue.of(QueueKind.AUTO, titles, st.target, System.currentTimeMillis(), fromList = false)
        mem.saveQueue(q)
        process(q, s)
    }

    private suspend fun resume(st: AutoStart.Resume, s: AppSettings) {
        services(s)
        val q = mem.loadQueue() ?: throw AgentException("Keine gespeicherte Warteschlange zum Fortsetzen.")
        if (st.retryFailed) q.retryFailed()
        if (q.nextPending() == null) throw AgentException("Nichts mehr offen in der gespeicherten Warteschlange.")
        log("Setze fort: ${q.count(ItemStatus.FERTIG)} fertig, ${q.count(ItemStatus.WARTET)} offen.")
        process(q, s)
    }

    private fun selfBlocked(extra: String): Set<String> = IchLogic.blockedFrom(
        listOf(extra),
        runCatching { mem.list().map { it.displayName } }.getOrDefault(emptyList()),
        CheckupState.state.value.items.map { it.entry.title },
    )

    /** Gesamtbild: ein Modellaufruf ueber die abstrakten Teilergebnisse (keine Chatinhalte), bei Fehler rechnerisch. Ergebnis ist nur ein Vorschlag. */
    private suspend fun finishSelf(q: AutoQueue, s: AppSettings, ok: Int, err: Int) {
        SelfState.load(ctx)
        val partials = SelfState.partials.value
        if (partials.isEmpty()) {
            AutoState.update { it.copy(finished = true, message = "Selbstanalyse ohne Ergebnis: $err Chats mit Fehler, keine Teilergebnisse.") }
            AgentState.update { it.copy(phase = Phase.FAILED, message = "Selbstanalyse ohne Ergebnis.") }
            return
        }
        AgentState.update { it.copy(phase = Phase.LLM, message = "Gesamtbild aus ${partials.size} Teilergebnissen ...") }
        AgentState.step(StepKind.LLM)
        val blocked = selfBlocked("")
        val merged = runCatching {
            val backend = LlmFactory.create(ctx, s)
            LlmGate.exclusive { backend.generate(LlmRequest(system = SelfAnalysis.mergeSystem(), user = SelfAnalysis.mergeUser(partials))) }.text
        }.getOrNull()
        val proposal = SelfAnalysis.proposal(partials, merged, blocked, System.currentTimeMillis())
        SelfState.setProposal(ctx, proposal)
        AppLog.i("AUTO: Selbstanalyse fertig, ${partials.size} Teilergebnisse, ${proposal.entries.size} Eintraege im Vorschlag, Zusammenfuehren ${if (merged != null) "durch das Modell" else "rechnerisch"}.")
        AutoState.update { it.copy(finished = true, message = "Selbstanalyse fertig: $ok Chats, $err mit Fehler. Vorschlag für das Ich-Profil wartet auf deine Bestätigung (Startseite).") }
        AgentState.update { it.copy(phase = Phase.DONE, message = "Selbstanalyse fertig. Vorschlag wartet auf Bestätigung.") }
    }

    private fun checkRate(s: AppSettings) {
        val now = System.currentTimeMillis()
        val recent = repo.runTimestamps().count { now - it < 3_600_000L }
        if (recent >= s.maxRunsPerHour) {
            throw AgentException("Ratenlimit: $recent Läufe in der letzten Stunde (Maximum ${s.maxRunsPerHour}). Limit in den Einstellungen anheben oder später erneut starten.")
        }
        repo.recordRun(now)
    }

    private suspend fun process(q: AutoQueue, s: AppSettings) {
        if (s.backend == app.chatlens.data.BackendChoice.EXTRACT_ONLY) {
            throw AgentException("Für Setup und Auto-Modus in den Einstellungen ein Modell wählen (empfohlen: lokal Gemma). \"Nur Auslesen\" legt kein Gedächtnis an.")
        }
        val runner = ChatRunner(ctx, repo)
        AgentState.update { it.copy(phase = Phase.SCROLLING) }
        publish(q)
        try {
            AutoQueueRunner.run(
                q, { System.currentTimeMillis() },
                onChange = {
                    runCatching { mem.saveQueue(it) }
                    publish(it)
                },
                maxConsecutiveFailures = 2,
                onFailure = { item, n ->
                    AppLog.e("AUTO: FEHLER bei \"${item.title}\" (Nr. $n in Folge): ${item.error}")
                    log("Fehler bei Chat \"${item.title}\": ${item.error.take(160)}")
                },
            ) { item ->
                coroutineContext.ensureActive()
                if (ChatAccessibilityService.instance == null) throw FatalAutoException("Bedienungshilfe wurde beendet.")
                val idx = q.items.indexOf(item) + 1
                log("Chat $idx von ${q.total}: ${item.title}")
                AgentState.reset()
                val self = q.kind == QueueKind.SELF
                val cfg = ScrollRunConfig(
                    chatTitle = item.title, chatAlreadyOpen = false, scrollCount = 0, instruction = if (self) q.note else "",
                    stopMode = StopMode.TARGET, targetMessages = q.targetPerChat,
                    fromList = q.fromList, task = if (self) TaskMode.SELF else TaskMode.MEMORY, incremental = q.kind == QueueKind.AUTO, inQueue = true,
                )
                val r = runner.run(cfg, s)
                if (self) {
                    val own = r.messages.count { it.direction == app.chatlens.core.Direction.OUT && app.chatlens.parse.TranscriptMerger.isCountable(it) }
                    if (own == 0) throw AgentException("Keine eigenen Nachrichten gelesen.")
                    val blocked = selfBlocked(item.title)
                    SelfState.addPartial(ctx, SelfAnalysis.partialFrom(r.text, blocked, emptyList(), own, NameMatcher.normalize(item.title), System.currentTimeMillis()))
                    return@run "$own eigene Nachrichten ausgewertet"
                }
                val n = r.messages.count { app.chatlens.parse.TranscriptMerger.isCountable(it) }
                // Kein Erfolg ohne gelesene Nachrichten: sonst wuerde ein Chat stillschweigend als fertig zaehlen
                if (n == 0) throw AgentException("Keine Nachrichten gelesen (${r.info.take(160)}).")
                "$n Nachrichten gelesen, ${r.memory?.generatedLength ?: 0} Zeichen Gedächtnis"
            }
            val ok = q.count(ItemStatus.FERTIG)
            val err = q.count(ItemStatus.FEHLER)
            if (q.kind == QueueKind.SELF) {
                finishSelf(q, s, ok, err)
                if (q.finished && err == 0) mem.clearQueue() else mem.saveQueue(q)
                return
            }
            val overview = mem.list()
            AutoState.update { it.copy(finished = true, overview = overview, message = "Fertig: $ok Chats angelegt oder aktualisiert, $err mit Fehler.") }
            AgentState.update { it.copy(phase = Phase.DONE, message = "Fertig: $ok Chats, $err Fehler.") }
            mem.saveQueue(q)
            if (q.finished && err == 0) mem.clearQueue()
        } catch (e: FatalAutoException) {
            throw AgentException(e.message.orEmpty())
        } catch (e: PausedAutoException) {
            AppLog.w("AUTO: ${e.message}")
            AutoState.update { it.copy(message = "Pausiert nach Fehlern.") }
            throw AgentException(e.message.orEmpty())
        }
    }
}
