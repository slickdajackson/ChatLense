package app.chatlens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.chatlens.agent.AgentState
import app.chatlens.agent.ProfileStore
import app.chatlens.data.AppLog
import app.chatlens.data.AppSettings
import app.chatlens.data.ScrollMethod
import app.chatlens.service.DebugDumper
import java.io.File

@Composable
fun DebugScreen(
    settings: AppSettings,
    onSettings: (AppSettings) -> Unit,
    onDump: (Int) -> Unit,
    onShareFile: (File) -> Unit,
    onCopyFile: (File) -> Unit,
    onPickProfile: () -> Unit,
    onResetProfile: () -> Unit,
    onShareBundledProfile: () -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st by AgentState.state.collectAsState()
    val logs by AppLog.lines.collectAsState()
    var delaySec by remember { mutableFloatStateOf(8f) }
    var dumps by remember { mutableStateOf(DebugDumper.listDumps(ctx)) }
    LaunchedEffect(st.dumpPaths, st.message) { dumps = DebugDumper.listDumps(ctx) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Section("Accessibility-Baum exportieren (zum Kalibrieren)") {
            Text(
                "Ablauf: Knopf drücken, innerhalb der Wartezeit zu WhatsApp wechseln und die gewünschte Ansicht öffnen (Chatliste, Suche, Chat). " +
                    "Nach Ablauf wird der Baum der gerade aktiven Ansicht gespeichert. Es kommt eine Benachrichtigung.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Wartezeit: ${delaySec.toInt()} s")
            Slider(value = delaySec, onValueChange = { delaySec = it }, valueRange = 3f..20f)
            SwitchRow("Texte maskieren (Buchstaben x, Ziffern 9; Uhrzeit und Datum bleiben lesbar)", settings.maskDebugText) {
                onSettings(settings.copy(maskDebugText = it))
            }
            OutlinedButton(onClick = { onDump(delaySec.toInt()) }) { Text("Baum in ${delaySec.toInt()} s exportieren") }
            Text(st.message, style = MaterialTheme.typography.bodySmall)
        }

        Section("Wartezeiten und Navigation") {
            Text(
                "Start-Verzögerung: gilt im Modus \"Chat ist schon geöffnet\". Nach Start läuft ein Countdown, in dem man zu WhatsApp wechselt; " +
                    "ChatLens startet WhatsApp dann nicht per Intent. Bei 0 gibt es keinen Countdown, nur \"Jetzt lesen\" in der Benachrichtigung.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Start-Verzögerung: ${settings.startDelaySec} s" + if (settings.startDelaySec == 0) " (nur Jetzt lesen)" else "")
            Slider(
                value = settings.startDelaySec.toFloat(),
                onValueChange = { onSettings(settings.copy(startDelaySec = it.toInt())) },
                valueRange = 0f..30f, steps = 29,
            )
            Text(
                "Wartezeit auf Suchtreffer: maximal so lange nach dem Tippen im Suchfeld auf die Ergebnisse warten (alle 0,5 s geprüft), danach bis zu 3 Mal die Liste weiterscrollen.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Wartezeit auf Suchtreffer: ${"%.1f".format(settings.searchWaitMs / 1000f)} s")
            Slider(
                value = settings.searchWaitMs / 1000f,
                onValueChange = { onSettings(settings.copy(searchWaitMs = (Math.round(it * 2f) * 500))) },
                valueRange = 1f..15f,
            )
            Text(
                "Jeder Navigationsschritt steht im Log unten (Zeilen mit NAV: und WARTEN:), ohne Chattexte. " +
                    "Bei einem Navigationsfehler wird automatisch ein maskierter Baum im Ordner der Bäume gespeichert.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("Scrollweg, Regelung und Geschwindigkeit") {
            Text(
                "Seit 0.1.3 wird die Wischstrecke geregelt: Nach jedem Schritt misst die App aus der Verschiebung gemeinsamer Zeilen, wie weit die Liste tatsächlich gelaufen ist " +
                    "(Pixel im Protokoll), und stellt die nächste Strecke so ein, dass sich Seiten um die Ziel-Überlappung überschneiden. " +
                    "Die ersten Schritte dienen als Probe-Scrolls (sie liefern schon Nachrichten). Geht die Überlappung verloren, wird nichts geraten: " +
                    "die App scrollt zurück, bis gemeinsame Zeilen sichtbar sind, und setzt dort neu an.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    onSettings(
                        settings.copy(
                            pauseMinMs = 0, pauseMaxMs = 0, settleMaxMs = 2500, pollMs = 40, swipeDurMs = 450, holdMs = 80,
                            scrollStepPercent = 60, selfCalibrate = true, targetOverlapPercent = 35, scrollMethod = ScrollMethod.AUTO,
                        ),
                    )
                }) { Text("Standard (0.1.3)") }
                OutlinedButton(onClick = {
                    onSettings(
                        settings.copy(
                            pauseMinMs = 0, pauseMaxMs = 0, settleMaxMs = 3500, pollMs = 60, swipeDurMs = 700, holdMs = 120,
                            selfCalibrate = true, targetOverlapPercent = 45, scrollMethod = ScrollMethod.GENAU,
                        ),
                    )
                }) { Text("Genau (langsam)") }
            }
            Text("Scrollmethode", style = MaterialTheme.typography.labelLarge)
            ScrollMethod.entries.forEach { m ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.RadioButton(selected = settings.scrollMethod == m, onClick = { onSettings(settings.copy(scrollMethod = m)) })
                    Text(
                        when (m) {
                            ScrollMethod.AUTO -> "Automatisch: geregelter Swipe; nach 2 Überlappungsverlusten Genau, bei Wirkungslosigkeit ACTION_SCROLL_BACKWARD"
                            ScrollMethod.SWIPE -> "Nur geregelter Swipe (lange Geste ohne Nachschwung)"
                            ScrollMethod.ACTION -> "Nur ACTION_SCROLL_BACKWARD (eine Seite pro Schritt; zum Vergleich)"
                            ScrollMethod.GENAU -> "Genau: Kette kleiner Wischer, nach jedem abgeglichen (langsam, lückenlos)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Switch(checked = settings.selfCalibrate, onCheckedChange = { onSettings(settings.copy(selfCalibrate = it)) })
                Text("  Selbstkalibrierung (Strecke aus gemessenem Weg nachregeln)", style = MaterialTheme.typography.bodySmall)
            }
            SwitchRow("Bei unverändertem Inhalt beenden (Ende des geladenen Verlaufs: bis 6 s auf Nachladen warten, bis zu 3 Wischversuche, dann mit dem Erfassten weiterarbeiten)", settings.endOnStatic) {
                onSettings(settings.copy(endOnStatic = it))
            }
            Text("Ziel-Überlappung: ${settings.targetOverlapPercent} Prozent der Listenhöhe (30 bis 50)")
            Slider(
                value = settings.targetOverlapPercent.toFloat(),
                onValueChange = { onSettings(settings.copy(targetOverlapPercent = it.toInt().coerceIn(30, 50))) },
                valueRange = 30f..50f,
            )
            Text("Feste Schrittweite (nur ohne Selbstkalibrierung): ${settings.scrollStepPercent} Prozent der Listenhöhe")
            Slider(
                value = settings.scrollStepPercent.toFloat(),
                onValueChange = { onSettings(settings.copy(scrollStepPercent = it.toInt().coerceIn(30, 70))) },
                valueRange = 30f..70f,
            )
            Text("Swipe-Dauer: ${settings.swipeDurMs} ms (lang = kein Nachschwung)")
            Slider(
                value = settings.swipeDurMs.toFloat(),
                onValueChange = { onSettings(settings.copy(swipeDurMs = (it / 10).toInt() * 10)) },
                valueRange = 100f..1500f,
            )
            Text("Fingerhaltezeit am Ende: ${settings.holdMs} ms")
            Slider(
                value = settings.holdMs.toFloat(),
                onValueChange = { onSettings(settings.copy(holdMs = (it / 10).toInt() * 10)) },
                valueRange = 0f..300f,
            )
            Text("Maximale Wartezeit nach einem Scroll: ${"%.1f".format(settings.settleMaxMs / 1000f)} s (kürzer, sobald der Baum sich beruhigt hat)")
            Slider(
                value = settings.settleMaxMs.toFloat(),
                onValueChange = { onSettings(settings.copy(settleMaxMs = (it / 100).toInt() * 100)) },
                valueRange = 300f..8000f,
            )
            Text("Abstand der Baumabfragen beim Warten: ${settings.pollMs} ms")
            Slider(
                value = settings.pollMs.toFloat(),
                onValueChange = { onSettings(settings.copy(pollMs = (it / 5).toInt() * 5)) },
                valueRange = 10f..300f,
            )
            Text(
                "Zusatzpause nach jedem Scroll: ${settings.pauseMinMs} bis ${settings.pauseMaxMs} ms" +
                    if (settings.pauseMaxMs == 0) " (aus)" else if (settings.pauseMaxMs > settings.pauseMinMs) " (zufällig dazwischen)" else " (fest)",
            )
            Slider(
                value = settings.pauseMinMs.toFloat(),
                onValueChange = { onSettings(settings.copy(pauseMinMs = (it / 50).toInt() * 50, pauseMaxMs = maxOf(settings.pauseMaxMs, (it / 50).toInt() * 50))) },
                valueRange = 0f..3000f,
            )
            Slider(
                value = settings.pauseMaxMs.toFloat(),
                onValueChange = { onSettings(settings.copy(pauseMaxMs = maxOf((it / 50).toInt() * 50, settings.pauseMinMs))) },
                valueRange = 0f..3000f,
            )
        }

        Section("Lauf-Log als Markdown") {
            Text(
                "Das Log des letzten oder laufenden Laufs mit Kopf (Version, Datum, Gerät, Modus, Einstellungen ohne Schlüssel) als .md-Datei. " +
                    "Jeder Lauf wird nach dem Ende außerdem automatisch gespeichert (die neuesten 30).",
                style = MaterialTheme.typography.bodySmall,
            )
            RunLogButtons()
            val saved = remember(st.message, st.phase) { app.chatlens.service.RunLogStore.list(ctx) }
            Text("Automatisch gespeichert: ${saved.size}", style = MaterialTheme.typography.bodySmall)
            saved.take(5).forEach { f ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${f.name} (${f.length() / 1024} KB)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { app.chatlens.service.RunLogStore.shareFile(ctx, f) }) { Text("Teilen") }
                }
            }
        }

        Section("Gespeicherte Bäume (${dumps.size})") {
            Text(
                "Dateien mit tree-auto im Namen speichert ChatLens selbst (maskiert): einmal beim Lesen der Chatliste im Setup und im Update-Modus " +
                    "und bei Abbruch, wenn die Chatliste nicht erkannt wurde. Mit \"Teilen\" lassen sie sich exportieren. Es bleiben die neuesten 30.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Ordner: ${DebugDumper.dumpDir(ctx).absolutePath}", style = MaterialTheme.typography.bodySmall)
            dumps.take(12).forEach { f ->
                Column {
                    Text("${f.name} (${f.length() / 1024} KB)", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onShareFile(f) }) { Text("Teilen") }
                        OutlinedButton(onClick = { onCopyFile(f) }) { Text("Kopieren") }
                        OutlinedButton(onClick = { f.delete(); dumps = DebugDumper.listDumps(ctx) }) { Text("Löschen") }
                    }
                }
            }
        }

        Section("Selektor-Profil (WhatsApp)") {
            val overridden = ProfileStore.isOverridden(ctx)
            val prof = runCatching { ProfileStore.load(ctx) }.getOrNull()
            Text("Quelle: " + if (overridden) "eigene Datei im App-Speicher" else "mitgeliefert (assets/profiles/whatsapp.json)")
            Text("Kalibrierung: ${prof?.calibrationStatus ?: "unlesbar"}; bekannte IDs: ${prof?.knownIdsStatus ?: "?"}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShareBundledProfile) { Text("Standardprofil teilen") }
                OutlinedButton(onClick = onPickProfile) { Text("Profil importieren") }
            }
            OutlinedButton(onClick = onResetProfile) { Text("Standardprofil wiederherstellen") }
        }

        Section("Log (ohne Chatinhalte)") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCopy("Log", logs.joinToString("\n")) }) { Text("Kopieren") }
                OutlinedButton(onClick = { AppLog.clearView() }) { Text("Ansicht leeren") }
            }
            Text("Datei: ${AppLog.logFile()?.absolutePath ?: "-"}", style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Text(logs.takeLast(120).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            }
        }

        Section("Aufräumen") {
            OutlinedButton(onClick = {
                File(ctx.cacheDir, "chatlens").deleteRecursively()
                AppLog.i("Zwischengespeicherte Bilder gelöscht.")
            }) { Text("Erfasste Bilder löschen") }
        }
    }
}
