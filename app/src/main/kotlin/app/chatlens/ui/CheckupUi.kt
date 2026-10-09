package app.chatlens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.chatlens.agent.AutoState
import app.chatlens.agent.CheckupState
import app.chatlens.checkup.CheckupItem
import app.chatlens.checkup.CheckupSelection
import app.chatlens.data.AppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Checkup and selection menu: reads the top X chats (the list only; no chat is opened) and shows them with checkmarks.
 * The selection decides which chats setup works through. Only names are stored (encrypted).
 */
@Composable
fun CheckupSection(settings: AppSettings, onSettings: (AppSettings) -> Unit, canRun: Boolean, onRun: () -> Unit) {
    val cs by CheckupState.state.collectAsState()
    val auto by AutoState.state.collectAsState()
    var query by remember { mutableStateOf("") }

    Section("Schritt 1: Checkup und Auswahl", highlight = true) {
        Text(
            "Der Checkup liest die obersten Chats der WhatsApp-Chatliste (Name, Vorschau, Zeit, Ungelesen-Hinweis, Gruppe soweit erkennbar). " +
                "Er öffnet keinen Chat, bleibt im Tab Chats und scrollt nur senkrecht. Danach wählst du per Haken, welche Chats das Setup bearbeitet.",
            style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
        )
        SwitchRow(
            "Checkup beim Start der App (einmal je Start, höchstens alle 10 Minuten, nur nach der Einrichtung)",
            settings.checkupOnStart && settings.wizardDone, enabled = settings.wizardDone,
        ) { onSettings(settings.copy(checkupOnStart = it)) }
        if (!settings.wizardDone) Text("Zuerst die Einrichtung abschließen (Einstellungen, Einrichtung). Ohne Zustimmung startet ChatLens WhatsApp nie von selbst.", style = MaterialTheme.typography.bodySmall, color = GlassColors.Warn)
        IntField("Anzahl X der gelesenen Chats (5 bis 200)", settings.checkupCount) { onSettings(settings.copy(checkupCount = it.coerceIn(0, 200))) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = canRun && !auto.running && settings.checkupCount in 5..200, onClick = onRun) { Text("Checkup jetzt") }
        }
        if (cs.items.isEmpty()) {
            Text("Noch kein Checkup. Mit „Checkup jetzt“ starten. WhatsApp wird dabei nach vorn geholt.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
            return@Section
        }
        val ts = if (cs.lastAt > 0) SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(cs.lastAt)) else "?"
        Text(
            (if (cs.storedOnly) "Gespeicherter Stand vom $ts (nur Namen, ohne Vorschau). " else "Stand vom $ts. ") + cs.note,
            style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
        )
        val chosen = cs.items.count { it.selected }
        Text("$chosen von ${cs.items.size} Chats gewählt", style = MaterialTheme.typography.labelLarge, color = GlassColors.Text)

        Text("Schnellwahl (setzt die Haken, danach änderbar)", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 20, 30).forEach { n ->
                GlassChip("Top $n", false, { onSettings(settings.copy(setupCount = n)); CheckupState.quick(n, settings.setupIncludeGroups, settings.setupPinnedCounts) })
            }
        }
        SwitchRow("Schnellwahl: Gruppen einbeziehen (Erkennung ist eine Heuristik)", settings.setupIncludeGroups) { onSettings(settings.copy(setupIncludeGroups = it)) }
        SwitchRow("Schnellwahl: Angeheftete normal mitzählen (aus: ausschließen)", settings.setupPinnedCounts) { onSettings(settings.copy(setupPinnedCounts = it)) }

        OutlinedTextField(
            value = query, onValueChange = { query = it }, label = { Text("Suchen im Namen") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val shown = CheckupSelection.filter(cs.items, query)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { CheckupState.setMany(shown.map { it.entry.title }.toSet(), true) }) { Text(if (query.isBlank()) "Alle" else "Alle gefundenen") }
            OutlinedButton(onClick = { CheckupState.setMany(shown.map { it.entry.title }.toSet(), false) }) { Text(if (query.isBlank()) "Keine" else "Keine gefundenen") }
        }
        if (query.isNotBlank()) Text("${shown.size} Treffer", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            shown.forEach { CheckupRow(it) }
        }
    }
}

@Composable
private fun CheckupRow(it: CheckupItem) {
    val e = it.entry
    Row(
        Modifier.fillMaxWidth().clickable { CheckupState.toggle(e.title) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = it.selected, onCheckedChange = { _ -> CheckupState.toggle(e.title) })
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${e.order + 1}.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
                Text(e.title, style = MaterialTheme.typography.bodyMedium, color = GlassColors.Text, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (it.isNew) Text("neu", style = MaterialTheme.typography.labelSmall, color = GlassColors.Warn)
                if (e.likelyGroup) Text("Gruppe", style = MaterialTheme.typography.labelSmall, color = GlassColors.TextDim)
                if (e.pinned) Text("angeheftet", style = MaterialTheme.typography.labelSmall, color = GlassColors.TextDim)
                if (e.unread) Text(if (e.unreadCount > 0) "${e.unreadCount} ungelesen" else "ungelesen", style = MaterialTheme.typography.labelSmall, color = GlassColors.Accent)
            }
            val sub = listOf(e.timeText, e.preview).filter { s -> s.isNotBlank() }.joinToString("  ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim, maxLines = 1)
        }
    }
}
