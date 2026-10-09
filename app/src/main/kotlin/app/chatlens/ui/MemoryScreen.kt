package app.chatlens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.chatlens.agent.AutoState
import app.chatlens.data.MemoryRepo
import app.chatlens.memory.ChatMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gedaechtnis: Profile ansehen, eigene Notiz bearbeiten, exportieren, loeschen (je Chat und alles). */
@Composable
fun MemoryScreen(settings: app.chatlens.data.AppSettings, onSettings: (app.chatlens.data.AppSettings) -> Unit, onShareJson: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { MemoryRepo.get(ctx) }
    var refresh by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<ChatMemory>>(emptyList()) }
    var open by remember { mutableStateOf<String?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    var confirmOne by remember { mutableStateOf<ChatMemory?>(null) }
    val auto by AutoState.state.collectAsState()

    LaunchedEffect(refresh, auto.running, auto.finished) {
        items = withContext(Dispatchers.IO) { runCatching { repo.list() }.getOrDefault(emptyList()) }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        IchProfileSection(settings, onSettings)
        Section("Gedächtnis (${items.size} Chats)") {
            Text(
                "Je Chat ein Profil mit Abschnitten (Steckbrief, Beziehung, Themen, Ton, offene Punkte, Fakten und Termine, Vorlieben, Stimmung und Stimmungsverlauf, " +
                    "höchstens ${settings.memoryMaxChars} Zeichen, einstellbar in den Einstellungen) plus DISC-Einschätzung und deine Notiz. Bei langen Profilen kommen nur die zur Aufgabe passenden Abschnitte in den Prompt. " +
                    "Gespeichert nur auf diesem Gerät, verschlüsselt mit einem Schlüssel im Android Keystore (AES-GCM). Kein Cloud-Backup. " +
                    "Der Export ist Klartext, nur auf deinen Wunsch.",
                style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
            )
            Text(MEMORY_PRIVACY_NOTE, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = items.isNotEmpty(), onClick = {
                    onShareJson(runCatching { repo.exportJson(null) }.getOrDefault("{}"), "chatlens-gedaechtnis.json")
                }) { Text("Alles exportieren") }
                OutlinedButton(enabled = items.isNotEmpty(), onClick = { confirmAll = true }) { Text("Alles löschen") }
            }
        }
        items.forEach { m ->
            Section(m.displayName) {
                ProfileLine(m)
                OutlinedButton(onClick = { open = if (open == m.chatKey) null else m.chatKey }) { Text(if (open == m.chatKey) "Schließen" else "Ansehen und bearbeiten") }
                if (open == m.chatKey) {
                    var note by remember(m.chatKey, m.updatedAt) { mutableStateOf(m.userNote) }
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            app.chatlens.memory.MemSection.entries.forEach { sec -> Field(sec.label, m.get(sec)) }
                        }
                    }
                    DiscBar(m.disc, showReason = true, who = "Gegenüber")
                    Text("Die DISC-Einschätzung wird bei neuen Läufen mit mehr Daten fortgeschrieben. Sie gilt nur für dieses Gegenüber in diesem Chat und ist keine Diagnose.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
                    OutlinedTextField(
                        value = note, onValueChange = { note = it.take(ChatMemory.MAX_NOTE) },
                        label = { Text("Deine Notiz (nur von dir, bleibt bei Updates erhalten)") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { repo.save(m.copy(userNote = note.trim())); refresh++ }) { Text("Notiz speichern") }
                        OutlinedButton(onClick = { onShareJson(repo.exportJson(m.chatKey), "chatlens-" + m.chatKey.take(24) + ".json") }) { Text("Exportieren") }
                        OutlinedButton(onClick = { confirmOne = m }) { Text("Löschen") }
                    }
                }
            }
        }
        if (items.isEmpty()) {
            Section("Noch leer") { Text("Lege im Tab Start das Setup an oder aktualisiere im Update-Modus.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim) }
        }
    }

    if (confirmAll) AlertDialog(
        onDismissRequest = { confirmAll = false },
        title = { Text("Gesamtes Gedächtnis löschen?") },
        text = { Text("Alle Chat-Profile, das Ich-Profil, Selbstanalyse-Teilergebnisse, gespeicherte Prompts und die Warteschlange werden endgültig entfernt.") },
        confirmButton = { TextButton(onClick = { repo.deleteAll(); app.chatlens.agent.IchState.reset(); app.chatlens.agent.PromptBookState.reset(); app.chatlens.agent.SelfState.clear(ctx); AutoState.update { it.copy(resumable = false, overview = emptyList(), items = emptyList(), total = 0, done = 0) }; confirmAll = false; refresh++ }) { Text("Löschen") } },
        dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Abbrechen") } },
    )
    confirmOne?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmOne = null },
            title = { Text("Profil von ${m.displayName} löschen?") },
            text = { Text("Das Profil wird endgültig entfernt.") },
            confirmButton = { TextButton(onClick = { repo.delete(m.chatKey); confirmOne = null; open = null; refresh++ }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmOne = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = GlassColors.Accent)
        Text(value.ifBlank { "(leer)" }, style = MaterialTheme.typography.bodyMedium, color = GlassColors.Text)
    }
}
