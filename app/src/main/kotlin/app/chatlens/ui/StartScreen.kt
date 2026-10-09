package app.chatlens.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.chatlens.agent.AgentState
import app.chatlens.agent.AutoState
import app.chatlens.agent.CheckupState
import app.chatlens.auto.QueueKind
import app.chatlens.agent.Workflow
import app.chatlens.data.AppSettings
import app.chatlens.memory.ChatMemory
import app.chatlens.service.ChatAccessibilityService

/**
 * Start page, the first step of onboarding: a checklist and, below it and prominent, setup (create chat profiles),
 * which walks through the newest chats automatically and creates a memory profile for each chat.
 */
@Composable
fun StartScreen(
    settings: AppSettings,
    onSettings: (AppSettings) -> Unit,
    hasBackend: Boolean,
    overlayGranted: Boolean,
    memoryCount: Int,
    onOpenA11y: () -> Unit,
    onOpenOverlay: () -> Unit,
    onStartCheckup: () -> Unit,
    onStartSetup: () -> Unit,
    onResume: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onGoto: (Int) -> Unit,
    /** Space for the dot status and the last run, directly under the header (supplied by MainActivity). */
    header: @Composable () -> Unit = {},
) {
    val a11y by ChatAccessibilityService.connected.collectAsState()
    val auto by AutoState.state.collectAsState()

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LogoMark(Modifier.size(40.dp), description = "ChatLens Logo")
                Text("ChatLens", style = MaterialTheme.typography.titleLarge, color = GlassColors.Text)
            }
            Text(if (settings.backend == app.chatlens.data.BackendChoice.API) "Liest einen WhatsApp-Chat, merkt sich Profile lokal verschlüsselt und hilft bei Antworten. Im API-Modus gehen Chattexte an den Server, den du eingetragen hast. Nachrichten werden nie automatisch gesendet." else "Liest einen WhatsApp-Chat, merkt sich Profile lokal verschlüsselt und hilft bei Antworten. Die Chatinhalte bleiben auf diesem Gerät. Nachrichten werden nie automatisch gesendet.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
        }

        header()

        // Checkup and selection first, then setup (no numbering and no jargon, 0.3.0)
        CheckupSection(settings, onSettings, canRun = a11y && settings.privacyAcknowledged, onRun = onStartCheckup)

        Section("Setup: Chat-Profile anlegen", highlight = true) {
            Text(
                "Das Setup bearbeitet genau die Chats, die du im Checkup angekreuzt hast (nicht einfach die obersten). Es öffnet sie nacheinander direkt aus " +
                    "der Chatliste, liest die Nachrichten und legt je Chat ein Profil im Gedächtnis an. " +
                    "Das Modell arbeitet seriell, immer nur ein Chat zugleich. Nach 2 Fehlern in Folge pausiert das Setup. Danach aktualisiert das Update nur noch Neues.",
                style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
            )
            IntField("Zielmenge je Chat (Nachrichten, mindestens)", settings.setupTarget) { onSettings(settings.copy(setupTarget = it)) }
            val cs by CheckupState.state.collectAsState()
            val chosenCount = cs.items.count { it.selected }

            val ready = a11y && hasBackend && settings.privacyAcknowledged && !auto.running && chosenCount in 1..200 &&
                Workflow.lockReason("das Setup", cs.items.size, chosenCount) == null
            Workflow.lockReason("das Setup", cs.items.size, chosenCount)?.let {
                Text("Gesperrt: $it", style = MaterialTheme.typography.bodyMedium, color = GlassColors.Warn)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = ready, onClick = onStartSetup) { Text(if (chosenCount > 0) "Setup starten ($chosenCount Chats)" else "Setup starten") }
                Button(
                    enabled = auto.running, onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(containerColor = GlassColors.Bad, contentColor = GlassColors.OnBad),
                ) { Text("Abbrechen") }
            }
            if (!ready && !auto.running) {
                Text(
                    when {
                        !a11y -> "Zuerst die Bedienungshilfe aktivieren (Checkliste unten)."
                        !hasBackend -> "Zuerst unter Einstellungen ein Modell wählen (Lokal: Gemma). Mit \"Nur Auslesen\" kann kein Profil entstehen."
                        !settings.privacyAcknowledged -> "Datenschutzhinweis unten bestätigen."
                        else -> "Zuerst den Checkup ausführen und mindestens einen Chat ankreuzen (oder Top 10, 20, 30 wählen)."
                    },
                    style = MaterialTheme.typography.bodySmall, color = GlassColors.Warn,
                )
            }

            AnimatedVisibility(auto.kind == QueueKind.SETUP || auto.running || auto.message.isNotEmpty()) {
                Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RunStatus(AgentState.state.collectAsState().value, auto)
                    if (auto.message.isNotEmpty()) Text(auto.message, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
                    RunLogButtons()
                    if (auto.total > 0) {
                        LinearProgressIndicator(progress = { auto.done.toFloat() / auto.total }, Modifier.fillMaxWidth())
                        Text("${auto.done} von ${auto.total} Chats bearbeitet", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
                    }
                }
            }
            QueueItems(auto.items)
            if (!auto.running && auto.resumable) {
                Text("Es gibt eine unterbrochene Warteschlange. Fortsetzen überspringt, was schon fertig ist.", style = MaterialTheme.typography.bodySmall, color = GlassColors.Warn)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = a11y && hasBackend, onClick = { onResume(false) }) { Text("Fortsetzen") }
                    OutlinedButton(enabled = a11y && hasBackend, onClick = { onResume(true) }) { Text("Fortsetzen und Fehler wiederholen") }
                }
            }
        }

        if (auto.finished && auto.overview.isNotEmpty()) {
            Section("Übersicht der angelegten Chat-Profile (${auto.overview.size})") {
                auto.overview.forEach { ProfileLine(it) }
                OutlinedButton(onClick = { onGoto(2) }) { Text("Im Gedächtnis ansehen") }
            }
        }

        Section("Checkliste") {
            StepRow(a11y, "Bedienungshilfe \"ChatLens Chat-Leser\" aktivieren", "Falls der Schalter grau ist (häufig bei Xiaomi/HyperOS): Einstellungen, Apps, ChatLens, Menü oben rechts, \"Eingeschränkte Einstellungen zulassen\".")
            if (!a11y) OutlinedButton(onClick = onOpenA11y) { Text("Bedienungshilfen öffnen") }
            StepRow(hasBackend, "Modell wählen (empfohlen: lokal Gemma 4 E4B)", "Tab Modelle: laden und verwenden. Alternativ Einstellungen, Abschnitt Lokales Modell.")
            if (!hasBackend) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onGoto(3) }) { Text("Zu den Modellen") }
                    OutlinedButton(onClick = { onGoto(4) }) { Text("Zu den Einstellungen") }
                }
            StepRow(settings.privacyAcknowledged, "Datenschutzhinweis gelesen", "")
            if (!settings.privacyAcknowledged) {
                Text(PRIVACY_TEXT, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = settings.privacyAcknowledged, onCheckedChange = { onSettings(settings.copy(privacyAcknowledged = it)) })
                    Text("Ich habe den Hinweis gelesen")
                }
            }
            StepRow(overlayGranted, "Optional: schwebender Punkt (Overlay)", "Erlaubnis \"Über anderen Apps einblenden\", siehe Einstellungen.")
            if (!overlayGranted) OutlinedButton(onClick = onOpenOverlay) { Text("Erlaubnis öffnen") }
            StepRow(memoryCount > 0, "Setup durchgelaufen ($memoryCount Profile im Gedächtnis)", "")
        }

        Section("Danach") {
            Text("Chat in WhatsApp öffnen und den schwebenden Punkt antippen: Analyse, Vorschlag oder Berater. Update: ausgewählte Chats später inkrementell aktualisieren.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onGoto(1) }) { Text("Zum Update") }
            }
        }
    }
}

@Composable
fun ProfileLine(m: ChatMemory) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(m.displayName, style = MaterialTheme.typography.bodyMedium, color = GlassColors.Text)
        Text(
            "${m.messagesSeen} Nachrichten gesehen, ${m.generatedLength} Zeichen" + (if (m.anchorTime.isNotBlank()) ", bis ${m.anchorTime}" else ""),
            style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
        )
    }
}
