package app.chatlens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.chatlens.agent.AgentUiState
import app.chatlens.agent.AutoUiState
import app.chatlens.agent.ProgressUi
import app.chatlens.agent.RunProgress
import app.chatlens.agent.StepKind
import kotlinx.coroutines.delay

/**
 * Progress display for everything that is running: a spinner, a step bar with a counter ("3/5 erledigt") and the name of the current step.
 * For setup and auto, also "Chat i von n". In the model step, an indeterminate bar with elapsed seconds.
 * After cancel or error the bar freezes and names the step ("Abgebrochen bei: ..."), and the spinner disappears.
 * [nowMs] fixes the clock (for tests and render images); otherwise the display ticks every second.
 */
@Composable
fun RunStatus(agent: AgentUiState, auto: AutoUiState? = null, compact: Boolean = false, nowMs: Long? = null, modifier: Modifier = Modifier) {
    val p = agent.progress
    val end = RunProgress.end(agent.phase)
    val queueRunning = auto != null && auto.running && auto.total > 0
    val animate = LocalGlassAnimate.current
    val running = end == RunProgress.End.RUNNING && (agent.running || queueRunning)
    if (!p.active) {
        // No plan yet (start, waiting for the chat) or no run: a status line with a spinner while something is running.
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (agent.running || queueRunning) Spinner(animate)
            StatusPill(agent.message.ifEmpty { auto?.message.orEmpty() }.ifEmpty { "Bereit." }, agent.running || queueRunning, agent.error.isNotEmpty())
        }
        if (queueRunning) QueueLine(auto!!)
        return
    }
    val stepKind = p.steps.getOrNull(p.index)
    val timed = running && (stepKind == StepKind.LLM || stepKind == StepKind.LOAD_MODEL || stepKind == StepKind.TRANSCRIBE)
    val now by produceState(nowMs ?: System.currentTimeMillis(), timed, nowMs, animate) {
        if (nowMs != null || !animate) { value = nowMs ?: System.currentTimeMillis(); return@produceState }
        while (timed) { value = System.currentTimeMillis(); delay(1000) }
    }
    val head = RunProgress.headline(p, end)
    val counter = RunProgress.counter(p, end)
    val color = when (end) {
        RunProgress.End.RUNNING -> GlassColors.Accent
        RunProgress.End.DONE -> GlassColors.Ok
        RunProgress.End.CANCELLED -> GlassColors.Warn
        RunProgress.End.FAILED -> GlassColors.Bad
    }
    Column(modifier.semantics { contentDescription = "$head. $counter" }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (running) Spinner(animate) else Box(Modifier.size(14.dp).background(color, CircleShape))
            Column(Modifier.weight(1f)) {
                Text(head, color = GlassColors.Text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(counter, color = GlassColors.TextDim, style = MaterialTheme.typography.bodySmall)
            }
        }
        StepBar(p, end, color)
        if (timed) {
            if (animate) LinearProgressIndicator(Modifier.fillMaxWidth(), color = GlassColors.Accent, trackColor = Color(0x44FFFFFF))
            else LinearProgressIndicator(progress = { 0.35f }, Modifier.fillMaxWidth(), color = GlassColors.Accent, trackColor = Color(0x44FFFFFF))
            Text(
                if (stepKind == StepKind.TRANSCRIBE) "Sprachnachrichten: " + p.detail.ifEmpty { "starte" } + ", " + RunProgress.llmDetail(p, now)
                else (if (stepKind == StepKind.LOAD_MODEL) "Modell wird geladen, " else "arbeitet, ") + RunProgress.llmDetail(p, now),
                color = GlassColors.TextDim, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (agent.voiceNote.isNotEmpty()) Text(agent.voiceNote, color = GlassColors.Warn, style = MaterialTheme.typography.bodySmall)
        if (auto != null && (auto.running || auto.total > 0) && auto.kind != null) QueueLine(auto)
        if (!compact) {
            p.steps.forEachIndexed { i, k ->
                val done = i < RunProgress.completed(p, end)
                val cur = i == p.index && end != RunProgress.End.DONE
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(if (done) GlassColors.Ok else if (cur) color else Color(0x66FFFFFF), CircleShape))
                    Text(
                        RunProgress.stepLabel(p, k) + (if (done) ", erledigt" else ""),
                        color = if (cur) GlassColors.Text else GlassColors.TextDim,
                        style = MaterialTheme.typography.bodySmall, fontWeight = if (cur) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        if (agent.message.isNotEmpty()) Text(agent.message, color = GlassColors.TextDim, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun QueueLine(auto: AutoUiState) {
    val line = RunProgress.queueLine(auto.done, auto.total)
    if (line.isEmpty()) return
    Text(
        line + (if (auto.current.isNotEmpty()) ": " + auto.current else "") + "  (" + auto.done + " fertig)",
        color = GlassColors.Text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
    )
}

/** Spinner. Without animation (render images), a fixed quarter circle, so nothing hangs. */
@Composable
fun Spinner(animate: Boolean, modifier: Modifier = Modifier.size(22.dp)) {
    val d = Modifier.semantics { contentDescription = "Läuft" }
    if (animate) CircularProgressIndicator(modifier.then(d), color = GlassColors.Accent, trackColor = Color(0x44FFFFFF), strokeWidth = 3.dp)
    else CircularProgressIndicator(progress = { 0.7f }, modifier.then(d), color = GlassColors.Accent, trackColor = Color(0x44FFFFFF), strokeWidth = 3.dp)
}

@Composable
private fun StepBar(p: ProgressUi, end: RunProgress.End, current: Color) {
    val done = RunProgress.completed(p, end)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        p.steps.forEachIndexed { i, _ ->
            val c = when {
                i < done -> GlassColors.Ok
                i == p.index && end != RunProgress.End.DONE -> current
                else -> Color(0x55FFFFFF)
            }
            Box(Modifier.weight(1f).height(6.dp).background(c, RoundedCornerShape(3.dp)))
        }
    }
}

/**
 * Download progress (Parakeet, models): spinner, a bar with percent and megabytes, verification, a clear error with retry,
 * and pause with resume. Same design as [RunStatus].
 */
@Composable
fun DownloadStatus(ui: app.chatlens.models.DlUi, name: String, onRetry: () -> Unit, onPause: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val animate = LocalGlassAnimate.current
    val running = ui.status == app.chatlens.models.DlStatus.RUNNING
    val verifying = ui.status == app.chatlens.models.DlStatus.VERIFYING
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (running || verifying) Spinner(animate) else Box(
                Modifier.size(14.dp).background(
                    when (ui.status) {
                        app.chatlens.models.DlStatus.DONE -> GlassColors.Ok
                        app.chatlens.models.DlStatus.FAILED -> GlassColors.Bad
                        else -> GlassColors.Warn
                    }, CircleShape,
                ),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    when (ui.status) {
                        app.chatlens.models.DlStatus.RUNNING -> "$name wird geladen"
                        app.chatlens.models.DlStatus.VERIFYING -> "$name wird geprüft"
                        app.chatlens.models.DlStatus.PAUSED -> "$name pausiert"
                        app.chatlens.models.DlStatus.FAILED -> "$name: Fehler"
                        app.chatlens.models.DlStatus.DONE -> "$name geladen und geprüft"
                        else -> name
                    },
                    color = GlassColors.Text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                )
                if (ui.total > 0) Text(RunProgress.downloadLine(ui.done, ui.total), color = GlassColors.TextDim, style = MaterialTheme.typography.bodySmall)
            }
        }
        if ((running || verifying || ui.status == app.chatlens.models.DlStatus.PAUSED) && ui.total > 0) {
            LinearProgressIndicator(progress = { (ui.done.toFloat() / ui.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = GlassColors.Accent, trackColor = Color(0x44FFFFFF))
        }
        if (ui.status == app.chatlens.models.DlStatus.FAILED) {
            Text(app.chatlens.asr.VoiceAutoDownload.failedText(ui.message), color = GlassColors.Bad, style = MaterialTheme.typography.bodySmall)
        } else if (ui.message.isNotEmpty()) {
            Text(ui.message, color = GlassColors.TextDim, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.status == app.chatlens.models.DlStatus.FAILED) androidx.compose.material3.Button(onClick = onRetry) { Text("Wiederholen") }
            if (ui.status == app.chatlens.models.DlStatus.PAUSED) androidx.compose.material3.Button(onClick = onRetry) { Text("Fortsetzen") }
            if (running) androidx.compose.material3.OutlinedButton(onClick = onPause) { Text("Pause") }
            if (running || ui.status == app.chatlens.models.DlStatus.PAUSED) androidx.compose.material3.OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
        }
    }
}
