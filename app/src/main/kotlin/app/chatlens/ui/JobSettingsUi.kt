package app.chatlens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import app.chatlens.agent.AgentState
import app.chatlens.core.ScrollRunConfig
import app.chatlens.core.StopMode
import app.chatlens.core.TaskMode
import app.chatlens.data.AppSettings
import app.chatlens.data.BackendChoice
import app.chatlens.service.ChatAccessibilityService
import java.net.URI

/**
 * Settings for the floating dot's jobs. Formerly on the Analysis tab (job section):
 * mode (read-only, local, API), amount, instruction, the advisor's goal, and starting a chat by name.
 */
@Composable
fun JobSettingsSection(settings: AppSettings, onSettings: (AppSettings) -> Unit, onStartChat: (ScrollRunConfig) -> Unit) {
    val st by AgentState.state.collectAsState()
    val a11y by ChatAccessibilityService.connected.collectAsState()
    var confirmApi by remember { mutableStateOf<ScrollRunConfig?>(null) }

    Section("Aufträge des Punktes: Modus und Menge") {
        Text(
            "Analyse, Vorschlag und Berater starten über den schwebenden Punkt im gerade geöffneten Chat. Hier stehen ihre Einstellungen.",
            style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
        )
        Text("Modus", style = MaterialTheme.typography.labelLarge)
        app.chatlens.data.BackendPolicy.choices().forEach { b ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = settings.backend == b, onClick = { onSettings(settings.copy(backend = b)) })
                Text(
                    when (b) {
                        BackendChoice.EXTRACT_ONLY -> "Nur Auslesen (kein LLM, nichts verlässt das Gerät)"
                        BackendChoice.LOCAL -> "Lokal: Gemma 4 E4B (nichts verlässt das Gerät)"
                        BackendChoice.API -> "API (Chatinhalt wird an den Server gesendet)"
                    },
                )
            }
        }
        if (settings.backend == BackendChoice.API) {
            val host = runCatching { URI(settings.apiBaseUrl).host }.getOrNull() ?: settings.apiBaseUrl
            Text(
                "ACHTUNG: Im Modus API verlässt der Chatinhalt dieses Gerät. Text" + (if (settings.apiVision && settings.captureImages) " und Bilder" else "") +
                    " gehen an: $host. Das betrifft auch Nachrichten Dritter." + (if (settings.apiBaseUrl.startsWith("http://")) " Die Adresse nutzt http ohne Verschlüsselung." else ""),
                style = MaterialTheme.typography.bodySmall, color = GlassColors.Warn,
            )
        } else if (settings.backend == BackendChoice.LOCAL) {
            Text(
                "Modell: " + settings.localModelPath.substringAfterLast('/').ifEmpty { "noch nicht gewählt (Tab Modelle)" },
                style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
            )
        }
        Text("Wie viel soll gelesen werden?", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = settings.lastStopMode == StopMode.TARGET, onClick = { onSettings(settings.copy(lastStopMode = StopMode.TARGET)) })
            Text("Zielmenge: scrollen, bis genug Nachrichten erfasst sind oder der Chatanfang erreicht ist")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = settings.lastStopMode == StopMode.SCROLLS, onClick = { onSettings(settings.copy(lastStopMode = StopMode.SCROLLS)) })
            Text("Feste Anzahl Scroll-Schritte (N)")
        }
        IntField("Zielmenge (Nachrichten, mindestens)", settings.lastTargetMessages) { onSettings(settings.copy(lastTargetMessages = it)) }
        IntField("N (Scroll-Schritte)", settings.lastScrollCount) { onSettings(settings.copy(lastScrollCount = it)) }
        Text(
            "Der Knopf Analysiere im Punkt liest immer bis zur Zielmenge (Modus Zielmenge). Sicherheitsobergrenze: höchstens ${settings.maxScrollCap} Scroll-Schritte pro Lauf (unten bei Limits). " +
                "Gezählt werden Text-, Bild- und Sprachnachrichten, keine Datumstrenner. Die Zielmenge ist ein Minimum.",
            style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
        )
        OutlinedTextField(
            value = settings.lastInstruction, onValueChange = { onSettings(settings.copy(lastInstruction = it)) },
            label = { Text("Standard-Instruktion für Analysiere (\"Analysieren wie immer\")") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = settings.lastGoal, onValueChange = { onSettings(settings.copy(lastGoal = it)) },
            label = { Text("Ziel für Berater und Schlage vor (z. B. Termin vereinbaren)") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
        )
        Text("Der Berater im Punkt braucht dieses Ziel; ohne Ziel öffnet der Knopf diese Einstellungen.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
    }

    Section("Einzelnen Chat per Namen starten") {
        Text(
            "Öffnet den Chat in WhatsApp über die Suche und führt die Aufgabe aus. Das Ergebnis erscheint auf der Startseite unter Letzter Lauf.",
            style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
        )
        OutlinedTextField(
            value = settings.lastChatTitle, onValueChange = { onSettings(settings.copy(lastChatTitle = it)) },
            label = { Text("Chat-Titel (Name wie in WhatsApp, für die Suche)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("Analysiere", settings.lastTask == TaskMode.ANALYSE, { onSettings(settings.copy(lastTask = TaskMode.ANALYSE)) })
            GlassChip("Schlage vor", settings.lastTask == TaskMode.SUGGEST, { onSettings(settings.copy(lastTask = TaskMode.SUGGEST)) })
            GlassChip("Berater", settings.lastTask == TaskMode.ADVISE, { onSettings(settings.copy(lastTask = TaskMode.ADVISE)) })
        }
        val ready = a11y && settings.privacyAcknowledged && !st.running && settings.lastChatTitle.isNotBlank()
        Button(
            enabled = ready,
            onClick = {
                val cfg = ScrollRunConfig(
                    settings.lastChatTitle.trim(), false, settings.lastScrollCount, settings.lastInstruction,
                    settings.lastStopMode, settings.lastTargetMessages.coerceIn(1, 5000),
                    task = settings.lastTask, goal = settings.lastGoal.trim(), incremental = false, useMemory = true,
                )
                if (settings.backend == BackendChoice.API) confirmApi = cfg else onStartChat(cfg)
            },
        ) { Text("Chat suchen und starten") }
        if (!ready) Text(
            when {
                !a11y -> "Bedienungshilfe ist nicht aktiv."
                !settings.privacyAcknowledged -> "Datenschutzhinweis auf der Startseite bestätigen."
                st.running -> "Es läuft bereits ein Auftrag."
                else -> "Chat-Titel eintragen."
            },
            style = MaterialTheme.typography.bodySmall, color = GlassColors.Warn,
        )
    }

    confirmApi?.let { cfg ->
        val host = runCatching { URI(settings.apiBaseUrl).host }.getOrNull() ?: settings.apiBaseUrl
        AlertDialog(
            onDismissRequest = { confirmApi = null },
            title = { Text("Chatinhalt an API senden?") },
            text = {
                Text(
                    "Der ausgelesene Chat (Nachrichten Dritter" + (if (settings.apiVision && settings.captureImages) " und Bilder" else "") +
                        ") wird an $host gesendet. Fortfahren nur, wenn dafür eine Rechtsgrundlage besteht.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmApi = null; onStartChat(cfg) }) { Text("Senden erlauben und starten") } },
            dismissButton = { TextButton(onClick = { confirmApi = null }) { Text("Abbrechen") } },
        )
    }
}
