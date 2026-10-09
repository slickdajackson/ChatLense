package app.chatlens.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.chatlens.data.AppSettings
import app.chatlens.data.BackendChoice
import app.chatlens.models.Assessment
import app.chatlens.models.DeviceInfo
import app.chatlens.models.DeviceProbe
import app.chatlens.models.DlStatus
import app.chatlens.models.Fit
import app.chatlens.models.Focus
import app.chatlens.models.ModelAdvisor
import app.chatlens.models.ModelCatalog
import app.chatlens.models.ModelDownloads
import app.chatlens.models.ModelEntry
import app.chatlens.models.ModelKind
import app.chatlens.models.ModelStatus
import app.chatlens.models.ModelStorage
import app.chatlens.models.Measured
import app.chatlens.models.MeasuredStats
import app.chatlens.models.ModelRatings
import app.chatlens.models.Rating
import app.chatlens.models.RatingArea
import app.chatlens.models.Sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

fun fmtBytes(b: Long): String =
    if (b >= 1_000_000_000L) String.format(Locale.GERMANY, "%.2f GB", b / 1e9) else String.format(Locale.GERMANY, "%d MB", b / 1_000_000L)

/** Modellkatalog: Empfehlung nach Geraet, Download mit Fortschritt, Verwenden. Quelle und Lizenz je Modell. */
@Composable
fun ModelsScreen(
    settings: AppSettings,
    onOpenUrl: (String) -> Unit,
    onStart: (ModelEntry, Boolean) -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onUse: (ModelEntry, File) -> Unit,
    onDelete: (ModelEntry, File) -> Unit,
    onUseFile: (File) -> Unit,
    /** Nur fuer Vorschau und Tests: feste Geraetewerte statt Messung. */
    deviceOverride: DeviceInfo? = null,
    initialOpen: String? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog = remember { ModelCatalog.load(ctx) }
    val dl by ModelDownloads.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var device by remember { mutableStateOf(DeviceInfo(0, 0, 0, null)) }
    var focus by remember { mutableStateOf(Focus.PROFILE) }
    var installed by remember { mutableStateOf(mapOf<String, File>()) }
    var parts by remember { mutableStateOf(mapOf<String, Long>()) }
    var unknown by remember { mutableStateOf(listOf<File>()) }
    var confirm by remember { mutableStateOf<Pair<ModelEntry, List<String>>?>(null) }
    var blocked by remember { mutableStateOf<String?>(null) }
    var deleteAsk by remember { mutableStateOf<Pair<ModelEntry, File>?>(null) }
    val verifyMsg = remember { mutableStateMapOf<String, String>() }
    var openId by remember { mutableStateOf(initialOpen) }
    var sortArea by remember { mutableStateOf<RatingArea?>(null) }

    LaunchedEffect(tick, dl) {
        withContext(Dispatchers.IO) {
            device = deviceOverride ?: DeviceProbe.read(ctx)
            installed = catalog.models.mapNotNull { e -> ModelStorage.locate(ctx, e)?.let { e.id to it } }.toMap()
            parts = catalog.models.associate { it.id to ModelStorage.partBytes(ctx, it) }
            unknown = ModelStorage.unknownFiles(ctx, catalog.models)
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(10_000); tick++ } }

    val installedIds = installed.keys
    val rec = remember(device, focus, installedIds) { if (device.totalRamMb > 0) ModelAdvisor.recommend(catalog.models, device, focus, installedIds) else null }
    val cmp = remember(device, rec, installedIds) { if (device.totalRamMb > 0 && rec?.entry?.id == ModelCatalog.DEFAULT_ID) ModelAdvisor.comparisonFor(catalog.models, device, installedIds) else null }
    val ranked = remember(device, focus, installedIds) { if (device.totalRamMb > 0) ModelAdvisor.rank(catalog.models, device, focus, installedIds).map { it.entry.id } else emptyList() }
    val ordered = catalog.models.sortedBy { e ->
        when {
            e.id == rec?.entry?.id -> 0
            e.id == cmp?.entry?.id -> 1
            e.gated || e.status == ModelStatus.PREVIEW -> 100
            else -> 10 + (ranked.indexOf(e.id).let { if (it < 0) 50 else it })
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Section("Modelle für dieses Gerät") {
            Text(
                "Lokale Modelle laufen ohne Server. Die Datei wird einmal geladen und liegt im App-Speicher. Der Standard bleibt Gemma 4 E4B; " +
                    "andere Modelle sind Alternativen für wenig Speicher oder zum Vergleichen. Größe, Prüfsumme und Lizenz stammen von Hugging Face (Stand ${catalog.checkedAt}).",
                style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
            )
            Text(
                "Dein Gerät: Arbeitsspeicher ${device.totalRamMb} MB gesamt, jetzt frei ${device.availRamMb} MB. Freier Speicher ${fmtBytes(device.freeStorageBytes)}. " +
                    "Netz: " + when (device.unmetered) { true -> "ungezählt (WLAN)"; false -> "gezählt (Mobilfunk)"; null -> "unbekannt" } + ".",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("Empfehlung nach Aufgabe", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip("Klassifikation", focus == Focus.CLASSIFY, { focus = Focus.CLASSIFY })
                GlassChip("Zusammenfassung", focus == Focus.SUMMARY, { focus = Focus.SUMMARY })
                GlassChip("Profil", focus == Focus.PROFILE, { focus = Focus.PROFILE })
            }
            OutlinedButton(onClick = { tick++ }) { Text("Gerätewerte neu lesen") }
        }
        rec?.let { r ->
            Section("Empfehlung", highlight = true) {
                Text(r.entry.name, style = MaterialTheme.typography.titleMedium, color = GlassColors.Accent)
                Text(r.reason, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
                cmp?.let { c ->
                    Text("Zum Vergleich: ${c.entry.name}", style = MaterialTheme.typography.titleSmall, color = GlassColors.Accent2Text)
                    Text(c.reason, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
                }
                Text("Die Werte sind Herstellerangaben von anderen Geräten und Schätzungen, nicht auf diesem Gerät gemessen.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
            }
        }

        Section("Alle Modelle (${catalog.models.size})") {
            Text(
                "Je Zeile: Name, Größe, Status und unsere Bewertung von 1 bis 5 Punkten (5 ist am besten; beim Speicherbedarf heißt 5 wenig Bedarf). Antippen klappt die Modellkarte aus.",
                style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim,
            )
            Text(catalog.ratingsNote, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
            Text("Sortieren", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassChip("Empfehlung", sortArea == null, { sortArea = null })
                RatingArea.entries.forEach { a -> GlassChip(a.short, sortArea == a, { sortArea = a }) }
            }
        }
        val shown = ModelRatings.order(catalog.models, ordered, sortArea)
        shown.forEach { e ->
            val f0 = installed[e.id]
            val active0 = if (e.kind == ModelKind.ASR) settings.voiceTranscribe && f0 != null
            else settings.backend == BackendChoice.LOCAL && f0 != null && File(settings.localModelPath).name == f0.name
            ModelRow(
                e, ModelRatings.rowState(active0, f0 != null, dl[e.id]?.status), isRec = e.id == rec?.entry?.id, isCmp = e.id == cmp?.entry?.id,
                expanded = openId == e.id, onToggle = { openId = if (openId == e.id) null else e.id },
            )
            if (openId == e.id) ModelCard(
                e = e, settings = settings, device = device, isRec = e.id == rec?.entry?.id, isCmp = e.id == cmp?.entry?.id,
                measured = f0?.let { MeasuredStats.load(ctx, it.name) },
                file = installed[e.id], partBytes = parts[e.id] ?: 0L, ui = dl[e.id], verifyMsg = verifyMsg[e.id],
                onOpenUrl = onOpenUrl,
                onDownload = {
                    val chk = ModelAdvisor.downloadCheck(e, device, parts[e.id] ?: 0L)
                    when {
                        !chk.allowed -> blocked = chk.blockReason
                        chk.warnings.isNotEmpty() -> confirm = e to chk.warnings
                        else -> onStart(e, false)
                    }
                },
                onPause = onPause, onCancel = onCancel,
                onUse = { f -> onUse(e, f) },
                onDelete = { f -> deleteAsk = e to f },
                onVerify = { f ->
                    scope.launch {
                        verifyMsg[e.id] = "Prüfe SHA-256 ... (bei 3 GB etwa eine Minute)"
                        if (e.multiFile) {
                            val bad = withContext(Dispatchers.IO) { runCatching { app.chatlens.models.MultiFileDownload.verifyAll(f, e) } }
                            verifyMsg[e.id] = bad.fold(
                                { if (it.isEmpty()) "Alle ${e.files.size} Dateien stimmen per SHA-256 mit dem Katalog überein." else "Prüfsumme weicht ab bei: ${it.joinToString()}. Modell löschen und neu laden." },
                                { "Prüfung fehlgeschlagen: ${it.message}" },
                            )
                            return@launch
                        }
                        val r = withContext(Dispatchers.IO) { runCatching { Sha256.of(f) } }
                        verifyMsg[e.id] = r.fold(
                            { if (it.equals(e.sha256, ignoreCase = true)) "Prüfsumme stimmt mit dem Katalog überein." else "Prüfsumme weicht ab. Datei ist anders als im Katalog (anderer Stand, beschädigt oder eigene Variante)." },
                            { "Prüfung fehlgeschlagen: ${it.message}" },
                        )
                    }
                },
            )
        }

        if (unknown.isNotEmpty()) {
            Section("Weitere Dateien im App-Ordner") {
                Text("Diese .litertlm-Dateien gehören zu keinem Katalogeintrag (zum Beispiel per adb abgelegt). Sie bleiben wählbar.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
                unknown.forEach { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${f.name} (${fmtBytes(f.length())})", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { onUseFile(f) }) { Text("Verwenden") }
                    }
                }
            }
        }
    }

    confirm?.let { (e, warns) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${e.name} laden (${fmtBytes(e.sizeBytes)})?") },
            text = { Text(warns.joinToString("\n\n") + "\n\nBesser im WLAN laden und das Gerät am Strom lassen.") },
            confirmButton = { TextButton(onClick = { confirm = null; onStart(e, true) }) { Text("Trotzdem laden") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Abbrechen") } },
        )
    }
    blocked?.let { msg ->
        AlertDialog(
            onDismissRequest = { blocked = null },
            title = { Text("Download nicht möglich") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { blocked = null }) { Text("OK") } },
        )
    }
    deleteAsk?.let { (e, f) ->
        AlertDialog(
            onDismissRequest = { deleteAsk = null },
            title = { Text("${e.name} löschen?") },
            text = { Text("Die Datei ${f.name} (${fmtBytes(f.length())}) wird vom Gerät entfernt. Erneutes Laden ist möglich.") },
            confirmButton = { TextButton(onClick = { onDelete(e, f); deleteAsk = null; tick++ }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { deleteAsk = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun Bar(label: String, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
        repeat(3) { i ->
            Box(
                Modifier.width(26.dp).height(7.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (i < value) GlassColors.Accent else Color(0x33FFFFFF)),
            )
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun ModelCard(
    e: ModelEntry, settings: AppSettings, device: DeviceInfo, isRec: Boolean, isCmp: Boolean, measured: Measured?,
    file: File?, partBytes: Long, ui: app.chatlens.models.DlUi?, verifyMsg: String?,
    onOpenUrl: (String) -> Unit, onDownload: () -> Unit, onPause: () -> Unit, onCancel: () -> Unit,
    onUse: (File) -> Unit, onDelete: (File) -> Unit, onVerify: (File) -> Unit,
) {
    val active = settings.backend == BackendChoice.LOCAL && file != null && File(settings.localModelPath).name == file.name
    val a: Assessment? = if (device.totalRamMb > 0 && e.kind == ModelKind.LLM) ModelAdvisor.assess(e, device, e.prefersGpu, file != null, partBytes) else null
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).animateContentSize(), highlight = isRec) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (e.status == ModelStatus.STANDARD) Badge("Standard", GlassColors.Accent)
                if (isRec) Badge("Empfohlen", GlassColors.Ok)
                if (isCmp) Badge("Zum Vergleich", GlassColors.Accent2Text)
                if (e.gated) Badge("Gated: Token nötig", GlassColors.Warn)
                if (e.status == ModelStatus.EXPERIMENTAL) Badge("Experimentell", GlassColors.Warn)
                if (e.status == ModelStatus.PREVIEW) Badge("Vorschau, noch ohne Funktion", GlassColors.Accent2Text)
                if (file != null) Badge("Vorhanden", GlassColors.Ok)
                if (active) Badge("Aktiv", GlassColors.Accent)
            }
            Text(
                "Format: ${e.format.label}. Größe: ${fmtBytes(e.sizeBytes)}. Lizenz: ${e.license}." +
                    (if (e.contextTokens > 0) " Kontext: ${e.contextTokens} Token." else if (e.kind == ModelKind.LLM) " Kontext: auf der Karte nicht angegeben." else "") +
                    (if (e.kind == ModelKind.LLM) (if (e.vision) " Kann Bilder." else " Nur Text.") else ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            val ramLine = buildString {
                val cpu = e.ramCpuMb; val gpu = e.ramGpuMb
                append("RAM-Bedarf: ")
                append(if (cpu != null) "CPU etwa $cpu MB" else "CPU unbekannt")
                append(", ")
                append(if (gpu != null) "GPU etwa $gpu MB" else "GPU unbekannt")
                append(". Quelle: ${e.ramSource}.")
            }
            Text(ramLine, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
            RatingDetails(e, measured)
            if (a != null) {
                val (txt, col) = when (a.fit) {
                    Fit.GUT -> "Passt gut zu deinem Gerät" to GlassColors.Ok
                    Fit.KNAPP -> "Passt knapp" to GlassColors.Warn
                    Fit.ZU_WENIG -> "Zu wenig Arbeitsspeicher" to GlassColors.Bad
                }
                Text(txt, style = MaterialTheme.typography.bodyMedium, color = col)
                a.reasons.drop(1).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim) }
            }
            if (e.german.isNotBlank()) Text(e.german, style = MaterialTheme.typography.bodySmall, color = GlassColors.Warn)
            Text(e.notes, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)

            if (file != null && e.multiFile) {
                Text("Ordner: ${file.name}, ${e.files.size} Dateien, ${fmtBytes(e.sizeBytes)}. Größen stimmen.", style = MaterialTheme.typography.bodySmall, color = GlassColors.Ok)
            } else if (file != null) {
                val ok = file.length() == e.sizeBytes
                Text(
                    "Datei: ${file.name}, ${fmtBytes(file.length())}" + if (ok) ", Größe stimmt." else ", Größe weicht vom Katalog ab (${fmtBytes(e.sizeBytes)}).",
                    style = MaterialTheme.typography.bodySmall, color = if (ok) GlassColors.Ok else GlassColors.Warn,
                )
            } else if (partBytes > 0 && ui?.status != DlStatus.RUNNING) {
                Text("Teilweise geladen: ${fmtBytes(partBytes)} von ${fmtBytes(e.sizeBytes)}.", style = MaterialTheme.typography.bodySmall, color = GlassColors.Warn)
            }
            val running = ui?.status == DlStatus.RUNNING || ui?.status == DlStatus.VERIFYING
            if (ui != null && ui.status != DlStatus.IDLE && file == null) {
                if (running && ui.total > 0) LinearProgressIndicator(progress = { (ui.done.toFloat() / ui.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                Text(
                    ui.message + if (running && ui.total > 0) " ${ui.done / 1_000_000} von ${ui.total / 1_000_000} MB" else "",
                    style = MaterialTheme.typography.bodySmall, color = if (ui.status == DlStatus.FAILED) GlassColors.Bad else GlassColors.TextDim,
                )
            }
            verifyMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GlassColors.Accent) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    file != null && e.kind == ModelKind.LLM -> {
                        Button(enabled = !active, onClick = { onUse(file) }) { Text(if (active) "Aktiv" else "Verwenden") }
                        if (e.sha256.length == 64) OutlinedButton(onClick = { onVerify(file) }) { Text("Prüfsumme prüfen") }
                        OutlinedButton(onClick = { onDelete(file) }) { Text("Löschen") }
                    }
                    file != null && e.kind == ModelKind.ASR -> {
                        OutlinedButton(onClick = { onVerify(file) }) { Text("Prüfsummen prüfen") }
                        OutlinedButton(onClick = { onDelete(file) }) { Text("Löschen") }
                        Text("Einschalten unter Einstellungen, Sprachnachrichten.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
                    }
                    running -> {
                        OutlinedButton(onClick = onPause) { Text("Pause") }
                        OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
                    }
                    e.canDownload -> {
                        val resume = partBytes > 0
                        Button(onClick = onDownload) { Text(if (resume) "Fortsetzen" else "Herunterladen (${fmtBytes(e.sizeBytes)})") }
                        if (resume) OutlinedButton(onClick = onCancel) { Text("Teildatei löschen") }
                    }
                }
                OutlinedButton(onClick = { onOpenUrl(e.pageUrl) }) { Text("Quelle öffnen") }
            }
            if (e.links.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    e.links.forEach { l -> OutlinedButton(onClick = { onOpenUrl(l.url) }) { Text(l.label) } }
                }
            }
            if (e.gated) Text("Gated: ChatLens lädt diese Datei nicht. Im Browser anmelden, Lizenz bestätigen, laden und dann unter Einstellungen importieren.", style = MaterialTheme.typography.bodySmall, color = GlassColors.Warn)
        }
    }
}

/** Punktreihe 1 bis 5: gefuellt = Akzent, leer = Umriss in Textfarbe (beides mindestens 3:1 auf der Karte). */
@Composable
fun Dots(score: Int, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics { contentDescription = "$label $score von ${ModelRatings.MAX}" },
        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(ModelRatings.MAX) { i ->
            if (i < score) Box(Modifier.size(8.dp).background(GlassColors.Accent, CircleShape))
            else Box(Modifier.size(8.dp).border(1.dp, GlassColors.TextDim, CircleShape))
        }
    }
}

/** Ultrakompakte Zeile: Name, Groesse, Status und fuenf Punktreihen. Antippen klappt die Karte auf. */
@Composable
fun ModelRow(e: ModelEntry, state: ModelRatings.RowState, isRec: Boolean, isCmp: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    val statusColor = when (state) {
        ModelRatings.RowState.ACTIVE -> GlassColors.Accent
        ModelRatings.RowState.LOADED -> GlassColors.Ok
        ModelRatings.RowState.LOADING -> GlassColors.Warn
        ModelRatings.RowState.NOT_LOADED -> GlassColors.TextDim
    }
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), highlight = isRec) {
        Column(
            Modifier.clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { contentDescription = e.name + ", " + state.label + (if (expanded) ", ausgeklappt" else ", zum Ausklappen tippen") },
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    e.name + (if (isRec) "  (Empfohlen)" else if (isCmp) "  (Vergleich)" else ""),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    color = if (isRec) GlassColors.Accent else GlassColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(fmtBytes(e.sizeBytes), style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
                Text(state.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = statusColor)
                Text(if (expanded) "Zu" else "Auf", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                RatingArea.entries.forEach { a ->
                    val r = e.ratings[a]
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(a.short, style = MaterialTheme.typography.labelSmall, color = GlassColors.TextDim, maxLines = 1)
                        if (r != null) Dots(r.score, a.label) else Text("n. z.", style = MaterialTheme.typography.labelSmall, color = GlassColors.TextDim)
                    }
                }
            }
        }
    }
}

/** Bewertung mit Begruendung je Bereich, klar als Einschaetzung gekennzeichnet; gemessene Werte stehen getrennt darunter. */
@Composable
private fun RatingDetails(e: ModelEntry, measured: Measured?) {
    Text("Unsere Bewertung (Einschätzung, nicht gemessen)", style = MaterialTheme.typography.labelLarge)
    RatingArea.entries.forEach { a ->
        val r: Rating = e.ratings[a] ?: return@forEach
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(112.dp)) {
                Text(a.label, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
                Dots(r.score, a.label)
            }
            Text(r.why, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = GlassColors.Text)
        }
    }
    Text("Gemessen auf diesem Gerät", style = MaterialTheme.typography.labelLarge)
    Text(
        measured?.let { MeasuredStats.describe(it) } ?: "Noch keine Messwerte. Sie entstehen aus eigenen Läufen mit diesem Modell und stehen dann hier, getrennt von der Einschätzung.",
        style = MaterialTheme.typography.bodySmall, color = if (measured != null) GlassColors.Ok else GlassColors.TextDim,
    )
}
