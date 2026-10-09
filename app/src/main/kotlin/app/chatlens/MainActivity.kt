package app.chatlens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Scaffold
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import app.chatlens.agent.AgentController
import app.chatlens.agent.AutoStart
import app.chatlens.agent.AutoState
import app.chatlens.agent.ConfirmBroker
import app.chatlens.agent.PromptBookState
import app.chatlens.agent.PromptChoice
import app.chatlens.agent.PromptChoiceBroker
import app.chatlens.assist.ExperimentalSender
import app.chatlens.assist.InsertOutcome
import app.chatlens.assist.ReplyInserter
import app.chatlens.data.MemoryRepo
import app.chatlens.service.ChatAccessibilityService
import app.chatlens.service.OverlayService
import app.chatlens.ui.AutoScreen
import app.chatlens.ui.GlassBackground
import app.chatlens.ui.GlassColors
import app.chatlens.ui.GlassTheme
import app.chatlens.ui.MemoryScreen
import app.chatlens.ui.ModelsScreen
import app.chatlens.models.ModelAdvisor
import app.chatlens.models.ModelEntry
import app.chatlens.service.ModelDownloadService
import app.chatlens.ui.StartScreen
import kotlinx.coroutines.delay
import app.chatlens.agent.ProfileStore
import app.chatlens.core.ScrollRunConfig
import app.chatlens.data.AppLog
import app.chatlens.data.AppSettings
import app.chatlens.data.SettingsRepo
import app.chatlens.llm.LiteRtLmBackend
import app.chatlens.service.AgentForegroundService
import app.chatlens.service.DebugDumper
import app.chatlens.ui.DebugScreen
import app.chatlens.ui.SettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var repo: SettingsRepo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        AgentForegroundService.ensureChannels(this)
        repo = SettingsRepo(this)
        handleIntent(intent)
        setContent {
            // A6: Systemeinstellung "Animationen entfernen" (Animatorskala 0) wird respektiert
            val animate = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
            androidx.compose.runtime.CompositionLocalProvider(app.chatlens.ui.LocalGlassAnimate provides animate) {
                GlassTheme {
                    GlassBackground { Root() }
                }
            }
        }
    }

    private var tabReq by mutableStateOf<Int?>(null)
    private var overlayGranted by mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.hasExtra(EXTRA_TAB)) tabReq = i.getIntExtra(EXTRA_TAB, 0)
        i.getStringExtra(EXTRA_TASK)?.let { name ->
            runCatching { app.chatlens.core.TaskMode.valueOf(name) }.getOrNull()?.let { t ->
                repo.save(repo.load().copy(lastTask = t))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayGranted = Settings.canDrawOverlays(this)
    }

    private fun backendReady(s: AppSettings): Boolean = when (s.backend) {
        app.chatlens.data.BackendChoice.API -> s.apiBaseUrl.isNotBlank() && s.apiModel.isNotBlank()
        app.chatlens.data.BackendChoice.LOCAL -> File(s.localModelPath).isFile
        else -> false
    }

    private fun startAutoJob(start: AutoStart, s: AppSettings) {
        if (start !is AutoStart.ReadList && start !is AutoStart.Checkup && !backendReady(s)) {
            toast("Zuerst in den Einstellungen ein Modell wählen (lokal: Gemma).")
            return
        }
        if (!AgentController.startAuto(this, start, s)) {
            toast("Es läuft bereits ein Auftrag.")
            return
        }
        openWhatsApp()
    }

    private fun openWhatsApp() {
        val pkg = runCatching { ProfileStore.load(this).launchPackage }.getOrDefault("com.whatsapp")
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) toast("WhatsApp ($pkg) nicht gefunden.") else startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @Composable
    private fun Root() {
        var settings by remember { mutableStateOf(repo.load()) }
        var tab by remember { mutableIntStateOf(0) }
        var wizardOpen by remember { mutableStateOf(!settings.wizardDone && !settings.wizardSkipped) }
        val scope = rememberCoroutineScope()
        val ask by ConfirmBroker.pending.collectAsState()
        val auto by AutoState.state.collectAsState()
        var memoryCount by remember { mutableIntStateOf(0) }

        // Debug ist nur mit eingeschalteter Entwickleroption erreichbar
        androidx.compose.runtime.LaunchedEffect(tab, settings.developerMode) { if (tab == 5 && !settings.developerMode) tab = 4 }
        androidx.compose.runtime.LaunchedEffect(tabReq) { tabReq?.let { tab = it; tabReq = null; settings = repo.load() } }
        androidx.compose.runtime.LaunchedEffect(auto.running, auto.finished) {
            val r = MemoryRepo.get(this@MainActivity)
            withContext(Dispatchers.IO) {
                memoryCount = runCatching { r.list().size }.getOrDefault(0)
                val q = runCatching { r.loadQueue() }.getOrNull()
                AutoState.update { it.copy(resumable = q != null && !q.finished && !it.running) }
            }
        }
        // Gespeicherte Checkup-Auswahl laden (nur Namen) und das Speichern der Haken anbinden
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val r = MemoryRepo.get(this@MainActivity)
            val io = java.util.concurrent.Executors.newSingleThreadExecutor()
            app.chatlens.agent.CheckupState.saver = { st -> io.execute { runCatching { r.saveCheckup(st) } } }
            withContext(Dispatchers.IO) { runCatching { r.loadCheckup() }.getOrNull() }?.let { app.chatlens.agent.CheckupState.restore(it) }
            // Auto-Checkup beim Start: einmal je Prozessstart, nur nach abgeschlossenem Assistenten, bewusst eingeschaltetem Schalter (Standard aus), bestaetigtem Datenschutzhinweis, aktiver Bedienungshilfe,
            // hoechstens alle 10 Minuten (der Checkup holt WhatsApp nach vorn).
            val s0 = repo.load()
            if (app.chatlens.wizard.StartPolicy.mayAutoCheckup(s0.checkupOnStart, s0.wizardDone, s0.privacyAcknowledged) && !autoCheckupTried && !AutoState.state.value.running) {
                autoCheckupTried = true
                val ok = kotlinx.coroutines.withTimeoutOrNull(4000) { ChatAccessibilityService.connected.first { it } } == true
                val last = app.chatlens.agent.CheckupState.stored?.savedAt ?: 0L
                if (ok && System.currentTimeMillis() - last > 10 * 60_000L && !AgentController.isRunning()) {
                    AppLog.i("CHECKUP: Auto-Checkup beim Start (Schalter an, letzter Stand vor ${(System.currentTimeMillis() - last) / 60_000} Minuten).")
                    startAutoJob(AutoStart.Checkup(s0.checkupCount.coerceIn(5, 200)), s0)
                } else {
                    AppLog.i("CHECKUP: Auto-Checkup beim Start uebersprungen (Bedienungshilfe aktiv=$ok, letzter Stand vor ${(System.currentTimeMillis() - last) / 60_000} Minuten, Lauf aktiv=${AgentController.isRunning()}).")
                }
            }
        }
        // Punkt wurde im Ring oder in der Benachrichtigung entfernt: Schalter in den Einstellungen nachziehen
        val removedTick by app.chatlens.service.OverlayEvents.removed.collectAsState()
        androidx.compose.runtime.LaunchedEffect(removedTick) {
            if (removedTick > 0 && settings.overlayEnabled) settings = settings.copy(overlayEnabled = repo.load().overlayEnabled)
        }
        androidx.compose.runtime.LaunchedEffect(settings.overlayEnabled, overlayGranted) {
            if (settings.overlayEnabled && overlayGranted) OverlayService.start(this@MainActivity) else OverlayService.stop(this@MainActivity)
        }

        fun update(s: AppSettings) {
            settings = s
            repo.save(s)
        }

        val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        // 0.3.0 (U3): keine Abfrage beim allerersten Start. Erst nach dem Assistenten, mit einem Satz Begruendung, genau einmal.
        val notifMissing = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!wizardOpen && !settings.notifAsked && notifMissing) {
            AlertDialog(
                onDismissRequest = { update(settings.copy(notifAsked = true)) },
                title = { Text("Benachrichtigungen erlauben?") },
                text = { Text("Während eines Auftrags zeigt ChatLens eine Benachrichtigung mit dem Knopf zum Abbrechen. Ohne Erlaubnis funktioniert ChatLens weiter, nur ohne diese Anzeige.") },
                confirmButton = { TextButton(onClick = { update(settings.copy(notifAsked = true)); notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Erlauben") } },
                dismissButton = { TextButton(onClick = { update(settings.copy(notifAsked = true)) }) { Text("Nicht jetzt") } },
            )
        }

        val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    toast("Kopiere Modell in den App-Speicher ...")
                    val path = withContext(Dispatchers.IO) { runCatching { copyModel(uri) } }
                    path.onSuccess {
                        LiteRtLmBackend.release()
                        update(settings.copy(localModelPath = it))
                        toast("Modell importiert.")
                    }.onFailure {
                        AppLog.e("Modellimport fehlgeschlagen", it)
                        toast("Import fehlgeschlagen: ${it.message}")
                    }
                }
            }
        }
        val pickVoiceFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                val ok = runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                if (ok.isSuccess) {
                    update(settings.copy(voiceTreeUri = uri.toString()))
                    AppLog.i("STIMME: Ordner freigegeben (Lesen).")
                    toast("Ordner freigegeben.")
                } else {
                    AppLog.w("STIMME: Ordnerfreigabe nicht dauerhaft: ${ok.exceptionOrNull()?.javaClass?.simpleName}")
                    toast("Freigabe nicht möglich: ${ok.exceptionOrNull()?.message}")
                }
            }
        }
        val pickProfile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val r = runCatching {
                    val json = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                    ProfileStore.importOverride(this, json)
                }
                toast(if (r.isSuccess) "Profil übernommen." else "Profil ungültig: ${r.exceptionOrNull()?.message}")
                AppLog.i("Profilimport: " + if (r.isSuccess) "ok" else "Fehler ${r.exceptionOrNull()?.message}")
            }
        }

        if (wizardOpen) app.chatlens.ui.WizardHost(
            settings, ::update,
            app.chatlens.ui.WizardHostActions(
                openA11y = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                openOverlay = { openOverlayPermission() },
                overlayGranted = { Settings.canDrawOverlays(this@MainActivity) },
                pickModelFile = { pickModel.launch(arrayOf("*/*")) },
                pickVoiceFolder = {
                    pickVoiceFolder.launch(
                        android.provider.DocumentsContract.buildTreeDocumentUri(
                            "com.android.externalstorage.documents", "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes",
                        ),
                    )
                },
                useDownloadedGemma = {
                    val e = runCatching { app.chatlens.models.ModelCatalog.load(this@MainActivity).byId(app.chatlens.models.ModelCatalog.DEFAULT_ID) }.getOrNull()
                    val f = e?.let { app.chatlens.models.ModelStorage.locate(this@MainActivity, it) }
                    if (e != null && f != null) update(useModel(repo.load(), e, f))
                },
                // Erst hier (nach Ansage und zweitem Tipp im Assistenten) oeffnet sich WhatsApp
                startCheckup = { startAutoJob(AutoStart.Checkup(settings.checkupCount.coerceIn(5, 200)), repo.load()) },
                close = { goSetup -> wizardOpen = false; if (goSetup) tab = 0 },
            ),
        ) else Scaffold(
            containerColor = Color.Transparent, contentColor = GlassColors.Text,
            // Fuenf Ziele unten (Material 3); Debug ist eine Entwickleroption und hat keinen Platz in der Leiste (ab 0.3.0)
            bottomBar = {
                androidx.compose.material3.NavigationBar(containerColor = GlassColors.BgBottom, contentColor = GlassColors.Text) {
                    val items = listOf("Start", "Update", "Gedächtnis", "Modelle", "Einstellungen")
                    items.forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = { androidx.compose.foundation.layout.Box(Modifier.size(if (tab == i) 10.dp else 6.dp).background(if (tab == i) GlassColors.OnAccent else GlassColors.TextDim, androidx.compose.foundation.shape.CircleShape)) },
                            label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                selectedIconColor = GlassColors.OnAccent, selectedTextColor = GlassColors.Accent,
                                unselectedIconColor = GlassColors.TextDim, unselectedTextColor = GlassColors.TextDim,
                                indicatorColor = GlassColors.Accent,
                            ),
                        )
                    }
                }
            },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                when (tab) {
                    0 -> StartScreen(
                        settings = settings,
                        onSettings = ::update,
                        hasBackend = backendReady(settings),
                        overlayGranted = overlayGranted,
                        memoryCount = memoryCount,
                        onOpenA11y = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        onOpenOverlay = { openOverlayPermission() },
                        onStartCheckup = { startAutoJob(AutoStart.Checkup(settings.checkupCount.coerceIn(5, 200)), settings) },
                        onStartSetup = {
                            val st = AutoStart.Setup(
                                settings.setupCount.coerceIn(1, 200), settings.setupTarget.coerceIn(10, 2000), settings.setupIncludeGroups, settings.setupPinnedCounts,
                                selectedTitles = app.chatlens.agent.CheckupState.selectedTitles(),
                            )
                            if (settings.backend == app.chatlens.data.BackendChoice.API) confirmApiAuto = { startAutoJob(st, settings) } else startAutoJob(st, settings)
                        },
                        onResume = { retry ->
                            val st = AutoStart.Resume(retry)
                            if (settings.backend == app.chatlens.data.BackendChoice.API) confirmApiAuto = { startAutoJob(st, settings) } else startAutoJob(st, settings)
                        },
                        onCancel = { AgentController.cancel("Abbruch durch Nutzer.") },
                        onGoto = { tab = it },
                        header = {
                            app.chatlens.ui.SetupWizardSection(settings, backendReady(settings), memoryCount, onCheckup = { startAutoJob(AutoStart.Checkup(settings.checkupCount.coerceIn(5, 200)), settings) })
                            app.chatlens.ui.SelfAnalysisSection(settings, backendReady(settings), onStart = { titles, per, focus ->
                                val st = AutoStart.SelfScan(titles, per, focus)
                                if (settings.backend == app.chatlens.data.BackendChoice.API) confirmApiAuto = { startAutoJob(st, settings) } else startAutoJob(st, settings)
                            })
                            app.chatlens.ui.DotStatusSection(
                                settings, overlayGranted, ::update,
                                onOpenOverlay = { openOverlayPermission() },
                                onOpenA11y = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            )
                            app.chatlens.ui.LastRunSection(
                                settings = settings,
                                onCancel = { AgentController.cancel() },
                                onCopy = { label, text -> copy(label, text) },
                                onShareText = { text -> shareText(text) },
                                onInsert = { text -> insertLater(text, send = false, settings = settings) },
                                onSend = { text -> insertLater(text, send = true, settings = settings) },
                            )
                        },
                    )
                    1 -> AutoScreen(
                        settings = settings,
                        onSettings = ::update,
                        hasBackend = backendReady(settings),
                        onReadList = { startAutoJob(AutoStart.ReadList, settings) },
                        onStartNames = { names ->
                            val st = AutoStart.Names(names, settings.autoTarget.coerceIn(10, 2000))
                            if (settings.backend == app.chatlens.data.BackendChoice.API) confirmApiAuto = { startAutoJob(st, settings) } else startAutoJob(st, settings)
                        },
                        onResume = { retry ->
                            val st = AutoStart.Resume(retry)
                            if (settings.backend == app.chatlens.data.BackendChoice.API) confirmApiAuto = { startAutoJob(st, settings) } else startAutoJob(st, settings)
                        },
                        onCancel = { AgentController.cancel("Abbruch durch Nutzer.") },
                    )
                    2 -> MemoryScreen(settings = settings, onSettings = ::update, onShareJson = { json, name ->
                        val f = File(cacheDir, name).apply { writeText(json) }
                        shareFile(f, cache = true)
                    })
                    3 -> ModelsScreen(
                        settings = settings,
                        onOpenUrl = { openUrl(it) },
                        onStart = { e, metered -> ModelDownloadService.start(this@MainActivity, e.id, metered) },
                        onPause = { ModelDownloadService.pause(this@MainActivity) },
                        onCancel = { ModelDownloadService.cancel(this@MainActivity) },
                        onUse = { e, f -> update(useModel(settings, e, f)) },
                        onDelete = { e, f ->
                            val active = !e.multiFile && File(settings.localModelPath).name == f.name
                            if (active) LiteRtLmBackend.release()
                            val ok = if (e.multiFile) { app.chatlens.models.MultiFileDownload.deleteAll(f, e); !f.exists() } else f.delete()
                            if (active && ok) update(settings.copy(localModelPath = ""))
                            toast(if (ok) "${e.name} gelöscht." else "Löschen fehlgeschlagen.")
                        },
                        onUseFile = { f -> LiteRtLmBackend.release(); update(settings.copy(backend = app.chatlens.data.BackendChoice.LOCAL, localModelPath = f.absolutePath)); toast("Datei ${f.name} gewählt. Vision und Grenzen bitte unter Einstellungen prüfen.") },
                    )
                    4 -> SettingsScreen(
                        settings = settings,
                        onSettings = ::update,
                        onPickModel = { pickModel.launch(arrayOf("*/*")) },
                        onOpenWizard = { update(settings.copy(wizardSkipped = false)); wizardOpen = true },
                        onMessengersChanged = { ChatAccessibilityService.instance?.applyEnabledMessengers(it) },
                        onOpenDebug = { tab = 5 },
                        onOpenPrivacy = { openPrivacy() },
                        onReleaseModel = { LiteRtLmBackend.release(); toast("Modell entladen.") },
                        overlayGranted = overlayGranted,
                        onOpenOverlayPerm = { openOverlayPermission() },
                        onOpenAppSettings = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                        onStartChat = { cfg -> startRun(cfg, settings); tab = 0 },
                        onPickVoiceFolder = {
                            val hint = android.provider.DocumentsContract.buildTreeDocumentUri(
                                "com.android.externalstorage.documents", "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes",
                            )
                            pickVoiceFolder.launch(hint)
                        },
                    )
                    else -> DebugScreen(
                        settings = settings,
                        onSettings = ::update,
                        onDump = { sec -> DebugDumper.dumpAfter(this@MainActivity, sec, settings.maskDebugText) },
                        onShareFile = { shareFile(it) },
                        onCopyFile = { f -> copy("Baum", f.readText()) },
                        onPickProfile = { pickProfile.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        onResetProfile = { ProfileStore.resetOverride(this@MainActivity); toast("Standardprofil aktiv.") },
                        onShareBundledProfile = {
                            val f = File(cacheDir, "whatsapp-profile.json").apply { writeText(ProfileStore.bundledJson(this@MainActivity)) }
                            shareFile(f, cache = true)
                        },
                        onCopy = { label, text -> copy(label, text) },
                    )
                }
            }
        }

        ask?.let { q ->
            AlertDialog(
                onDismissRequest = { ConfirmBroker.answer(q.id, false) },
                title = { Text(q.title) },
                text = { Text(q.body) },
                confirmButton = { TextButton(onClick = { ConfirmBroker.answer(q.id, true) }) { Text("Ja") } },
                dismissButton = { TextButton(onClick = { ConfirmBroker.answer(q.id, false) }) { Text("Nein") } },
            )
        }
        val choice by PromptChoiceBroker.pending.collectAsState()
        val promptBook by PromptBookState.book.collectAsState()
        androidx.compose.runtime.LaunchedEffect(Unit) { PromptBookState.ensureLoaded(this@MainActivity) }
        choice?.let { c ->
            androidx.compose.ui.window.Dialog(onDismissRequest = { PromptChoiceBroker.answer(c.id, PromptChoice.Cancel) }) {
                app.chatlens.ui.GlassCard(Modifier.fillMaxWidth(), fill = GlassColors.PanelFill) {
                    androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                        app.chatlens.ui.PromptChoiceCard(
                            chatTitle = c.chatTitle, messageCount = c.messageCount, book = promptBook,
                            onChoice = { PromptChoiceBroker.answer(c.id, it) },
                            onSaveTemplate = { n, t -> PromptBookState.update(this@MainActivity) { b -> b.withSaved(n, t) } },
                            onDeleteSaved = { n -> PromptBookState.update(this@MainActivity) { b -> b.withoutSaved(n) } },
                            onDeleteRecent = { t -> PromptBookState.update(this@MainActivity) { b -> b.withoutRecent(t) } },
                        )
                    }
                }
            }
        }
        if (showPrivacy) {
            AlertDialog(
                onDismissRequest = { showPrivacy = false },
                title = { Text("Datenschutzerklärung") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(app.chatlens.ui.PrivacyPolicy.load(this@MainActivity)) } },
                confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text("Schließen") } },
            )
        }
        confirmApiAuto?.let { go ->
            AlertDialog(
                onDismissRequest = { confirmApiAuto = null },
                title = { Text("Chatinhalte an die API senden?") },
                text = { Text("Setup und Aktualisieren senden die Inhalte mehrerer Chats an den eingetragenen API-Server. Besser: lokal mit Gemma. Fortfahren nur mit Rechtsgrundlage.") },
                confirmButton = { TextButton(onClick = { confirmApiAuto = null; go() }) { Text("Senden erlauben und starten") } },
                dismissButton = { TextButton(onClick = { confirmApiAuto = null }) { Text("Abbrechen") } },
            )
        }
    }

    private var showPrivacy by mutableStateOf(false)

    /** Mit hinterlegter URL (Gradle-Property privacyUrl) im Browser, sonst als Text in der App. */
    private fun openPrivacy() {
        val url = BuildConfig.PRIVACY_URL
        if (url.isNotBlank()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else showPrivacy = true
    }

    private var confirmApiAuto by mutableStateOf<(() -> Unit)?>(null)

    private fun openOverlayPermission() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    /**
     * Traegt einen Entwurf ins WhatsApp-Eingabefeld ein (ACTION_SET_TEXT). Nach der Wartezeit muss WhatsApp mit dem Chat vorne sein.
     * Mit [send] (nur wenn der experimentelle Schalter an ist und der Nutzer den Text im Dialog bestaetigt hat) wird danach der
     * Senden-Knopf gedrueckt. Ohne [send] wird nie gesendet.
     */
    private fun insertLater(text: String, send: Boolean, settings: AppSettings) {
        val svc = ChatAccessibilityService.instance
        if (svc == null) { toast("Bedienungshilfe ist nicht aktiv."); return }
        toast("In 4 s wird eingetragen. Jetzt zu WhatsApp wechseln und den Chat offen lassen.")
        lifecycleScope.launch {
            delay(4000)
            val profile = ProfileStore.load(this@MainActivity)
            val r = withContext(Dispatchers.Default) { ReplyInserter(svc, profile).insert(text) }
            when (r) {
                InsertOutcome.OK -> {
                    if (send) {
                        delay(400)
                        val ok = withContext(Dispatchers.Default) { ExperimentalSender(svc, profile).send(text, confirmedText = text, experimentalEnabled = settings.experimentalSend) }
                        toast(if (ok) "Gesendet (experimentell)." else "Senden-Knopf nicht gefunden oder nicht erlaubt. Der Text steht im Eingabefeld.")
                    } else {
                        toast("Eingetragen. Prüfen und selbst absenden.")
                    }
                }
                InsertOutcome.NO_INPUT_FIELD -> toast("Eingabefeld nicht gefunden. Ist der Chat in WhatsApp offen?")
                else -> toast("Eintragen fehlgeschlagen. Text stattdessen kopieren.")
            }
        }
    }

    private fun startRun(cfg: ScrollRunConfig, s: AppSettings) {
        when (s.backend) {
            app.chatlens.data.BackendChoice.API ->
                if (s.apiBaseUrl.isBlank() || s.apiModel.isBlank()) {
                    toast("API: Base-URL und Modellname in den Einstellungen eintragen.")
                    return
                }
            app.chatlens.data.BackendChoice.LOCAL ->
                if (!File(s.localModelPath).isFile) {
                    toast("Lokal: Modelldatei in den Einstellungen wählen.")
                    return
                }
            else -> {}
        }
        val started = AgentController.start(this, cfg, s)
        if (!started) {
            toast("Es läuft bereits ein Auftrag.")
            return
        }
        if (cfg.chatAlreadyOpen) {
            // Kein Intent-Start: ein Neustart von WhatsApp wuerde die offene Chat-Ansicht verlieren.
            toast(
                if (s.startDelaySec > 0) "Jetzt zu WhatsApp wechseln. Lesen startet in ${s.startDelaySec} s oder per \"Jetzt lesen\" in der Benachrichtigung."
                else "In WhatsApp den Chat öffnen, dann in der Benachrichtigung \"Jetzt lesen\" tippen.",
            )
            return
        }
        // WhatsApp aus der sichtbaren Activity heraus starten (umgeht Hintergrundstart-Einschraenkungen)
        val pkg = runCatching { ProfileStore.load(this).launchPackage }.getOrDefault("com.whatsapp")
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            toast("WhatsApp ($pkg) nicht gefunden.")
        } else {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun copy(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        toast("In die Zwischenablage kopiert.")
    }

    private fun shareText(text: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                "Teilen",
            ),
        )
    }

    private fun shareFile(f: File, cache: Boolean = false) {
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Datei teilen",
            ),
        )
    }

    /** Kopiert die gewaehlte .litertlm-Datei in den App-Speicher (SAF liefert keinen direkten Dateipfad). */
    /** Setzt einen Katalogeintrag als lokales Backend. Die Beschleunigung GPU nur fuer Eintraege, die als GPU-optimiert gelten (ungetestet). */
    private fun useModel(s: AppSettings, e: ModelEntry, f: File): AppSettings {
        LiteRtLmBackend.release()
        val lim = ModelAdvisor.limitsFor(e, 8192, 12_000)
        toast("${e.name} ist aktiv." + if (lim.note.isNotBlank()) " " + lim.note else "")
        AppLog.i("Modell gewählt: ${e.id}, ${f.name}, maxTokens=${lim.maxTokens}, contextChars=${lim.contextChars}, accel=${if (e.prefersGpu) "GPU" else "CPU"}")
        return s.copy(
            backend = app.chatlens.data.BackendChoice.LOCAL,
            localModelPath = f.absolutePath,
            localVision = e.vision,
            localAccel = if (e.prefersGpu) app.chatlens.data.LocalAccel.GPU else app.chatlens.data.LocalAccel.CPU,
            // Grosse Modelle: automatische Stufe (0). Kleine Modelle behalten die feste Grenze aus ihrer Karte.
            localMaxTokens = if (lim.note.isBlank()) 0 else lim.maxTokens,
            contextCharsLocal = if (lim.note.isBlank()) 0 else lim.contextChars,
        )
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { toast("Kein Browser gefunden. Adresse: $url") }
    }

    private fun copyModel(uri: Uri): String {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "model.litertlm"
        val dir = File(filesDir, "models").apply { mkdirs() }
        val out = File(dir, name)
        contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 20) } }
        AppLog.i("Modell importiert: ${out.name}, ${out.length() / (1024 * 1024)} MB")
        return out.absolutePath
    }

    companion object {
        /** Der Auto-Checkup laeuft hoechstens einmal je Prozessstart. */
        @Volatile
        var autoCheckupTried = false
        const val EXTRA_TAB = "app.chatlens.TAB"
        const val EXTRA_TASK = "app.chatlens.TASK"
    }
}
