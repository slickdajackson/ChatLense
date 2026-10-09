package app.chatlens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.chatlens.agent.IchState
import app.chatlens.agent.SelfState
import app.chatlens.data.AppSettings
import app.chatlens.memory.IchCat
import app.chatlens.memory.IchLogic
import app.chatlens.memory.IchProfile

/**
 * Ich-Profil: chatuebergreifende Merkmale des Nutzers (Stil, Ton, Formulierungen, Humor, Sprachen, Werte, Interessen, Arbeitsweise, Entscheidungsstil, DISC).
 * Ansehen, Eintraege festpinnen oder loeschen, eigene Eintraege hinzufuegen (haben Vorrang), Schalter, Loeschen. Enthaelt nie Fakten aus einzelnen Chats.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IchProfileSection(settings: AppSettings, onSettings: (AppSettings) -> Unit) {
    val ctx = LocalContext.current
    val p by IchState.profile.collectAsState()
    val proposal by SelfState.proposal.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { IchState.ensureLoaded(ctx); SelfState.load(ctx) }
    var confirmDelete by remember { mutableStateOf(false) }
    var cat by remember { mutableStateOf(IchCat.STYLE) }
    var text by remember { mutableStateOf("") }

    Section("Ich-Profil", highlight = proposal != null) {
        Text(
            "Chatübergreifende Merkmale von dir, getrennt vom Gedächtnis der einzelnen Chats und verschlüsselt gespeichert. Es enthält keine Fakten, Namen, Termine oder Einzelheiten aus einem Chat; " +
                "jeder Eintrag wird vor dem Speichern gegen Namen aus deiner Kontaktliste und gegen die Fakten des Chats geprüft. Ein kurzer Block daraus geht in Berater und Schlage vor, damit der Stil passt.",
            style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
        )
        SwitchRow("Ich-Profil nutzen", settings.ichEnabled) { onSettings(settings.copy(ichEnabled = it)) }
        Text(
            "Stand: ${p.entries.size} Einträge, ${p.length} von ${IchLogic.MAX_CHARS} Zeichen, aus ${p.chatCount} Chats, ${p.runs} Läufen." +
                (if (p.entries.any { it.pinned }) " Festgepinnt: ${p.entries.count { it.pinned }}." else ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        DiscBar(p.disc, showReason = true, who = "du")
        IchCat.entries.forEach { c ->
            val items = p.entries.filter { it.cat == c }
            if (items.isNotEmpty()) {
                Text(c.label, style = MaterialTheme.typography.labelLarge, color = GlassColors.Accent)
                items.forEach { e ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text((if (e.pinned) "Fest: " else "") + e.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { IchState.update(ctx) { IchLogic.pin(it, e, !e.pinned) } }) { Text(if (e.pinned) "Lösen" else "Anpinnen") }
                        TextButton(onClick = { IchState.update(ctx) { IchLogic.delete(it, e) } }) { Text("Löschen", color = GlassColors.Danger) }
                    }
                }
            }
        }
        if (p.entries.isEmpty()) Text("Noch leer. Es füllt sich nach Gedächtnis-Läufen (Setup, Auto) oder mit der Selbstanalyse. Du kannst auch selbst etwas eintragen.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
        Text("Eigenen Eintrag hinzufügen (hat Vorrang, wird festgepinnt)", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            IchCat.entries.forEach { c -> GlassChip(c.label, cat == c, { cat = c }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = text, onValueChange = { text = it.take(IchLogic.MAX_ENTRY) }, label = { Text("Eintrag") }, modifier = Modifier.weight(1f))
            OutlinedButton(enabled = text.isNotBlank(), onClick = { IchState.update(ctx) { IchLogic.addByUser(it, cat, text, System.currentTimeMillis()) }; text = "" }) { Text("Hinzufügen") }
        }
        OutlinedButton(enabled = !p.isEmpty() || p.suppressed.isNotEmpty(), onClick = { confirmDelete = true }) { Text("Ich-Profil löschen", color = GlassColors.Danger) }
        Text(MEMORY_PRIVACY_NOTE, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
    }

    proposal?.let { pr -> SelfProposalSection(pr) }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Ich-Profil löschen?") },
        text = { Text("Alle Einträge, auch festgepinnte, die DISC-Einschätzung und die gemerkten Löschungen werden endgültig entfernt.") },
        confirmButton = { TextButton(onClick = { IchState.clear(ctx); confirmDelete = false }) { Text("Löschen") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
    )
}

/** Vorschlag der Selbstanalyse: erst nach Bestaetigung geht etwas ins Ich-Profil. */
@Composable
fun SelfProposalSection(pr: IchProfile) {
    val ctx = LocalContext.current
    Section("Selbstanalyse: Vorschlag prüfen", highlight = true) {
        Text(
            "Gesamtbild aus ${pr.chatCount} Chats. Es ist ein Vorschlag und steht noch nicht im Ich-Profil. Er enthält nur allgemeine Merkmale, keine Einzelheiten aus Chats.",
            style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
        )
        DiscBar(pr.disc, showReason = true, who = "du")
        IchCat.entries.forEach { c ->
            val items = pr.entries.filter { it.cat == c }
            if (items.isNotEmpty()) {
                Text(c.label, style = MaterialTheme.typography.labelLarge, color = GlassColors.Accent)
                items.forEach { Text(it.text, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (pr.entries.isEmpty()) Text("Der Vorschlag ist leer: keine allgemeinen Merkmale fanden die Prüfung.", style = MaterialTheme.typography.bodySmall, color = GlassColors.Warn)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val blocked = IchLogic.blockedFrom(
                    runCatching { app.chatlens.data.MemoryRepo.get(ctx).list().map { it.displayName } }.getOrDefault(emptyList()),
                    app.chatlens.agent.CheckupState.state.value.items.map { it.entry.title },
                )
                SelfState.confirm(ctx, blocked)
            }) { Text("In das Ich-Profil übernehmen") }
            OutlinedButton(onClick = { SelfState.clear(ctx) }) { Text("Verwerfen") }
        }
    }
}
