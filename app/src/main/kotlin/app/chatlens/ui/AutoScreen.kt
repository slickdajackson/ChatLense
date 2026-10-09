package app.chatlens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.chatlens.agent.AgentState
import app.chatlens.agent.AutoState
import app.chatlens.agent.QueueItemView
import app.chatlens.auto.ItemStatus
import app.chatlens.data.AppSettings
import app.chatlens.service.ChatAccessibilityService

/** Progress list of a queue: status and error text for each chat. */
@Composable
fun QueueItems(items: List<QueueItemView>) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEach { i ->
            val (mark, col) = when (i.status) {
                ItemStatus.FERTIG -> "fertig" to GlassColors.Ok
                ItemStatus.FEHLER -> "Fehler" to GlassColors.Bad
                ItemStatus.LAEUFT -> "läuft" to GlassColors.Accent
                ItemStatus.UEBERSPRUNGEN -> "übersprungen" to GlassColors.Warn
                ItemStatus.WARTET -> "wartet" to GlassColors.TextDim
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(i.title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = GlassColors.Text, maxLines = 1)
                Text(mark, style = MaterialTheme.typography.bodySmall, color = col)
            }
            if (i.status == ItemStatus.FEHLER && i.error.isNotBlank()) {
                Text(i.error, style = MaterialTheme.typography.bodySmall, color = GlassColors.Bad)
            } else if (i.status == ItemStatus.FERTIG && i.info.isNotBlank()) {
                Text(i.info, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
            }
        }
    }
}

/** Auto mode: pick chats by name or from the chat list that was read, then update them serially and incrementally. */
@Composable
fun AutoScreen(
    settings: AppSettings,
    onSettings: (AppSettings) -> Unit,
    hasBackend: Boolean,
    onReadList: () -> Unit,
    onStartNames: (List<String>) -> Unit,
    onResume: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val a11y by ChatAccessibilityService.connected.collectAsState()
    val auto by AutoState.state.collectAsState()
    val ticked = remember { mutableStateListOf<String>() }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Section("Chats aktualisieren") {
            Text(
                "Pro Chat werden nur die Nachrichten seit dem letzten Stand gelesen (Anker) und in das Profil eingearbeitet. " +
                    "Gibt es noch kein Profil, wird komplett gelesen. Ein Modellaufruf nach dem anderen, lokal mit Gemma empfohlen.",
                style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
            )
            OutlinedTextField(
                value = settings.autoNames, onValueChange = { onSettings(settings.copy(autoNames = it)) },
                label = { Text("Chatnamen (ein Name pro Zeile)") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            IntField("Zielmenge je Chat (Nachrichten, mindestens)", settings.autoTarget) { onSettings(settings.copy(autoTarget = it)) }
            Text(
                "Stimmt ein Name nicht genau, sucht ChatLens den ähnlichsten und fragt vorher (Dialog und Benachrichtigung) nach. Ohne Ja wird der Chat übersprungen.",
                style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
            )
        }

        Section("Oder aus der Chatliste ankreuzen") {
            Text("ChatLens öffnet WhatsApp, liest die Chatliste und zeigt sie hier. Gelesen wird nur, was sichtbar ist.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
            OutlinedButton(enabled = a11y && !auto.running, onClick = onReadList) { Text("Chatliste lesen") }
            auto.listEntries.filter { !it.archiveRow }.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = e.title in ticked, onCheckedChange = { c -> if (c) ticked.add(e.title) else ticked.remove(e.title) })
                    Column(Modifier.weight(1f)) {
                        Text(e.title + (if (e.pinned) " (angeheftet)" else "") + (if (e.likelyGroup) " (Gruppe?)" else ""), style = MaterialTheme.typography.bodyMedium, color = GlassColors.Text, maxLines = 1)
                        Text(e.timeText, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
                    }
                }
            }
        }

        val cu by app.chatlens.agent.CheckupState.state.collectAsState()
        val lock = app.chatlens.agent.Workflow.lockReason("das Aktualisieren", cu.items.size, 0, needsSelection = false)
        lock?.let { Text("Gesperrt: $it", Modifier.padding(horizontal = 18.dp), style = MaterialTheme.typography.bodyMedium, color = GlassColors.Warn) }
        val names = (settings.autoNames.lines().map { it.trim() } + ticked).filter { it.isNotEmpty() }.distinct()
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = lock == null && a11y && hasBackend && settings.privacyAcknowledged && !auto.running && names.isNotEmpty(),
                onClick = { onStartNames(names) },
            ) { Text("Auto starten (${names.size})") }
            Button(
                enabled = auto.running, onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = GlassColors.Bad, contentColor = GlassColors.OnBad),
            ) { Text("Abbrechen") }
        }
        if (!hasBackend) Text("Unter Einstellungen ein Modell wählen.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = GlassColors.Warn, style = MaterialTheme.typography.bodySmall)

        Section("Fortschritt") {
            RunStatus(AgentState.state.collectAsState().value, auto)
            if (auto.message.isNotEmpty()) Text(auto.message, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
            if (auto.total > 0) {
                LinearProgressIndicator(progress = { auto.done.toFloat() / auto.total }, Modifier.fillMaxWidth())
                Text("${auto.done} von ${auto.total} bearbeitet, Fehler: ${auto.items.count { it.status == ItemStatus.FEHLER }}", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
            }
            Text("Ein fehlerhafter Chat wird übersprungen und unten mit Grund gemeldet; die übrigen laufen weiter.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
            QueueItems(auto.items)
            if (!auto.running && auto.resumable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = a11y && hasBackend, onClick = { onResume(false) }) { Text("Fortsetzen") }
                    OutlinedButton(enabled = a11y && hasBackend, onClick = { onResume(true) }) { Text("Fehler wiederholen") }
                }
            }
        }
    }
}
