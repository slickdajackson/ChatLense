package app.chatlens.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.chatlens.agent.CheckupState
import app.chatlens.asr.VoiceAutoDownload
import app.chatlens.asr.VoiceRuntime
import app.chatlens.checkup.CheckupItem
import app.chatlens.data.AppSettings
import app.chatlens.models.DlStatus
import app.chatlens.models.DlUi
import app.chatlens.models.ModelCatalog
import app.chatlens.models.ModelDownloads
import app.chatlens.service.ChatAccessibilityService
import app.chatlens.service.ModelDownloadService
import app.chatlens.data.AppLog
import app.chatlens.wizard.WizardAction
import app.chatlens.wizard.WizardEffect
import app.chatlens.wizard.WizardFacts
import app.chatlens.wizard.WizardFlow
import app.chatlens.wizard.WizardState
import app.chatlens.wizard.WizardStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Everything the pages display. The pages themselves hold no state (so they can be tested as render images). */
class WizardUi(
    val state: WizardState,
    val facts: WizardFacts,
    val voiceOn: Boolean = true,
    val parakeetReady: Boolean = false,
    val voiceFolderSet: Boolean = false,
    val gemmaDl: DlUi? = null,
    val parakeetDl: DlUi? = null,
    val hint: String? = null,
    val items: List<CheckupItem> = emptyList(),
    val overlayEnabled: Boolean = false,
    val checkupOnStart: Boolean = false,
)

/** Page actions that go beyond the state machine. */
class WizardActions(
    val dispatch: (WizardAction) -> Unit = {},
    val loadGemma: () -> Unit = {},
    val pickModelFile: () -> Unit = {},
    val setVoice: (Boolean) -> Unit = {},
    val pickVoiceFolder: () -> Unit = {},
    val toggleItem: (String) -> Unit = {},
    val quick: (Int) -> Unit = {},
    val setOverlayEnabled: (Boolean) -> Unit = {},
    val setCheckupOnStart: (Boolean) -> Unit = {},
    val startSetup: () -> Unit = {},
    val pauseDownload: () -> Unit = {},
    val cancelDownload: () -> Unit = {},
    val retryParakeet: () -> Unit = {},
)

@Composable
private fun BigButton(text: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = true, modifier: Modifier = Modifier) {
    val m = modifier.height(56.dp)
    if (primary) Button(onClick = onClick, enabled = enabled, modifier = m, shape = RoundedCornerShape(18.dp)) { Text(text, style = MaterialTheme.typography.titleMedium) }
    else OutlinedButton(onClick = onClick, enabled = enabled, modifier = m, shape = RoundedCornerShape(18.dp)) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun Dots(step: WizardStep) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        WizardStep.entries.forEach { s ->
            val cur = s == step
            Box(
                Modifier.height(10.dp).width(if (cur) 28.dp else 10.dp)
                    .background(if (cur) GlassColors.Accent else if (s.ordinal < step.ordinal) GlassColors.Ok else androidx.compose.ui.graphics.Color(0x55FFFFFF), CircleShape),
            )
        }
    }
}

@Composable
private fun Title(t: String) = Text(t, style = MaterialTheme.typography.headlineMedium, color = GlassColors.Text, fontWeight = FontWeight.SemiBold)

@Composable
private fun Body(t: String) = Text(t, style = MaterialTheme.typography.bodyLarge, color = GlassColors.TextDim)

@Composable
private fun Done(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, color = GlassColors.Ok, fontWeight = FontWeight.SemiBold)

