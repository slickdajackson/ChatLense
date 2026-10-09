package app.chatlens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.chatlens.agent.AgentState
import app.chatlens.agent.Phase
import app.chatlens.data.AppSettings
import app.chatlens.service.ChatAccessibilityService

/** State of the floating dot, with a hint and buttons if it is not running because a permission is missing. */
@Composable
fun DotStatusSection(
    settings: AppSettings, overlayGranted: Boolean, onSettings: (AppSettings) -> Unit,
    onOpenOverlay: () -> Unit, onOpenA11y: () -> Unit,
) {
    val a11y by ChatAccessibilityService.connected.collectAsState()
    val s = app.chatlens.agent.DotStatus.of(settings.overlayEnabled, overlayGranted, a11y)
    Section(s.title, highlight = !s.ok) {
        s.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = if (s.ok) GlassColors.TextDim else GlassColors.Warn) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.fixes.forEach { f ->
                when (f) {
                    app.chatlens.agent.DotFix.ENABLE_SWITCH -> Button(onClick = { onSettings(settings.copy(overlayEnabled = true)) }) { Text("Punkt einschalten") }
                    app.chatlens.agent.DotFix.OPEN_OVERLAY_PERMISSION -> Button(onClick = onOpenOverlay) { Text("Erlaubnis öffnen") }
                    app.chatlens.agent.DotFix.OPEN_A11Y -> OutlinedButton(onClick = onOpenA11y) { Text("Bedienungshilfen öffnen") }
                }
            }
        }
    }
}

/**
 * Last run: status with a progress display, NOTAUS, result, reply drafts, extracted history, and the prompt that was sent.
 * Formerly on the Analysis tab (job section); the run itself now starts from the dot, and the result appears here.
 */
@Composable
fun LastRunSection(
    settings: AppSettings,
    onCancel: () -> Unit,
    onCopy: (String, String) -> Unit,
    onShareText: (String) -> Unit,
    onInsert: (String) -> Unit,
    onSend: (String) -> Unit,
) {
    val st by AgentState.state.collectAsState()
    var showTranscript by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }
    var confirmSend by remember { mutableStateOf<String?>(null) }
    val hasRun = st.progress.active || st.running || st.result.isNotEmpty() || st.suggestions.isNotEmpty() || st.error.isNotEmpty()
    if (!hasRun) return

    Section("Letzter Lauf") {
        RunStatus(st, compact = false)
        if (st.running) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = st.phase == Phase.WAITING, onClick = { app.chatlens.agent.AgentController.requestReadNow() }) { Text("Jetzt lesen") }
                Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = GlassColors.Bad, contentColor = GlassColors.OnBad)) { Text("NOTAUS") }
            }
        }
        if (st.targetMessages > 0) {
            Text("Ziel: ${st.targetMessages} Nachrichten, erfasst: ${st.messageCount}", style = MaterialTheme.typography.titleSmall)
            if (st.running) LinearProgressIndicator(progress = { (st.messageCount.toFloat() / st.targetMessages).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            Text("Scroll-Schritte bisher: ${st.scrollDone}", style = MaterialTheme.typography.bodySmall)
        } else if (st.scrollTotal > 0 && st.running) {
            LinearProgressIndicator(progress = { st.scrollDone.toFloat() / st.scrollTotal.coerceAtLeast(1) }, Modifier.fillMaxWidth())
            Text("Scroll ${st.scrollDone} von ${st.scrollTotal}", style = MaterialTheme.typography.bodySmall)
        }
        Text("Nachrichten: ${st.messageCount}, Bilder erfasst: ${st.imageCount}", style = MaterialTheme.typography.bodySmall)
        if (st.scrollInfo.isNotEmpty()) Text("Scrollen: ${st.scrollInfo}", style = MaterialTheme.typography.bodySmall)
        if (st.contextInfo.isNotEmpty()) Text("Kontext: ${st.contextInfo}", style = MaterialTheme.typography.bodySmall)
        if (st.error.isNotEmpty()) Text("Fehler: ${st.error}", color = GlassColors.Bad)
    }

    if (st.result.isNotEmpty()) {
        Section("Ergebnis") {
            SelectionContainer { Text(st.result) }
            if (st.resultInfo.isNotBlank()) Text(st.resultInfo, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCopy("Ergebnis", st.result) }) { Text("Kopieren") }
                OutlinedButton(onClick = { onShareText(st.result) }) { Text("Teilen") }
            }
        }
    }
    if (st.suggestions.isNotEmpty()) {
        Section("Antwortentwürfe für ${st.resultChat.ifBlank { "den Chat" }}", highlight = true) {
            Text("Nichts wird automatisch gesendet. \"Eintragen\" setzt den Text nur ins WhatsApp-Eingabefeld; absenden tust du selbst.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
            st.suggestions.forEachIndexed { i, t ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Entwurf ${i + 1}", style = MaterialTheme.typography.labelLarge, color = GlassColors.Accent)
                    SelectionContainer { Text(t, style = MaterialTheme.typography.bodyMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onCopy("Entwurf", t) }) { Text("Kopieren") }
                        OutlinedButton(onClick = { onInsert(t) }) { Text("Eintragen") }
                        if (settings.experimentalSend) OutlinedButton(onClick = { confirmSend = t }) { Text("Senden (exp.)") }
                    }
                }
            }
        }
    }
    if (st.transcript.isNotEmpty()) {
        Section("Extrahierter Verlauf (zur Kontrolle)") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showTranscript = !showTranscript }) { Text(if (showTranscript) "Verbergen" else "Anzeigen") }
                OutlinedButton(onClick = { onCopy("Verlauf", st.transcript) }) { Text("Kopieren") }
            }
            if (showTranscript) SelectionContainer { Text(st.transcript, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (st.contextPreview.isNotEmpty()) {
        Section("Gesendeter Prompt (Kontext)") {
            OutlinedButton(onClick = { showContext = !showContext }) { Text(if (showContext) "Verbergen" else "Anzeigen") }
            if (showContext) SelectionContainer { Text(st.contextPreview, style = MaterialTheme.typography.bodySmall) }
        }
    }

    confirmSend?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmSend = null },
            title = { Text("Diesen Text jetzt senden?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t)
                    Text("Experimentell: automatisiertes Senden kann gegen die WhatsApp-Nutzungsbedingungen verstoßen. Der Chat muss in WhatsApp geöffnet sein. Gesendet wird genau dieser Text, genau einmal.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { confirmSend = null; onSend(t) }) { Text("Ja, senden") } },
            dismissButton = { TextButton(onClick = { confirmSend = null }) { Text("Nein") } },
        )
    }
}
