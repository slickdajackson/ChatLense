package app.chatlens.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Phase { IDLE, STARTING, WAITING, NAVIGATING, SCROLLING, CAPTURING, CHOOSING, LLM, DONE, FAILED, CANCELLED }

data class AgentUiState(
    val phase: Phase = Phase.IDLE,
    val message: String = "Bereit.",
    val scrollDone: Int = 0,
    val scrollTotal: Int = 0,
    /** Zielmenge an Nachrichten (0 = Modus feste Scroll-Anzahl). */
    val targetMessages: Int = 0,
    /** Kurzstatus der Scrollsteuerung (Modus, Verluste, offene angeschnittene Nachrichten). */
    val scrollInfo: String = "",
    val messageCount: Int = 0,
    val imageCount: Int = 0,
    val transcript: String = "",
    val contextPreview: String = "",
    val result: String = "",
    /** Antwortentwuerfe (nur Vorschlag-Modus). Werden nie automatisch gesendet. */
    val suggestions: List<String> = emptyList(),
    /** Name des Chats, auf den sich Ergebnis und Entwuerfe beziehen. */
    val resultChat: String = "",
    val resultInfo: String = "",
    /** Tatsaechlich genutzte Kontextstufe des lokalen Modells, leer = nicht lokal oder noch nicht geladen. */
    val contextInfo: String = "",
    /** Hinweis zur Sprachnachrichten-Transkription dieses Laufs (uebersprungen und warum, oder Ergebnis). Leer, wenn nichts zu melden ist. */
    val voiceNote: String = "",
    val error: String = "",
    val dumpPaths: List<String> = emptyList(),
    /** Schrittleiste des laufenden oder zuletzt beendeten Laufs (ein Chat). */
    val progress: ProgressUi = ProgressUi(),
) {
    val running: Boolean
        get() = phase in setOf(Phase.STARTING, Phase.WAITING, Phase.NAVIGATING, Phase.SCROLLING, Phase.CAPTURING, Phase.CHOOSING, Phase.LLM)
}

object AgentState {
    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state

    fun update(f: (AgentUiState) -> AgentUiState) {
        _state.value = f(_state.value)
    }

    /** Legt den Plan eines neuen Laufs an. */
    fun beginProgress(steps: List<StepKind>, llmLabel: String = StepKind.LLM.label) {
        update { it.copy(progress = RunProgress.begin(steps, System.currentTimeMillis(), llmLabel)) }
    }

    fun step(kind: StepKind) {
        update { it.copy(progress = RunProgress.advance(it.progress, kind, System.currentTimeMillis())) }
    }

    fun stepDetail(text: String) {
        update { it.copy(progress = it.progress.copy(detail = text)) }
    }

    fun dropStep(kind: StepKind) {
        update { it.copy(progress = RunProgress.drop(it.progress, kind)) }
    }

    fun setLlmLabel(label: String) {
        update { it.copy(progress = it.progress.copy(llmLabel = label)) }
    }

    fun reset() {
        _state.value = AgentUiState(dumpPaths = _state.value.dumpPaths)
    }
}
