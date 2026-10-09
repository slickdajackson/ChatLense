package app.chatlens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.chatlens.agent.AutoState
import app.chatlens.agent.CheckupState
import app.chatlens.agent.Workflow
import app.chatlens.agent.WfStep
import app.chatlens.data.AppSettings
import app.chatlens.memory.SelfAnalysis
import app.chatlens.service.ChatAccessibilityService

/** Erster Schritt sichtbar: Assistent 1. Berechtigungen, 2. Checkup der obersten 50, 3. Auswahl, 4. Setup. */
@Composable
fun SetupWizardSection(settings: AppSettings, hasBackend: Boolean, memoryCount: Int, onCheckup: () -> Unit) {
    val a11y by ChatAccessibilityService.connected.collectAsState()
    val cu by CheckupState.state.collectAsState()
    val w = Workflow.state(a11y, settings.privacyAcknowledged, hasBackend, cu.items.size, cu.items.count { it.selected }, memoryCount)
    if (w.step == WfStep.DONE) return
    val auto by AutoState.state.collectAsState()
    Section("Einrichtung: Schritt ${w.step.number} von 4", highlight = true) {
        Workflow.stepsText(w).forEach { (st, done) ->
            StepRow(done, "${st.number}. ${st.title}", if (st == w.step) hint(st) else "")
        }
        when (w.step) {
            WfStep.CHECKUP -> Button(enabled = !auto.running, onClick = onCheckup) { Text("Checkup ausführen") }
            else -> {}
        }
        Text("Setup, Selbstanalyse und Auto arbeiten nur Chats aus der Checkup-Liste ab. Bis sie vorliegt, sind sie gesperrt.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
    }
}

private fun hint(s: WfStep) = when (s) {
    WfStep.PERMISSIONS -> "Bedienungshilfe aktivieren, Datenschutzhinweis bestätigen und ein Modell wählen (Checkliste unten)."
    WfStep.CHECKUP -> "Liest die Chatliste ein (oberste 50), ohne einen Chat zu öffnen."
    WfStep.SELECTION -> "In der Liste die gewünschten Chats ankreuzen oder Top 10, 20, 30 wählen."
    WfStep.SETUP -> "Setup legt für die angekreuzten Chats ein Profil an."
    WfStep.DONE -> ""
}

/**
 * Selbstanalyse: Anzahl Chats und Nachrichten je Chat, oder frei formuliert ("Scanne 20 Chats, je letzte 200 Nachrichten, analysiere meine Persönlichkeit ...").
 * Die Chats kommen ausschliesslich aus der Checkup-Liste. Ergebnis ist ein Vorschlag fuers Ich-Profil, uebernommen erst nach Bestaetigung.
 */
@Composable
fun SelfAnalysisSection(settings: AppSettings, hasBackend: Boolean, onStart: (List<String>, Int, String) -> Unit) {
    val a11y by ChatAccessibilityService.connected.collectAsState()
    val cu by CheckupState.state.collectAsState()
    val auto by AutoState.state.collectAsState()
    var chats by remember { mutableStateOf(SelfAnalysis.DEFAULT_CHATS) }
    var per by remember { mutableStateOf(SelfAnalysis.DEFAULT_PER_CHAT) }
    var free by remember { mutableStateOf("") }
    var fromSel by remember { mutableStateOf(true) }
    val all = cu.items.map { it.entry.title }
    val selected = cu.items.filter { it.selected }.map { it.entry.title }
    val lock = Workflow.lockReason("die Selbstanalyse", all.size, if (fromSel) selected.size else all.size, needsSelection = fromSel)
    val titles = SelfAnalysis.pickTitles(selected, all, chats, fromSel)
    Section("Selbstanalyse (Persönlichkeit aus deinen Nachrichten)") {
        Text(
            "Liest nacheinander mehrere Chats der Checkup-Liste, wertet nur deine eigenen Nachrichten (mit knappem Kontext ohne Namen) aus und fasst alles zu einem Gesamtbild zusammen: Schreibstil, Ton, Humor, Werte, DISC mit Konfidenz. " +
                "Das Ergebnis ist ein Vorschlag fürs Ich-Profil ohne Chat-Einzelheiten und wird erst nach deiner Bestätigung übernommen.",
            style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip("Aus meiner Auswahl", fromSel, { fromSel = true })
            GlassChip("Oberste der Liste", !fromSel, { fromSel = false })
        }
        IntField("Anzahl Chats (höchstens ${SelfAnalysis.MAX_CHATS})", chats) { chats = it.coerceIn(0, SelfAnalysis.MAX_CHATS) }
        IntField("Nachrichten je Chat (zuletzt gelesen, höchstens ${SelfAnalysis.MAX_PER_CHAT})", per) { per = it.coerceIn(0, SelfAnalysis.MAX_PER_CHAT) }
        OutlinedTextField(
            value = free, onValueChange = { free = it.take(500) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
            label = { Text("Freier Auftrag (optional), z. B. Scanne 20 Chats, je letzte 200 Nachrichten, analysiere meine Persönlichkeit") },
        )
        Text("Zahlen im Text (\"20 Chats\", \"200 Nachrichten\") ersetzen die Felder oben. Der Rest ist ein Schwerpunkt für das Modell und ändert keine Regeln der App.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
        Text("Gewählt: ${titles.size} Chats, je ${per.coerceAtLeast(10)} Nachrichten.", style = MaterialTheme.typography.bodyMedium)
        val ready = lock == null && a11y && hasBackend && settings.privacyAcknowledged && !auto.running && titles.isNotEmpty()
        Button(enabled = ready, onClick = {
            val r = if (free.isNotBlank()) SelfAnalysis.parseRequest(free, chats, per) else app.chatlens.memory.SelfRequest(chats, per.coerceIn(10, SelfAnalysis.MAX_PER_CHAT), "")
            onStart(SelfAnalysis.pickTitles(selected, all, r.chats, fromSel), r.perChat, r.focus)
        }) { Text("Selbstanalyse starten") }
        val why = lock ?: when {
            !a11y -> "Bedienungshilfe ist nicht aktiv."
            !hasBackend -> "Zuerst ein Modell wählen (Tab Modelle)."
            !settings.privacyAcknowledged -> "Datenschutzhinweis bestätigen."
            auto.running -> "Es läuft bereits ein Auftrag."
            else -> null
        }
        if (why != null) Text(why, style = MaterialTheme.typography.bodySmall, color = GlassColors.Warn)
        Text("Pausiert sie nach Fehlern, setzt \"Fortsetzen\" im Bereich Setup die Warteschlange fort. Abbrechen jederzeit mit Abbrechen oder NOTAUS.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
    }
}
