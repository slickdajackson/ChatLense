package app.chatlens.agent

import app.chatlens.core.TaskMode

/** Schritte eines Laufs, wie sie in der Schrittleiste erscheinen. */
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
 * Fortschritt eines einzelnen Laufs (ein Chat). [index] ist der Schritt, der gerade laeuft (0-basiert); alles davor ist erledigt.
 * [llmLabel] benennt den Modellschritt je Aufgabe und Modell ("Gemma analysiert"). [tokens] ist -1, solange keine Zahl vorliegt
 * (das lokale Modell und die API werden nicht gestreamt, deshalb gibt es in 0.2.7 keinen Token-Zaehler).
 */
data class ProgressUi(
    val steps: List<StepKind> = emptyList(),
    val index: Int = 0,
    val llmLabel: String = StepKind.LLM.label,
    val runStartedAt: Long = 0,
    val stepStartedAt: Long = 0,
    val tokens: Int = -1,
    /** Zaehler des laufenden Schritts, z. B. "Nachricht 2 von 4" bei der Transkription. Leer, wenn es keinen gibt. */
    val detail: String = "",
) {
    val active: Boolean get() = steps.isNotEmpty()
}

/** Anzeige-Werte zu einem [ProgressUi] und der Phase. Reine Logik, deshalb ohne Android testbar. */
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

    /** Name des Modellschritts: "Gemma analysiert", "Modell schreibt Vorschläge", "API berät" ... */
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

    /** Setzt den laufenden Schritt. Ist der Schritt nicht im Plan, bleibt alles wie es war. */
    fun advance(p: ProgressUi, kind: StepKind, now: Long): ProgressUi {
        val i = p.steps.indexOf(kind)
        if (i < 0 || i == p.index) return p
        return p.copy(index = i, stepStartedAt = now, detail = "")
    }

    /** Nimmt einen Schritt aus dem Plan (zum Beispiel Sprachnachrichten, wenn der Chat keine hat). Der Index bleibt auf demselben Schritt. */
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

    /** Zahl der erledigten Schritte: bei Fertig alle, sonst alle vor dem laufenden. */
    fun completed(p: ProgressUi, end: End): Int = if (end == End.DONE) p.steps.size else p.index.coerceIn(0, p.steps.size)

    fun counter(p: ProgressUi, end: End): String = "${completed(p, end)}/${p.steps.size} erledigt"

    fun stepLabel(p: ProgressUi, kind: StepKind): String = if (kind == StepKind.LLM) p.llmLabel else kind.label

    fun currentLabel(p: ProgressUi): String = p.steps.getOrNull(p.index)?.let { stepLabel(p, it) } ?: ""

    /** Kopfzeile der Schrittleiste: laufend "Nachrichten sammeln", am Ende "Fertig", "Abgebrochen bei ..." oder "Fehler bei ...". */
    fun headline(p: ProgressUi, end: End): String = when (end) {
        End.RUNNING -> currentLabel(p)
        End.DONE -> "Fertig"
        End.CANCELLED -> "Abgebrochen bei: " + currentLabel(p).ifEmpty { "Start" }
        End.FAILED -> "Fehler bei: " + currentLabel(p).ifEmpty { "Start" }
    }

    fun elapsedSec(startedAt: Long, now: Long): Long = if (startedAt <= 0) 0 else ((now - startedAt) / 1000).coerceAtLeast(0)

    /** "42 s" oder "1:05 min". */
    fun formatElapsed(sec: Long): String = if (sec < 60) "$sec s" else "%d:%02d min".format(sec / 60, sec % 60)

    /** Zeile unter dem Modellbalken: "Modell arbeitet seit 42 s (Token: 120)". */
    fun llmDetail(p: ProgressUi, now: Long): String {
        val s = "seit " + formatElapsed(elapsedSec(p.stepStartedAt, now))
        return if (p.tokens >= 0) s + ", " + p.tokens + " Token" else s
    }

    /** Zeile fuer Downloads: "412 von 670 MB, 61 Prozent". */
    fun downloadLine(done: Long, total: Long): String =
        if (total <= 0) "${done / 1_000_000L} MB" else "${done / 1_000_000L} von ${total / 1_000_000L} MB, ${(done * 100 / total).coerceIn(0, 100)} Prozent"

    /** Zeile fuer Setup und Auto: "Chat 3 von 12". */
    fun queueLine(done: Int, total: Int): String = if (total > 0) "Chat ${(done + 1).coerceAtMost(total)} von $total" else ""

    /** Kurztext fuer die Benachrichtigung: "3/5 Nachrichten sammeln". */
    fun notificationLine(p: ProgressUi, end: End = End.RUNNING): String =
        if (!p.active) "" else "${p.index.coerceIn(0, p.steps.size) + 1}/${p.steps.size} ${currentLabel(p)}"
}
