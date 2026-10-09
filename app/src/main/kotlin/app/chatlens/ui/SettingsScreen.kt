package app.chatlens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import app.chatlens.BuildConfig
import app.chatlens.data.AppSettings
import app.chatlens.data.BackendPolicy
import app.chatlens.entitlement.EntitlementProvider
import app.chatlens.messenger.AdapterStatus
import app.chatlens.messenger.MessengerRegistry
import app.chatlens.data.LocalAccel
import java.io.File

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettings: (AppSettings) -> Unit,
    onPickModel: () -> Unit,
    onReleaseModel: () -> Unit,
    overlayGranted: Boolean,
    onOpenOverlayPerm: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onPickVoiceFolder: () -> Unit = {},
    onOpenWizard: () -> Unit = {},
    onStartChat: (app.chatlens.core.ScrollRunConfig) -> Unit = {},
    onMessengersChanged: (String) -> Unit = {},
    onOpenDebug: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Section("Einrichtung") {
            Text(
                if (settings.wizardDone) "Die Einrichtung ist abgeschlossen. Du kannst sie jederzeit erneut durchgehen."
                else "Die Einrichtung ist noch nicht abgeschlossen. Sie lässt sich jederzeit fortsetzen.",
                style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
            )
            Button(onClick = onOpenWizard) { Text(if (settings.wizardDone) "Einrichtung erneut öffnen" else "Einrichtung fortsetzen") }
        }
        Section("Schwebender Punkt (Overlay)") {
            Text(
                "Ein kleiner verschiebbarer Punkt über WhatsApp. Antippen öffnet einen Ring mit Analyse, Vorschlag, Berater, Update und Optionen. " +
                    "Braucht die Erlaubnis \"Über anderen Apps einblenden\". Auf Xiaomi/HyperOS zusätzlich: Autostart erlauben, den Akku auf \"Keine Einschränkungen\" stellen und \"Pop-up-Fenster im Hintergrund\" zulassen (Button unten: App-Einstellungen).",
                style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
            )
            Text(if (overlayGranted) "Erlaubnis erteilt." else "Erlaubnis fehlt.", color = if (overlayGranted) GlassColors.Ok else GlassColors.Warn, style = MaterialTheme.typography.bodyMedium)
            if (!overlayGranted) OutlinedButton(onClick = onOpenOverlayPerm) { Text("Erlaubnis öffnen") }
            OutlinedButton(onClick = onOpenAppSettings) { Text("App-Einstellungen (Akku, Autostart)") }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            SwitchRow("Schwebender Punkt", settings.overlayEnabled && overlayGranted) { on ->
                onSettings(settings.copy(overlayEnabled = on))
                if (!on) {
                    val msg = if (app.chatlens.agent.AgentController.isRunning()) "Punkt entfernt, der laufende Auftrag läuft weiter. Wieder einschalten in den Einstellungen."
                    else app.chatlens.service.OverlayRemoval.MSG_REMOVED
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            Text(
                "Aus: der Punkt wird sofort entfernt und beim nächsten Start nicht wieder angezeigt. Entfernen geht auch im Ring des Punktes (rot).",
                style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
            )
        }

        JobSettingsSection(settings, onSettings, onStartChat)

        Section("Antworten und Senden") {
            Text(
                "ChatLens sendet nie von allein. Vorgeschlagene Antworten kannst du kopieren oder ins WhatsApp-Eingabefeld eintragen lassen; absenden tust du selbst.",
                style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
            )
            if (BackendPolicy.sendAllowed) SwitchRow("Senden-Knopf in der App (experimentell, kann gegen WhatsApp-AGB verstoßen)", settings.experimentalSend) { onSettings(settings.copy(experimentalSend = it)) }
            if (BackendPolicy.sendAllowed && settings.experimentalSend) {
                WarningCard("Experimentell: Automatisiertes Senden kann gegen die WhatsApp-Nutzungsbedingungen verstoßen und zur Kontosperre führen. Jeder einzelne Text muss vorher in einem Dialog bestätigt werden. Standard ist aus.")
            }
        }

        Section("Gedächtnis und Datenschutz") {
            Text(
                "Profile liegen nur auf diesem Gerät, verschlüsselt (AES-GCM, Schlüssel im Android Keystore). allowBackup ist aus, es gibt kein Cloud-Backup. " +
                    "Verwaltung, Export und Löschen im Tab Gedächtnis." +
                    (if (BackendPolicy.apiAllowed) " Bei Backend \"API\" gehen Chattexte beim Anlegen und Aktualisieren an den Server; für Setup und Update ist das lokale Modell gedacht." else " Es werden keine Chatinhalte an einen Server gesendet."),
                style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
            )
            IntField("Steckbrief je Chat: Obergrenze in Zeichen (1500 bis 12000, Standard 6000)", settings.memoryMaxChars) { onSettings(settings.copy(memoryMaxChars = it)) }
            Text("Gespeichert wird zwischen 1500 und 12000 Zeichen. Im Prompt belegt das Gedächtnis höchstens ein Drittel des Kontexts; bei langen Profilen kommen nur passende Abschnitte hinein.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
            SwitchRow("DISC-Einschätzung des Gegenübers (nur Einzelchats)", settings.memoryDisc) { onSettings(settings.copy(memoryDisc = it)) }
            SwitchRow("Ich-Profil nutzen", settings.ichEnabled) { onSettings(settings.copy(ichEnabled = it)) }
            Text(MEMORY_PRIVACY_NOTE, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
        }

        if (BackendPolicy.apiAllowed) Section("API-Backend (OpenAI-kompatibel)") {
            Text(
                "Ruft POST {Base-URL}/chat/completions auf. Es gibt keine Voreinstellung für den Modellnamen; trage den Namen deines Anbieters ein.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = settings.apiBaseUrl, onValueChange = { onSettings(settings.copy(apiBaseUrl = it)) },
                label = { Text("Base-URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = settings.apiKey, onValueChange = { onSettings(settings.copy(apiKey = it)) },
                label = { Text("API-Key (verschlüsselt gespeichert)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = settings.apiModel, onValueChange = { onSettings(settings.copy(apiModel = it)) },
                label = { Text("Modellname") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("Bilder als base64 mitsenden (nur wenn das Modell Vision kann)", settings.apiVision) {
                onSettings(settings.copy(apiVision = it))
            }
            IntField("Kontextlimit API (Zeichen)", settings.contextCharsApi) { onSettings(settings.copy(contextCharsApi = it)) }
        }

        Section("Lokales Modell (LiteRT-LM)") {
            Text("Standard ist Gemma 4 E4B. Modelle laden und auswählen geht im Tab Modelle; hier bleibt der manuelle Pfad und der Import einer Datei.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val extDir = File(ctx.getExternalFilesDir(null), "models")
            var found by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(listOf<File>()) }
            fun scan() {
                found = listOf(extDir, File(ctx.filesDir, "models"))
                    .flatMap { d -> d.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") }?.toList() ?: emptyList() }
            }
            androidx.compose.runtime.LaunchedEffect(settings.localModelPath) { scan() }
            Text(
                "Erwartet gemma-4-E4B-it.litertlm (rund 3,66 GB) von Hugging Face (litert-community/gemma-4-E4B-it-litert-lm). " +
                    "Die Datei ist nicht in der APK. Zwei Wege: (1) per adb in den App-Ordner kopieren und unten wählen, (2) per Dateiauswahl importieren " +
                    "(kopiert 3,66 GB zusätzlich in den App-Speicher). Auf einem Gerät ungetestet.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("App-Ordner für adb push: ${extDir.absolutePath}", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = settings.localModelPath, onValueChange = { onSettings(settings.copy(localModelPath = it.trim())) },
                label = { Text("Pfad der Modelldatei") }, modifier = Modifier.fillMaxWidth(),
            )
            val cur = File(settings.localModelPath)
            Text(
                if (cur.isFile) "Datei gefunden: ${cur.name}, ${cur.length() / (1024 * 1024)} MB" else "Keine Datei unter diesem Pfad.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scan() }) { Text("App-Ordner durchsuchen") }
                OutlinedButton(onClick = onPickModel) { Text("Datei importieren") }
            }
            found.forEach { f ->
                OutlinedButton(onClick = { onSettings(settings.copy(localModelPath = f.absolutePath)) }) {
                    Text("Wählen: ${f.name} (${f.length() / (1024 * 1024)} MB)")
                }
            }
            OutlinedButton(onClick = onReleaseModel) { Text("Modell aus dem Speicher entladen") }
            SwitchRow("Bilder als Pixel an das Modell geben (Vision)", settings.localVision) { onSettings(settings.copy(localVision = it)) }
            Text(
                "Vision: Der Dateikopf von gemma-4-E4B-it.litertlm nennt tf_lite_vision_encoder und tf_lite_vision_adapter. " +
                    "Die Datei gemma-4-E4B-it-gpu.litertlm zeigte im Kopf nur einen Text-Decoder; dafür Vision ausschalten. " +
                    "Ob Vision auf diesem Gerät läuft, ist ungetestet. Ohne Vision liefert ML Kit OCR den Text im Bild.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Rechenweg", style = MaterialTheme.typography.labelLarge)
            LocalAccel.entries.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = settings.localAccel == a, onClick = { onSettings(settings.copy(localAccel = a)) })
                    Text(if (a == LocalAccel.CPU) "CPU (Standard)" else "GPU (laut Herstellerkarte schneller, auf diesem Gerät ungetestet)")
                }
            }
            Text("Kontextgröße (Token)", style = MaterialTheme.typography.labelLarge)
            listOf(0 to "Automatisch maximal (Standard)", 4096 to "4096", 8192 to "8192", 16384 to "16384", 32768 to "32768 (Obergrenze von Gemma 4 E4B)").forEach { (v, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = settings.localMaxTokens == v, onClick = { onSettings(settings.copy(localMaxTokens = v)) })
                    Text(label)
                }
            }
            Text(
                "Automatisch nimmt beim Laden die größte Stufe, die das Telefon schafft (Modellobergrenze, begrenzt durch den freien Arbeitsspeicher nach einer " +
                    "Schätzung für den KV-Cache), und fällt bei Ladefehler oder Speichermangel Stufe für Stufe zurück. Die erfolgreiche Stufe wird je Modell und " +
                    "Rechenweg gemerkt, eine höhere wird alle 7 Tage neu getestet. Mehr Kontext braucht mehr Arbeitsspeicher und verlängert das Einlesen " +
                    "(Prefill laut Google etwa 1300 Token/s auf GPU, gemessen am Galaxy S26 Ultra; auf diesem Gerät ungemessen). Eine manuelle Wahl ist die Obergrenze, " +
                    "auch sie fällt bei Fehlern zurück.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Zuletzt genutzt: ${app.chatlens.llm.LiteRtLmBackend.contextInfo()}", style = MaterialTheme.typography.bodySmall)
            IntField("Kontextlimit lokal (Zeichen, 0 = automatisch aus der Stufe, ältere Zeilen fallen weg)", settings.contextCharsLocal) {
                onSettings(settings.copy(contextCharsLocal = it))
            }
        }

        VoiceSection(settings, onSettings, onPickVoiceFolder)

        Section("Auslesen") {
            SwitchRow("Gruppenchat: erste Textzeile eingehender Nachrichten als Absender werten", settings.groupChat) {
                onSettings(settings.copy(groupChat = it))
            }
            SwitchRow("Bilder per Screenshot erfassen", settings.captureImages) { onSettings(settings.copy(captureImages = it)) }
            SwitchRow("OCR auf erfassten Bildern (ML Kit, offline)", settings.ocrImages) { onSettings(settings.copy(ocrImages = it)) }
            IntField("Maximale Anzahl Bilder pro Lauf", settings.maxImages) { onSettings(settings.copy(maxImages = it)) }
        }

        Section("Limits") {
            Text("Scroll-Geschwindigkeit, Wartezeiten und Schrittweite stehen im Debug-Bereich (Entwickleroption, Standard: maximal schnell).", style = MaterialTheme.typography.bodySmall)
            IntField("Sicherheitsobergrenze: maximale Scroll-Schritte pro Lauf", settings.maxScrollCap) { onSettings(settings.copy(maxScrollCap = it)) }
            IntField("Maximale Läufe pro Stunde", settings.maxRunsPerHour) { onSettings(settings.copy(maxRunsPerHour = it)) }
        }

        MessengerSection(settings, onSettings, onMessengersChanged)
        PrivacySection(onOpenPrivacy)
        ProSection()
        AboutSection(settings, onSettings, onOpenDebug)
    }
}

/** Messenger adapters (0.3.0): WhatsApp is active; Signal and Telegram are prepared but not yet switchable. */
@Composable
private fun MessengerSection(settings: AppSettings, onSettings: (AppSettings) -> Unit, onChanged: (String) -> Unit) {
    Section("Messenger") {
        Text(
            "ChatLens liest nur Apps, die hier eingeschaltet sind. Andere Apps bleiben unberührt.",
            style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
        )
        val enabled = MessengerRegistry.enabledIds(settings.enabledMessengers)
        MessengerRegistry.all.forEach { a ->
            val active = a.status == AdapterStatus.ACTIVE
            if (active && a.id != "whatsapp") {
                SwitchRow(a.displayName, checked = a.id in enabled) {
                    val v = MessengerRegistry.toggled(settings.enabledMessengers, a.id, it)
                    onSettings(settings.copy(enabledMessengers = v)); onChanged(v)
                }
            } else {
                // WhatsApp is always active; Signal and Telegram cannot be switched: a status line only, no switch that does nothing
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(a.displayName, style = MaterialTheme.typography.bodyMedium, color = GlassColors.Text)
                    Text(if (active) "aktiv" else "in Vorbereitung", style = MaterialTheme.typography.bodyMedium, color = if (active) GlassColors.Ok else GlassColors.TextDim)
                }
            }
        }
        Text(
            "Signal und Telegram sind als Profil vorbereitet, aber nicht kalibriert und nicht schaltbar. Erst nach einem Test auf einem Gerät.",
            style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
        )
    }
}

@Composable
private fun PrivacySection(onOpenPrivacy: () -> Unit) {
    Section("Datenschutz") {
        Text(PRIVACY_TEXT, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
        val hasUrl = BuildConfig.PRIVACY_URL.isNotBlank()
        OutlinedButton(onClick = onOpenPrivacy) { Text(if (hasUrl) "Datenschutzerklärung im Browser öffnen" else "Datenschutzerklärung anzeigen") }
    }
}

@Composable
private fun ProSection() {
    Section("ChatLens Pro") {
        Text(
            EntitlementProvider.current.describe(),
            style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
        )
    }
}

@Composable
private fun AboutSection(settings: AppSettings, onSettings: (AppSettings) -> Unit, onOpenDebug: () -> Unit) {
    var taps by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Section("Über ChatLens") {
        Text(
            "Version ${BuildConfig.VERSION_NAME}" + if (BackendPolicy.apiAllowed) " (Full)" else " (Play)",
            style = MaterialTheme.typography.bodyMedium, color = GlassColors.Text,
            modifier = Modifier.clickable {
                taps++
                if (!settings.developerMode && taps >= 7) {
                    onSettings(settings.copy(developerMode = true)); taps = 0
                    android.widget.Toast.makeText(ctx, "Entwickleroptionen eingeschaltet.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }.heightIn(min = 48.dp),
        )
        if (settings.developerMode) {
            OutlinedButton(onClick = onOpenDebug) { Text("Debug öffnen") }
            OutlinedButton(onClick = { onSettings(settings.copy(developerMode = false)) }) { Text("Entwickleroptionen ausblenden") }
        } else {
            Text("Entwickleroptionen: die Versionszeile siebenmal antippen.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
        }
    }
}
