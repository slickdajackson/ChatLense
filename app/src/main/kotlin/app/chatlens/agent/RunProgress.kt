package app.chatlens.agent

import app.chatlens.core.TaskMode

/** Steps of a run, as they appear in the step bar. */
enum class StepKind(val label: String) {
    OPEN_CHAT("Chat öffnen"),
    COLLECT("Nachrichten sammeln"),
    TRANSCRIBE("Sprachnachrichten transkribieren"),
    CHOOSE("Auswahl treffen"),
    LOAD_MODEL("Modell laden"),
    LLM("Modell arbeitet"),
    SAVE("Ergebnis speichern"),
}

/**
 * Progress of a single run (one chat). [index] is the step currently running (0-based); everything before it is done.
 * [llmLabel] names the model step by task and model ("Gemma analysiert"). [tokens] is -1 until a number is available
 * (the local model and the API are not streamed, so 0.2.7 has no token counter).
 */
data class ProgressUi(
    val steps: List<StepKind> = emptyList(),
    val index: Int = 0,
    val llmLabel: String = StepKind.LLM.label,
    val runStartedAt: Long = 0,
    val stepStartedAt: Long = 0,
    val tokens: Int = -1,
    /** Counter for the current step, for example "Nachricht 2 von 4" during transcription. Empty when there is none. */
    val detail: String = "",
) {
    val active: Boolean get() = steps.isNotEmpty()
}

/** Display values for a [ProgressUi] and the phase. Pure logic, so testable without Android. */
object RunProgress {
    fun plan(task: TaskMode, chatAlreadyOpen: Boolean, askPrompt: Boolean, voiceEnabled: Boolean, localModel: Boolean): List<StepKind> = buildList {
        if (!chatAlreadyOpen) add(StepKind.OPEN_CHAT)
        add(StepKind.COLLECT)
        if (voiceEnabled) add(StepKind.TRANSCRIBE)
        if (askPrompt && task == TaskMode.ANALYSE) add(StepKind.CHOOSE)
        if (localModel) add(StepKind.LOAD_MODEL)
        add(StepKind.LLM)
        if (task == TaskMode.MEMORY) add(StepKind.SAVE)
    }

    /** Name of the model step: "Gemma analysiert", "Modell schreibt Vorschläge", "API berät" ... */
    fun llmLabel(task: TaskMode, backendName: String, local: Boolean): String {
        val who = when {
            !local -> "API"
            backendName.contains("gemma", ignoreCase = true) -> "Gemma"
            backendName.contains("qwen", ignoreCase = true) -> "Qwen"
            else -> "Modell"
        }
        val verb = when (task) {
            TaskMode.ANALYSE -> "analysiert"
            TaskMode.SUGGEST -> "schreibt Vorschläge"
            TaskMode.ADVISE -> "berät"
            TaskMode.MEMORY -> "erstellt den Steckbrief"
            TaskMode.SELF -> "analysiert deinen Stil"
        }
        return "$who $verb"
    }

    fun begin(steps: List<StepKind>, now: Long, llmLabel: String = StepKind.LLM.label) =
        ProgressUi(steps, 0, llmLabel, now, now)

    /** Sets the current step. If the step is not in the plan, everything stays as it was. */
    fun advance(p: ProgressUi, kind: StepKind, now: Long): ProgressUi {
        val i = p.steps.indexOf(kind)
        if (i < 0 || i == p.index) return p
        return p.copy(index = i, stepStartedAt = now, detail = "")
    }

    /** Removes a step from the plan (for example voice messages when the chat has none). The index stays on the same step. */
    fun drop(p: ProgressUi, kind: StepKind): ProgressUi {
        val i = p.steps.indexOf(kind)
        if (i < 0) return p
        val newIdx = if (i < p.index) p.index - 1 else p.index
        val steps = p.steps.filterIndexed { j, _ -> j != i }
        return p.copy(steps = steps, index = newIdx.coerceIn(0, (steps.size - 1).coerceAtLeast(0)))
    }

    enum class End { RUNNING, DONE, FAILED, CANCELLED }

    fun end(phase: Phase): End = when (phase) {
        Phase.DONE -> End.DONE
        Phase.FAILED -> End.FAILED
        Phase.CANCELLED -> End.CANCELLED
        else -> End.RUNNING
    }

    /** Number of completed steps: all of them when finished, otherwise all before the current one. */
    fun completed(p: ProgressUi, end: End): Int = if (end == End.DONE) p.steps.size else p.index.coerceIn(0, p.steps.size)

    fun counter(p: ProgressUi, end: End): String = "${completed(p, end)}/${p.steps.size} erledigt"

    fun stepLabel(p: ProgressUi, kind: StepKind): String = if (kind == StepKind.LLM) p.llmLabel else kind.label

    fun currentLabel(p: ProgressUi): String = p.steps.getOrNull(p.index)?.let { stepLabel(p, it) } ?: ""

    /** Headline of the step bar: while running "Nachrichten sammeln", at the end "Fertig", "Abgebrochen bei ..." or "Fehler bei ...". */
    fun headline(p: ProgressUi, end: End): String = when (end) {
        End.RUNNING -> currentLabel(p)
        End.DONE -> "Fertig"
        End.CANCELLED -> "Abgebrochen bei: " + currentLabel(p).ifEmpty { "Start" }
        End.FAILED -> "Fehler bei: " + currentLabel(p).ifEmpty { "Start" }
    }

    fun elapsedSec(startedAt: Long, now: Long): Long = if (startedAt <= 0) 0 else ((now - startedAt) / 1000).coerceAtLeast(0)

    /** "42 s" or "1:05 min". */
    fun formatElapsed(sec: Long): String = if (sec < 60) "$sec s" else "%d:%02d min".format(sec / 60, sec % 60)

    /** Line under the model bar: "Modell arbeitet seit 42 s (Token: 120)". */
    fun llmDetail(p: ProgressUi, now: Long): String {
        val s = "seit " + formatElapsed(elapsedSec(p.stepStartedAt, now))
        return if (p.tokens >= 0) s + ", " + p.tokens + " Token" else s
    }

    /** Line for downloads: "412 von 670 MB, 61 Prozent". */
    fun downloadLine(done: Long, total: Long): String =
        if (total <= 0) "${done / 1_000_000L} MB" else "${done / 1_000_000L} von ${total / 1_000_000L} MB, ${(done * 100 / total).coerceIn(0, 100)} Prozent"

    /** Line for setup and auto: "Chat 3 von 12". */
    fun queueLine(done: Int, total: Int): String = if (total > 0) "Chat ${(done + 1).coerceAtMost(total)} von $total" else ""

    /** Short text for the notification: "3/5 Nachrichten sammeln". */
    fun notificationLine(p: ProgressUi, end: End = End.RUNNING): String =
        if (!p.active) "" else "${p.index.coerceIn(0, p.steps.size) + 1}/${p.steps.size} ${currentLabel(p)}"
}
