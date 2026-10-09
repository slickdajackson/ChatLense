package app.chatlens.service

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.chatlens.core.RunLogMarkdown
import app.chatlens.data.AppLog
import app.chatlens.data.AppSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Markdown-Log je Lauf: Kopf mit Version, Datum, Geraet, Modus und Einstellungen (ohne Schluessel), Ergebnis und alle Logzeilen des Laufs.
 * Jeder Lauf wird nach dem Ende automatisch unter runlogs/ gespeichert (die neuesten 30 bleiben). Teilen und Ablegen in Downloads
 * gehen jederzeit, auch waehrend eines Laufs.
 */
object RunLogStore {
    private const val KEEP = 30

    @Volatile
    private var mode: String = "(noch kein Lauf)"

    @Volatile
    private var startedAt: Long = 0L

    @Volatile
    private var outcome: String = ""

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "runlogs").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".md") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun begin(modeText: String, s: AppSettings) {
        AppLog.beginRun()
        mode = modeText
        startedAt = System.currentTimeMillis()
        outcome = ""
        AppLog.i("LAUF-START: $modeText")
        AppLog.i("EINSTELLUNGEN: " + settingsRows(s).joinToString("; ") { "${it.first}=${it.second}" })
    }

    /** Beendet den Lauf, merkt das Ergebnis und speichert das Markdown-Log automatisch. */
    fun finish(ctx: Context, s: AppSettings, outcomeText: String): File? {
        outcome = outcomeText
        AppLog.i("LAUF-ENDE: $outcomeText")
        return runCatching { write(ctx, s, "lauf") }.getOrNull()
    }

    fun headerRows(ctx: Context): List<Pair<String, String>> {
        val pi = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) pi?.longVersionCode else pi?.versionCode?.toLong()
        return listOf(
            "Version" to "${pi?.versionName ?: "?"} (versionCode ${code ?: "?"})",
            "Erstellt" to fmt.format(Date()),
            "Lauf gestartet" to if (startedAt > 0) fmt.format(Date(startedAt)) else "kein Lauf in dieser Sitzung",
            "Modus" to mode,
            "Geraet" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.DISPLAY}",
        )
    }

    /** Einstellungen als Tabelle. Der API-Schluessel fehlt absichtlich; von Pfaden steht nur der Dateiname, von der URL nur der Host. */
    fun settingsRows(s: AppSettings): List<Pair<String, String>> = listOf(
        "Backend" to s.backend.name,
        "API-Host" to (runCatching { Uri.parse(s.apiBaseUrl).host }.getOrNull() ?: "?"),
        "API-Modell" to s.apiModel.ifBlank { "-" },
        "Lokales Modell (Datei)" to (s.localModelPath.substringAfterLast('/').ifBlank { "-" }),
        "Beschleunigung" to s.localAccel.name,
        "Max. Token lokal (0 = automatisch)" to s.localMaxTokens.toString(),
        "Kontextstufe tatsaechlich genutzt" to app.chatlens.llm.LiteRtLmBackend.contextInfo(),
        "Kontextlimit lokal (Zeichen, 0 = automatisch)" to s.contextCharsLocal.toString(),
        "Bilder erfassen / OCR / max." to "${s.captureImages} / ${s.ocrImages} / ${s.maxImages}",
        "Scrollmethode" to s.scrollMethod.name,
        "Selbstkalibrierung" to s.selfCalibrate.toString(),
        "Schrittweite % / Ueberlappung %" to "${s.scrollStepPercent} / ${s.targetOverlapPercent}",
        "Wischdauer / Halten (ms)" to "${s.swipeDurMs} / ${s.holdMs}",
        "Warten max / Poll (ms)" to "${s.settleMaxMs} / ${s.pollMs}",
        "Zusatzpause (ms)" to "${s.pauseMinMs} bis ${s.pauseMaxMs}",
        "Scroll-Obergrenze" to s.maxScrollCap.toString(),
        "Ende bei unveraendertem Inhalt" to s.endOnStatic.toString(),
        "Setup: Anzahl / Ziel / Gruppen / Angeheftete" to "${s.setupCount} / ${s.setupTarget} / ${s.setupIncludeGroups} / ${s.setupPinnedCounts}",
        "Checkup beim Start / Anzahl" to "${s.checkupOnStart} / ${s.checkupCount}",
        "Sprachnachrichten transkribieren / Ordner gewaehlt" to "${s.voiceTranscribe} / ${s.voiceTreeUri.isNotBlank()}",
        "Sprachnachrichten Limit je Chat / max. Sekunden / Toleranz Minuten / Threads" to "${s.voiceMaxPerChat} / ${s.voiceMaxSeconds} / ${s.voiceToleranceMin} / ${s.voiceThreads}",
        "Auto: Ziel" to s.autoTarget.toString(),
        "Start-Verzoegerung (s)" to s.startDelaySec.toString(),
        "Suchwartezeit (ms)" to s.searchWaitMs.toString(),
        "Debug-Texte maskiert" to s.maskDebugText.toString(),
    )

    fun markdown(ctx: Context, s: AppSettings): String {
        val lines = AppLog.runSnapshot().ifEmpty { AppLog.lines.value.map { "(Ansicht) $it" } }
        val checkup = app.chatlens.agent.CheckupState.summary()
        val voice = app.chatlens.agent.VoiceState.markdown()
        val sections = buildList {
            if (checkup.isNotEmpty()) add("Checkup" to (checkup + ". Namen stehen nur gekuerzt im Log (zwei Zeichen und Laenge)."))
            if (voice.isNotEmpty()) add("Sprachnachrichten" to voice)
        }
        return RunLogMarkdown.build(headerRows(ctx), settingsRows(s), outcome, lines, sections)
    }

    private fun write(ctx: Context, s: AppSettings, prefix: String): File {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY).format(Date())
        val f = File(dir(ctx), "$prefix-$ts.md")
        f.writeText(markdown(ctx, s))
        list(ctx).drop(KEEP).forEach { runCatching { it.delete() } }
        return f
    }

    fun share(ctx: Context, s: AppSettings) {
        val f = write(ctx, s, "log")
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, f.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Log teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareFile(ctx: Context, f: File) {
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, f.name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Log teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Legt das Log als .md in Downloads/ChatLens ab (MediaStore, ab Android 10; davor App-Ordner). Liefert eine Beschreibung des Ortes oder null. */
    fun saveToDownloads(ctx: Context, s: AppSettings): String? {
        val md = markdown(ctx, s)
        val name = "ChatLens-Log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY).format(Date()) + ".md"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ChatLens")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(md.toByteArray(Charsets.UTF_8)) } ?: return null
                "Download/ChatLens/$name"
            } else {
                val f = File(dir(ctx), name)
                f.writeText(md)
                f.absolutePath
            }
        } catch (e: Exception) {
            AppLog.w("Log konnte nicht in Downloads gespeichert werden: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
