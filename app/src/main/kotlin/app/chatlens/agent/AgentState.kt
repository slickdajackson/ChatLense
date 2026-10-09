package app.chatlens.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Phase { IDLE, STARTING, WAITING, NAVIGATING, SCROLLING, CAPTURING, CHOOSING, LLM, DONE, FAILED, CANCELLED }

data class AgentUiState(
    val phase: Phase = Phase.IDLE,
    val message: String = "Bereit.",
    val scrollDone: Int = 0,
    val scrollTotal: Int = 0,
    /** Target number of messages (0 means fixed scroll-count mode). */
    val targetMessages: Int = 0,
    /** Short status of scroll control (mode, losses, open clipped messages). */
    val scrollInfo: String = "",
    val messageCount: Int = 0,
    val imageCount: Int = 0,
    val transcript: String = "",
    val contextPreview: String = "",
    val result: String = "",
    /** Reply drafts (suggest mode only). They are never sent automatically. */
    val suggestions: List<String> = emptyList(),
    /** Name of the chat that the result and drafts refer to. */
    val resultChat: String = "",
    val resultInfo: String = "",
    /** Context level actually used by the local model, empty means not local or not loaded yet. */
    val contextInfo: String = "",
    /** Note about voice-message transcription for this run (skipped and why, or the result). Empty when there is nothing to report. */
    val voiceNote: String = "",
    val error: String = "",
    val dumpPaths: List<String> = emptyList(),
    /** Step bar of the running or most recently finished run (one chat). */
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

    /** Creates the plan for a new run. */
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
