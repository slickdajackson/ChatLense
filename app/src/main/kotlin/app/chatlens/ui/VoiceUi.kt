package app.chatlens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import app.chatlens.asr.VoiceAutoDownload
import app.chatlens.data.AppLog
import app.chatlens.models.DeviceProbe
import app.chatlens.models.DlStatus
import app.chatlens.models.ModelCatalog
import app.chatlens.models.ModelDownloads
import app.chatlens.service.ModelDownloadService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.chatlens.asr.FileVoiceCache
import app.chatlens.asr.VoiceRuntime
import app.chatlens.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voice messages: switch, folder grant, limits, self-test. Everything stays local; the model (Parakeet) is loaded on the Models tab.
 * The folder is under Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes and is granted through the system picker.
 */
@Composable
fun VoiceSection(settings: AppSettings, onSettings: (AppSettings) -> Unit, onPickFolder: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var askMetered by remember { mutableStateOf<Long?>(null) }
    val dl by ModelDownloads.state.collectAsState()
    val status = remember(settings.voiceTranscribe, settings.voiceTreeUri, tick) { VoiceRuntime.status(ctx, settings) }
    val cache = remember(tick) { FileVoiceCache(VoiceRuntime.cacheDir(ctx)) }

    Section("Sprachnachrichten (lokale Transkription)") {
        Text(
            "Erkennt Sprachnachrichten im Chat, ordnet sie den Audiodateien im freigegebenen WhatsApp-Ordner zu und fügt den Text als Transkript in den Verlauf ein. " +
                "Die Erkennung läuft ganz auf dem Gerät (Parakeet TDT 0.6B v3 über sherpa-onnx), es wird nichts hochgeladen. Das Modell (etwa 670 MB) lädst du im Tab Modelle. " +
                "Die Zuordnung nutzt Uhrzeit und Dauer und ist eine Heuristik; unsichere Nachrichten bleiben \"nicht transkribiert\".",
            style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
        )
        SwitchRow("Sprachnachrichten transkribieren", settings.voiceTranscribe) {
            onSettings(settings.copy(voiceTranscribe = it))
            if (it) scope.launch {
                val e = withContext(Dispatchers.IO) { runCatching { ModelCatalog.load(ctx).byId(VoiceRuntime.MODEL_ID) }.getOrNull() } ?: return@launch
                val unmetered = withContext(Dispatchers.IO) { DeviceProbe.read(ctx).unmetered }
                val ready = withContext(Dispatchers.IO) { VoiceRuntime.modelDir(ctx) != null }
                val a = VoiceAutoDownload.trigger(ready, ModelDownloads.get(VoiceRuntime.MODEL_ID).status, unmetered, e.sizeBytes,
                    start = { m -> ModelDownloadService.start(ctx, e.id, m) }, log = { m -> AppLog.i(m) })
                when (a) {
                    VoiceAutoDownload.Action.START -> hint = VoiceAutoDownload.hint(e.sizeBytes, a)
                    VoiceAutoDownload.Action.ASK_METERED -> askMetered = e.sizeBytes
                    VoiceAutoDownload.Action.ALREADY_RUNNING -> hint = VoiceAutoDownload.hint(e.sizeBytes, a)
                    VoiceAutoDownload.Action.NOTHING -> hint = null
                }
            }
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GlassColors.Accent) }
        val dlUi = dl[VoiceRuntime.MODEL_ID]
        if (dlUi != null && dlUi.status != DlStatus.IDLE && status.modelDir == null) {
            DownloadStatus(
                dlUi, "Parakeet",
                onRetry = { scope.launch { retryDownload(ctx) { askMetered = it } } },
                onPause = { ModelDownloadService.pause(ctx) },
                onCancel = { ModelDownloadService.cancel(ctx) },
            )
        }
        askMetered?.let { size ->
            AlertDialog(
                onDismissRequest = { askMetered = null },
                title = { Text("Parakeet über mobile Daten laden?") },
                text = { Text("Es wurde kein WLAN erkannt. Der Download ist einmalig ${VoiceAutoDownload.sizeText(size)} groß und kann Datenvolumen kosten. Empfehlung: WLAN. Die Transkription startet erst nach erfolgreicher Prüfung.") },
                confirmButton = { TextButton(onClick = {
                    askMetered = null
                    VoiceAutoDownload.confirmMetered(size, { m -> ModelDownloadService.start(ctx, VoiceRuntime.MODEL_ID, m) }, { m -> AppLog.i(m) })
                    hint = "Parakeet wird über mobile Daten geladen (${VoiceAutoDownload.sizeText(size)})."
                }) { Text("Mit mobilen Daten laden") } },
                dismissButton = { TextButton(onClick = { askMetered = null; hint = "Kein Download. Im WLAN den Schalter erneut einschalten oder im Tab Modelle laden." }) { Text("Später im WLAN") } },
            )
        }
        Text(status.text(), style = MaterialTheme.typography.bodyMedium, color = if (status.ready) GlassColors.Ok else GlassColors.Warn)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickFolder) { Text("Ordner wählen") }
            OutlinedButton(onClick = { tick++ }) { Text("Status prüfen") }
        }
        Text(
            "Zu wählen ist der Ordner Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes (bei WhatsApp Business com.whatsapp.w4b). " +
                "In der Systemauswahl oben auf \"Diesen Ordner verwenden\" tippen. Ob HyperOS diesen Ordner anbietet, ist ungeprüft.",
            style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
        )
        IntField("Höchstens so viele Sprachnachrichten je Lauf (neueste zuerst)", settings.voiceMaxPerChat) { onSettings(settings.copy(voiceMaxPerChat = it)) }
        IntField("Längere Sprachnachrichten überspringen (Sekunden)", settings.voiceMaxSeconds) { onSettings(settings.copy(voiceMaxSeconds = it)) }
        IntField("Zeittoleranz für die Zuordnung (Minuten)", settings.voiceToleranceMin) { onSettings(settings.copy(voiceToleranceMin = it)) }
        IntField("Rechenkerne für die Erkennung", settings.voiceThreads) { onSettings(settings.copy(voiceThreads = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = status.modelDir != null && status.folderGranted && !testing, onClick = {
                testing = true; testMsg = "Läuft ..."
                scope.launch { testMsg = withContext(Dispatchers.Default) { VoiceRuntime.selfTest(ctx, settings) }; testing = false }
            }) { Text("Selbsttest mit neuester Datei") }
            OutlinedButton(onClick = { cache.clear(); tick++ }) { Text("Transkript-Zwischenspeicher leeren (${cache.count()})") }
        }
        testMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GlassColors.Accent) }
    }
}

/** Retry after an error or a pause: same rules as when turning it on (Wi-Fi right away, otherwise ask once). What is already downloaded stays; resume uses Range requests. */
private suspend fun retryDownload(ctx: android.content.Context, ask: (Long) -> Unit) {
    val e = withContext(Dispatchers.IO) { runCatching { ModelCatalog.load(ctx).byId(VoiceRuntime.MODEL_ID) }.getOrNull() } ?: return
    val unmetered = withContext(Dispatchers.IO) { DeviceProbe.read(ctx).unmetered }
    val a = VoiceAutoDownload.trigger(false, DlStatus.IDLE, unmetered, e.sizeBytes, { m -> ModelDownloadService.start(ctx, e.id, m) }, { m -> AppLog.i(m) })
    if (a == VoiceAutoDownload.Action.ASK_METERED) ask(e.sizeBytes)
}