/** One wizard page with a header (dots, "k von 6", skip), content, and a footer (back, next). */
@Composable
fun WizardScreen(ui: WizardUi, act: WizardActions, modifier: Modifier = Modifier) {
    val s = ui.state
    val d = act.dispatch
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Dots(s.step)
                Text(WizardFlow.progressText(s), style = MaterialTheme.typography.labelLarge, color = GlassColors.TextDim)
            }
            if (s.step != WizardStep.DONE) TextButton(onClick = { d(WizardAction.Leave) }) { Text("Pausieren", color = GlassColors.TextDim) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (s.step) {
                WizardStep.WELCOME -> {
                    Title("Willkommen")
                    Body("ChatLens liest deine WhatsApp Chats und fasst sie zusammen.")
                    Body(
                        if (app.chatlens.data.BackendPolicy.apiAllowed) "Standardmäßig läuft alles auf dem Telefon. Im API Modus gehen Chattexte an deinen Server."
                        else "Alles läuft auf dem Telefon. Kein Chat geht an einen Server.",
                    )
                    Body("ChatLens sendet nie Nachrichten.")
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = s.consent, role = androidx.compose.ui.semantics.Role.Checkbox) { d(WizardAction.Consent(it)) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = s.consent, onCheckedChange = null)
                        Text("Ich habe das gelesen und stimme zu.", style = MaterialTheme.typography.titleMedium, color = GlassColors.Text)
                    }
                }
                WizardStep.A11Y -> {
                    Title("Bedienungshilfe")
                    Body("ChatLens braucht sie, um Chats auf dem Bildschirm zu lesen.")
                    if (ui.facts.a11y) Done("Eingeschaltet")
                    else {
                        // U2: disclosure before the system dialog, with separate consent (Play requirement "Prominent Disclosure")
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Was ChatLens mit der Bedienungshilfe tut", style = MaterialTheme.typography.titleMedium, color = GlassColors.Text)
                                Body("ChatLens liest den Text des Chats, den du in WhatsApp öffnest. Nur auf deinen Auftrag.")
                                Body("Gelesen werden Namen, Nachrichten und Zeiten. Keine Passwörter, keine anderen Apps.")
                                Body("ChatLens sendet nie Nachrichten und tippt nichts ohne deine Bestätigung.")
                                Body("Alles bleibt verschlüsselt auf dem Telefon. Du kannst es jederzeit ausschalten.")
                                Body("Auf manchen Geräten heißt die Bedienungshilfe Barrierefreiheit.")
                                Body("Ohne Bedienungshilfe kann ChatLens keinen Chat lesen.")
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .toggleable(value = s.a11yConsent, role = androidx.compose.ui.semantics.Role.Checkbox) { d(WizardAction.A11yConsent(it)) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = s.a11yConsent, onCheckedChange = null)
                            Text("Ich habe das gelesen und erlaube ChatLens, den geöffneten Chat zu lesen.", style = MaterialTheme.typography.titleMedium, color = GlassColors.Text, modifier = Modifier.padding(start = 8.dp))
                        }
                        BigButton("Einschalten", { d(WizardAction.OpenA11y) }, enabled = s.a11yConsent, modifier = Modifier.fillMaxWidth())
                        if (!s.a11yConsent) Body("Zuerst zustimmen, dann einschalten.")
                        Body(if (s.waiting) "Warte auf die Einschaltung. Danach geht es automatisch weiter." else "Es öffnet sich die Systemeinstellung. Dort ChatLens wählen.")
                    }
                }
                WizardStep.OVERLAY -> {
                    Title("Schwebender Punkt")
                    Body("Der Punkt liegt über WhatsApp und startet das Lesen.")
                    Body("Ohne Erlaubnis startest du Aufträge in der App. Du kannst sie jederzeit widerrufen.")
                    if (ui.facts.overlay) Done("Erlaubt")
                    else {
                        BigButton("Erlauben", { d(WizardAction.OpenOverlay) }, modifier = Modifier.fillMaxWidth())
                        Body(if (s.waiting) "Warte auf die Erlaubnis. Danach geht es automatisch weiter." else "Es öffnet sich die Systemeinstellung. Dort ChatLens erlauben.")
                    }
                }
                WizardStep.MODEL -> {
                    Title("Modell")
                    Body("Gemma läuft ganz auf dem Telefon.")
                    if (ui.facts.modelReady) Done("Modell bereit")
                    else {
                        val g = ui.gemmaDl
                        if (g != null && g.status != DlStatus.IDLE) {
                            DownloadStatus(g, "Gemma", onRetry = act.loadGemma, onPause = act.pauseDownload, onCancel = act.cancelDownload)
                            if (g.status == DlStatus.FAILED || g.status == DlStatus.PAUSED) BigButton("Erneut laden", act.loadGemma, modifier = Modifier.fillMaxWidth())
                        } else {
                            BigButton("Gemma laden", act.loadGemma, modifier = Modifier.fillMaxWidth())
                        }
                        BigButton("Vorhandene Datei wählen", act.pickModelFile, primary = false, modifier = Modifier.fillMaxWidth())
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Sprachnachrichten verstehen", style = MaterialTheme.typography.titleMedium, color = GlassColors.Text, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = ui.voiceOn, onCheckedChange = act.setVoice)
                    }
                    if (ui.voiceOn) {
                        val p = ui.parakeetDl
                        if (ui.parakeetReady) Done("Parakeet bereit")
                        else if (p != null && p.status != DlStatus.IDLE) DownloadStatus(p, "Parakeet", onRetry = act.retryParakeet, onPause = act.pauseDownload, onCancel = act.cancelDownload)
                        if (!ui.voiceFolderSet) {
                            Body("Wähle noch den Ordner der Sprachnachrichten.")
                            BigButton("Ordner wählen", act.pickVoiceFolder, primary = false, modifier = Modifier.fillMaxWidth())
                        } else Done("Ordner gewählt")
                    }
                    ui.hint?.let { Body(it) }
                }
                WizardStep.CHECKUP -> {
                    Title("Chats einlesen")
                    if (ui.items.isEmpty()) {
                        Body("ChatLens liest jetzt deine Chatliste. Dabei öffnet sich WhatsApp.")
                        if (!s.announced) {
                            BigButton("Chats einlesen", { d(WizardAction.AnnounceRead) }, enabled = ui.facts.a11y, modifier = Modifier.fillMaxWidth())
                            if (!ui.facts.a11y) Body("Zuerst die Bedienungshilfe einschalten.")
                        } else {
                            GlassCard(Modifier.fillMaxWidth(), highlight = true) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Gleich öffnet sich WhatsApp.", style = MaterialTheme.typography.titleMedium, color = GlassColors.Accent)
                                    Body("ChatLens liest nur die Liste und öffnet keinen Chat.")
                                    BigButton("WhatsApp öffnen und lesen", { d(WizardAction.ConfirmRead) }, modifier = Modifier.fillMaxWidth())
                                    BigButton("Abbrechen", { d(WizardAction.CancelAnnounce) }, primary = false, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    } else {
                        Body("Wähle die Chats, die ChatLens kennenlernen soll.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(10, 20, 30).forEach { n -> GlassChip("Top $n", false, { act.quick(n) }) }
                        }
                        Text("${ui.items.count { it.selected }} von ${ui.items.size} gewählt", style = MaterialTheme.typography.labelLarge, color = GlassColors.Text)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            ui.items.forEach { it ->
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                        .toggleable(value = it.selected, role = androidx.compose.ui.semantics.Role.Checkbox) { _ -> act.toggleItem(it.entry.title) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = it.selected, onCheckedChange = null)
                                    Text(it.entry.title, style = MaterialTheme.typography.bodyLarge, color = GlassColors.Text, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                WizardStep.DONE -> {
                    Title("Fertig")
                    Body("Schalte den Punkt ein und starte, wenn du magst, das Setup.")
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Punkt einschalten", style = MaterialTheme.typography.titleMedium, color = GlassColors.Text, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = ui.overlayEnabled, onCheckedChange = act.setOverlayEnabled)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Beim Start Chats neu einlesen", style = MaterialTheme.typography.titleMedium, color = GlassColors.Text, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = ui.checkupOnStart, onCheckedChange = act.setCheckupOnStart)
                    }
                    Body("Das Einlesen beim Start ist aus, solange du es nicht einschaltest.")
                    BigButton("Setup starten", act.startSetup, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("Zurück", { d(WizardAction.Back) }, enabled = s.step != WizardStep.WELCOME, primary = false, modifier = Modifier.weight(1f))
            when {
                s.step == WizardStep.DONE -> BigButton("Fertig", { d(WizardAction.Finish) }, modifier = Modifier.weight(1f))
                WizardFlow.canLater(s) && !WizardFlow.canNext(s, ui.facts) -> BigButton("Später", { d(WizardAction.LaterStep) }, primary = false, modifier = Modifier.weight(1f))
                else -> BigButton("Weiter", { d(WizardAction.Next) }, enabled = WizardFlow.canNext(s, ui.facts), modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Integration with the app (permissions, download, WhatsApp launch). Every action comes from a user tap. */
class WizardHostActions(
    val openA11y: () -> Unit,
    val openOverlay: () -> Unit,
    val overlayGranted: () -> Boolean,
    val pickModelFile: () -> Unit,
    val pickVoiceFolder: () -> Unit,
    val useDownloadedGemma: () -> Unit,
    /** Starts the checkup. Opens WhatsApp. Called only after the announcement and the second tap. */
    val startCheckup: () -> Unit,
    /** Closes the wizard. [goSetup]: then go to the start page with setup. */
    val close: (goSetup: Boolean) -> Unit,
)

@Composable
fun WizardHost(settings: AppSettings, onSettings: (AppSettings) -> Unit, host: WizardHostActions) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cur by rememberUpdatedState(settings)
    val a11y by ChatAccessibilityService.connected.collectAsState()
    val overlay by produceState(host.overlayGranted()) { while (true) { value = host.overlayGranted(); delay(700) } }
    val cs by CheckupState.state.collectAsState()
    val dl by ModelDownloads.state.collectAsState()
    var hint by remember { mutableStateOf<String?>(null) }
    var askMetered by remember { mutableStateOf(false) }
    val start = remember { if (settings.wizardDone) WizardStep.WELCOME else WizardStep.parse(settings.wizardStep) }
    var st by remember { mutableStateOf(WizardState(step = start, consent = settings.privacyAcknowledged, a11yConsent = settings.a11yConsent)) }

    val modelReady = File(settings.localModelPath).isFile
    val parakeetReady = remember(dl, settings.voiceTranscribe) { VoiceRuntime.modelDir(ctx) != null }
    val facts = WizardFacts(a11y, overlay, modelReady, cs.items.size)
    val factsNow by rememberUpdatedState(facts)

    fun dispatch(a: WizardAction) {
        val r = WizardFlow.reduce(st, factsNow, a)
        st = r.state
        var s = cur.copy(wizardStep = r.state.step.name)
        if (a is WizardAction.Consent) s = s.copy(privacyAcknowledged = a.value)
        if (a is WizardAction.A11yConsent) s = s.copy(a11yConsent = a.value)
        if (r.state.finished) s = s.copy(wizardDone = true, wizardSkipped = false)
        if (r.state.skipped) s = s.copy(wizardSkipped = true)
        onSettings(s)
        when (r.effect) {
            WizardEffect.OPEN_A11Y_SETTINGS -> host.openA11y()
            WizardEffect.OPEN_OVERLAY_SETTINGS -> host.openOverlay()
            WizardEffect.LAUNCH_CHECKUP_AND_WHATSAPP -> { AppLog.i("ASSISTENT: Checkup auf ausdruecklichen Tipp gestartet (WhatsApp oeffnet sich)."); host.startCheckup() }
            WizardEffect.NONE -> {}
        }
        if (r.state.finished) host.close(false)
        if (r.state.skipped) host.close(false)
    }

    LaunchedEffect(a11y, overlay) { dispatch(WizardAction.Observe) }

    // After the Gemma download: set the file as the active model
    val gemmaDl = dl[ModelCatalog.DEFAULT_ID]
    LaunchedEffect(gemmaDl?.status, modelReady) {
        if (gemmaDl?.status == DlStatus.DONE && !modelReady) host.useDownloadedGemma()
    }
    // Parakeet downloads automatically (as decided), but only after the Gemma download is no longer running
    val gemmaBusy = gemmaDl?.status == DlStatus.RUNNING || gemmaDl?.status == DlStatus.VERIFYING
    LaunchedEffect(st.step, settings.voiceTranscribe, gemmaBusy, parakeetReady) {
        if (st.step != WizardStep.MODEL || !settings.voiceTranscribe || parakeetReady || gemmaBusy) return@LaunchedEffect
        val e = withContext(Dispatchers.IO) { runCatching { ModelCatalog.load(ctx).byId(VoiceRuntime.MODEL_ID) }.getOrNull() } ?: return@LaunchedEffect
        val unmetered = withContext(Dispatchers.IO) { app.chatlens.models.DeviceProbe.read(ctx).unmetered }
        val a = VoiceAutoDownload.trigger(false, ModelDownloads.get(VoiceRuntime.MODEL_ID).status, unmetered, e.sizeBytes,
            start = { m -> ModelDownloadService.start(ctx, e.id, m) }, log = { m -> AppLog.i(m) })
        hint = when (a) {
            VoiceAutoDownload.Action.ASK_METERED -> "Kein WLAN. Parakeet lädt später im WLAN im Tab Modelle."
            else -> null
        }
    }

    fun gemma(metered: Boolean) = ModelDownloadService.start(ctx, ModelCatalog.DEFAULT_ID, metered)
    val ui = WizardUi(
        st, facts, settings.voiceTranscribe, parakeetReady, settings.voiceTreeUri.isNotBlank(), gemmaDl, dl[VoiceRuntime.MODEL_ID], hint,
        cs.items, settings.overlayEnabled, settings.checkupOnStart,
    )
    val act = WizardActions(
        dispatch = ::dispatch,
        loadGemma = {
            scope.launch {
                val unmetered = withContext(Dispatchers.IO) { app.chatlens.models.DeviceProbe.read(ctx).unmetered }
                if (unmetered == false) askMetered = true else gemma(false)
            }
        },
        pickModelFile = host.pickModelFile,
        setVoice = { onSettings(cur.copy(voiceTranscribe = it)) },
        pickVoiceFolder = host.pickVoiceFolder,
        toggleItem = { CheckupState.toggle(it) },
        quick = { n -> onSettings(cur.copy(setupCount = n)); CheckupState.quick(n, cur.setupIncludeGroups, cur.setupPinnedCounts) },
        setOverlayEnabled = { onSettings(cur.copy(overlayEnabled = it)) },
        setCheckupOnStart = { onSettings(cur.copy(checkupOnStart = it)) },
        pauseDownload = { ModelDownloadService.pause(ctx) },
        cancelDownload = { ModelDownloadService.cancel(ctx) },
        retryParakeet = { ModelDownloadService.start(ctx, VoiceRuntime.MODEL_ID, false) },
        startSetup = {
            st = st.copy(finished = true)
            onSettings(cur.copy(wizardDone = true, wizardSkipped = false, wizardStep = WizardStep.DONE.name))
            host.close(true)
        },
    )
    // U9: the wizard sits outside the scaffold; from Android 15 (edge-to-edge) inset the status bar, navigation bar, and cutout
    WizardScreen(ui, act, Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
    if (askMetered) {
        AlertDialog(
            onDismissRequest = { askMetered = false },
            title = { Text("Gemma über mobile Daten laden?") },
            text = { Text("Es wurde kein WLAN erkannt. Der Download ist mehrere Gigabyte groß. Besser im WLAN.") },
            confirmButton = { TextButton(onClick = { askMetered = false; gemma(true) }) { Text("Mit mobilen Daten laden") } },
            dismissButton = { TextButton(onClick = { askMetered = false }) { Text("Später im WLAN") } },
        )
    }
}
