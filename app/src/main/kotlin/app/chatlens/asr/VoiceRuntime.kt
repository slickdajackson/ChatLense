package app.chatlens.asr

import android.content.Context
import android.net.Uri
import app.chatlens.agent.VoiceState
import app.chatlens.core.ChatMessage
import app.chatlens.data.AppLog
import app.chatlens.data.AppSettings
import app.chatlens.models.DlStatus
import app.chatlens.models.ModelCatalog
import app.chatlens.models.ModelDownloads
import app.chatlens.models.ModelStorage
import java.io.File

/** Zustand der Voraussetzungen fuer die Sprachnachrichten-Transkription, fuer Einstellungen und Log. */
class VoiceStatus(val modelDir: File?, val folderGranted: Boolean, val folderSet: Boolean, val enabled: Boolean) {
    val ready: Boolean get() = enabled && modelDir != null && folderGranted
    fun text(): String = buildString {
        append("Modell: ").append(if (modelDir != null) "geladen" else "nicht geladen (startet beim Einschalten automatisch, sonst Tab Modelle)")
        append(". Ordner: ").append(if (!folderSet) "nicht gewählt" else if (folderGranted) "freigegeben" else "Freigabe verloren, bitte neu wählen")
        append(". Schalter: ").append(if (enabled) "an" else "aus").append(".")
    }
}

object VoiceRuntime {
    const val MODEL_ID = "parakeet-tdt-0.6b-v3"

    fun cacheDir(ctx: Context) = File(ctx.cacheDir, "chatlens/voice")

    fun modelDir(ctx: Context): File? {
        val e = runCatching { ModelCatalog.load(ctx).byId(MODEL_ID) }.getOrNull() ?: return null
        // Waehrend des Downloads oder der Pruefung gilt das Modell als nicht vorhanden: Transkription erst nach erfolgreicher Pruefung
        if (ModelDownloads.get(MODEL_ID).status.let { it == DlStatus.RUNNING || it == DlStatus.VERIFYING }) return null
        return ModelStorage.locate(ctx, e)?.takeIf { AsrModelFiles.complete(it) }
    }

    fun status(ctx: Context, s: AppSettings): VoiceStatus {
        val granted = s.voiceTreeUri.isNotBlank() && ctx.contentResolver.persistedUriPermissions.any { it.uri.toString() == s.voiceTreeUri && it.isReadPermission }
        return VoiceStatus(modelDir(ctx), granted, s.voiceTreeUri.isNotBlank(), s.voiceTranscribe)
    }

    private fun transcriber(dir: File, threads: Int) = ParakeetTranscriber({ SherpaParakeetEngine(dir, threads) }, listOf(ConcentusOpusDecoder, MediaCodecAudioDecoder()))

    /**
     * Schritt im Lesevorgang: Transkribiert die Sprachnachrichten in [messages]. Gibt null zurueck, wenn der Schalter aus ist oder Voraussetzungen fehlen
     * (dann bleibt es bei "nicht transkribiert"). Das Modell wird nur fuer diesen Schritt geladen und danach freigegeben.
     */
    fun transcribeMessages(ctx: Context, s: AppSettings, messages: List<ChatMessage>, log: (String) -> Unit, cancelled: () -> Boolean, progress: (Int, Int) -> Unit = { _, _ -> }): VoiceReport? {
        val voices = messages.count { it.kind == app.chatlens.core.Kind.VOICE }
        if (!s.voiceTranscribe) {
            VoiceState.set(null, "Sprachnachrichten-Transkription ist ausgeschaltet. Im Chat gefunden: $voices.")
            return null
        }
        if (voices == 0) { VoiceState.set(null, "Keine Sprachnachrichten im gelesenen Abschnitt."); return null }
        val st = status(ctx, s)
        if (!st.ready) {
            val why = st.text()
            log("STIMME: nicht ausgefuehrt. $why Sprachnachrichten im Chat: $voices.")
            VoiceState.set(null, "Nicht ausgeführt. $why Sprachnachrichten im Chat: $voices.")
            return null
        }
        val tr = transcriber(st.modelDir!!, s.voiceThreads)
        val src = SafVoiceSource(ctx, Uri.parse(s.voiceTreeUri))
        val cache = FileVoiceCache(cacheDir(ctx))
        log("STIMME: Start, $voices Sprachnachricht(en), Limit ${s.voiceMaxPerChat}, Toleranz ${s.voiceToleranceMin} min, Modell lokal, kein Upload.")
        return try {
            val rep = VoiceTranscriptionStep.run(
                messages, VoiceStepConfig(s.voiceMaxPerChat, s.voiceMaxSeconds, s.voiceToleranceMin), src, ParakeetPipeline(tr),
                cache = cache, cancelled = cancelled, log = log, progress = progress,
            )
            log("STIMME: ${rep.summary()}. Modell laden ${tr.loadMs} ms, Dekoder ${tr.lastDecoder.ifBlank { "-" }}.")
            VoiceState.set(rep, "Modell laden: ${tr.loadMs} ms. Dekoder zuletzt: ${tr.lastDecoder.ifBlank { "keiner" }}. Ordner: Freigabe (SAF).")
            rep
        } catch (e: Throwable) {
            log("STIMME: Fehler, es bleibt bei \"nicht transkribiert\": ${e.javaClass.simpleName}: ${e.message}")
            AppLog.w("STIMME: ${e.javaClass.simpleName}: ${e.message}")
            VoiceState.set(null, "Fehler im Schritt: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { tr.close() }
        }
    }

    /**
     * Selbsttest fuer die Einstellungen: neueste Datei im Ordner lesen, dekodieren, erkennen. Zeigt Dauer, Dekoder und Zeiten, nicht den Text
     * (der Text bleibt auf dem Geraet; die Anzeige nennt nur die Laenge und die ersten drei Woerter nicht).
     */
    fun selfTest(ctx: Context, s: AppSettings): String {
        val st = status(ctx, s)
        if (st.modelDir == null) return "Modell nicht geladen."
        if (!st.folderGranted) return "Ordner nicht freigegeben."
        val src = SafVoiceSource(ctx, Uri.parse(s.voiceTreeUri))
        val files = src.list().filter { it.name.endsWith(".opus", true) || it.name.endsWith(".ogg", true) }
        if (files.isEmpty()) return "Ordner lesbar, aber keine .opus-Dateien gefunden. Falscher Ordner oder Freigabe ohne Unterordner?"
        val f = files.maxByOrNull { it.lastModified }!!
        val tr = transcriber(st.modelDir, s.voiceThreads)
        return try {
            val bytes = src.read(f)
            val a = tr.decode(bytes)
            val t0 = System.nanoTime()
            val text = tr.recognize(a)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val msg = "Dateien gefunden: ${files.size}. Neueste: ${bytes.size / 1024} KB, ${"%.1f".format(a.durationSec)} s, Dekoder ${tr.lastDecoder} (${tr.lastDecodeMs} ms). " +
                "Modell laden ${tr.loadMs} ms, Erkennen $ms ms, ${text.length} Zeichen erkannt."
            AppLog.i("STIMME: Selbsttest: $msg")
            msg
        } catch (e: Throwable) {
            val msg = "Fehler: ${e.javaClass.simpleName}: ${e.message}"
            AppLog.w("STIMME: Selbsttest $msg")
            msg
        } finally {
            runCatching { tr.close() }
        }
    }
}
