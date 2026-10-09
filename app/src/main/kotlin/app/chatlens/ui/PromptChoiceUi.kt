package app.chatlens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.chatlens.agent.PromptChoice
import app.chatlens.prompts.PromptBook
import app.chatlens.prompts.PromptLimits
import app.chatlens.prompts.PromptMode
import app.chatlens.prompts.QuickPrompts

/**
 * Auswahl nach dem Sammeln: "Analysieren wie immer" oder "Eigener Prompt zur Laufzeit".
 * Zustandslos gegenueber dem Speicher: Speichern und Loeschen von Vorlagen laufen ueber die Rueckrufe.
 * [onOpenApp] bietet den Weg in die App an (Tastatur im Overlay-Fenster ist auf manchen Geraeten unzuverlaessig).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PromptChoiceCard(
    chatTitle: String,
    messageCount: Int,
    book: PromptBook,
    onChoice: (PromptChoice) -> Unit,
    onSaveTemplate: (name: String, text: String) -> Unit,
    onDeleteSaved: (name: String) -> Unit,
    onDeleteRecent: (text: String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenApp: (() -> Unit)? = null,
    initialMode: PromptMode = book.lastMode,
    initialText: String = if (book.lastMode == PromptMode.CUSTOM) book.lastText else "",
    initialName: String = "",
) {
    var mode by remember { mutableStateOf(initialMode) }
    var text by remember { mutableStateOf(initialText) }
    var name by remember { mutableStateOf(initialName) }
    val canStart = mode == PromptMode.STANDARD || text.isNotBlank()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Nachrichten gesammelt: $messageCount" + (if (chatTitle.isNotBlank()) " ($chatTitle)" else ""), style = MaterialTheme.typography.titleSmall, color = GlassColors.Text)
        Text("Wie soll das Modell den Verlauf auswerten?", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
        Column(Modifier.weight(1f, fill = false).heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == PromptMode.STANDARD, onClick = { mode = PromptMode.STANDARD })
                Text("Analysieren wie immer", color = GlassColors.Text, style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == PromptMode.CUSTOM, onClick = { mode = PromptMode.CUSTOM })
                Text("Eigener Prompt", color = GlassColors.Text, style = MaterialTheme.typography.bodyMedium)
            }
            if (mode == PromptMode.CUSTOM) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(PromptLimits.MAX_LEN) },
                    label = { Text("Was soll das Modell tun?") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(),
                )
                Text("${text.length} von ${PromptLimits.MAX_LEN} Zeichen. Der Text wird verschlüsselt auf dem Gerät gespeichert und nie im Protokoll abgelegt.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
                Text("Schnellvorlagen", style = MaterialTheme.typography.labelLarge, color = GlassColors.Accent)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickPrompts.all.forEach { q -> GlassChip(q.name, text == q.text, { text = q.text }) }
                }
                if (book.saved.isNotEmpty()) {
                    Text("Eigene Vorlagen", style = MaterialTheme.typography.labelLarge, color = GlassColors.Accent)
                    book.saved.forEach { sp ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlassChip(sp.name, text == sp.text, { text = sp.text; name = sp.name }, Modifier.weight(1f, fill = false))
                            TextButton(onClick = { onDeleteSaved(sp.name) }) { Text("Löschen", color = GlassColors.Danger) }
                        }
                    }
                }
                if (book.recent.isNotEmpty()) {
                    Text("Zuletzt genutzt", style = MaterialTheme.typography.labelLarge, color = GlassColors.Accent)
                    book.recent.take(5).forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { text = r }, Modifier.weight(1f, fill = false)) {
                                Text(r.replace('\n', ' ').take(60) + (if (r.length > 60) " ..." else ""), color = GlassColors.Text, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onDeleteRecent(r) }) { Text("Löschen", color = GlassColors.Danger) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(PromptLimits.MAX_NAME) }, singleLine = true,
                        label = { Text("Name der Vorlage") }, modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(enabled = name.isNotBlank() && text.isNotBlank(), onClick = { onSaveTemplate(name, text) }) { Text("Speichern") }
                }
            } else {
                Text(
                    "Die Aufgabe aus den Einstellungen und der Standardprompt bleiben unverändert. Das Gedächtnis fließt in Analysieren wie bisher nicht ein.",
                    style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim, modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = canStart,
                onClick = { onChoice(if (mode == PromptMode.STANDARD) PromptChoice.Standard else PromptChoice.Custom(text)) },
            ) { Text("Starten") }
            OutlinedButton(onClick = { onChoice(PromptChoice.Cancel) }) { Text("Abbrechen") }
            if (onOpenApp != null) TextButton(onClick = onOpenApp) { Text("In der App wählen") }
        }
    }
}
