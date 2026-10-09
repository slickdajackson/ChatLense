package app.chatlens.asr

import app.chatlens.core.ChatMessage
import app.chatlens.core.Kind
import java.time.LocalDate
import java.time.ZoneId

/** Quelle der Sprachnachrichten-Dateien (bei SAF der freigegebene Ordner, in Tests ein normaler Ordner). */
interface VoiceFileSource {
    val label: String
    fun list(): List<VoiceFile>
    fun read(f: VoiceFile): ByteArray
}

/** Dekodieren und Erkennen, getrennt, damit ein Test beides durch Attrappen ersetzen kann. [ParakeetTranscriber] implementiert das. */
interface VoicePipeline {
    fun decode(bytes: ByteArray): DecodedAudio
    fun recognize(a: DecodedAudio): String
}

class ParakeetPipeline(val t: ParakeetTranscriber) : VoicePipeline {
    override fun decode(bytes: ByteArray) = t.decode(bytes)
    override fun recognize(a: DecodedAudio) = t.recognize(a)
}

/** Zwischenspeicher fertiger Transkripte je Datei (Schluessel aus Name, Groesse, Datum), damit ein erneutes Lesen nicht neu rechnet. */
interface VoiceTextCache {
    fun get(key: String): String?
    fun put(key: String, text: String)
}

class MemoryVoiceCache : VoiceTextCache {
    val map = HashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, text: String) { map[key] = text }
}

class VoiceStepConfig(val maxPerChat: Int, val maxSeconds: Int, val toleranceMin: Int, val durationTolSec: Double = 3.0)

/** Ergebnis fuer Log und Markdown-Log. Enthaelt weder Transkripttexte noch Dateinamen. */
class VoiceReport {
    var voiceMessages = 0
    var considered = 0
    var matched = 0
    var transcribed = 0
    var fromCache = 0
    var tooLong = 0
    var failed = 0
    var unmatched = 0
    var skippedByLimit = 0
    var filesListed = 0
    var audioSec = 0.0
    var decodeMs = 0L
    var recognizeMs = 0L
    var folderError: String? = null
    val reasons = LinkedHashMap<String, Int>()
    val lines = ArrayList<String>()

    fun reason(r: String) { reasons[r] = (reasons[r] ?: 0) + 1 }

    val realtimeFactor: Double? get() = if (audioSec > 0) recognizeMs / 1000.0 / audioSec else null

    fun summary(): String = buildString {
        append("Sprachnachrichten $voiceMessages, betrachtet $considered, zugeordnet $matched, transkribiert $transcribed (davon aus Zwischenspeicher $fromCache)")
        append(", ohne Zuordnung $unmatched, zu lang $tooLong, Fehler $failed, wegen Limit uebersprungen $skippedByLimit, Dateien im Ordner $filesListed")
        realtimeFactor?.let { append(", Audio ${"%.1f".format(audioSec)} s, Dekodieren $decodeMs ms, Erkennen $recognizeMs ms, Echtzeitfaktor ${"%.2f".format(it)}") }
        folderError?.let { append(", Ordnerfehler: $it") }
    }
}

/**
 * Ablauf: Sprachnachrichten des Chats finden, den Dateien im Ordner zuordnen, dekodieren, erkennen, Text in [ChatMessage.transcript] schreiben.
 * Nur Nachrichten ohne Transkript; die neuesten zuerst, hoechstens [VoiceStepConfig.maxPerChat].
 */
object VoiceTranscriptionStep {
    fun queries(messages: List<ChatMessage>, today: LocalDate): List<Pair<Int, VoiceQuery>> {
        val out = ArrayList<Pair<Int, VoiceQuery>>()
        var date: LocalDate? = null
        var dateKnownSinceStart = false
        for ((i, m) in messages.withIndex()) {
            if (m.kind == Kind.DATE) { date = DateLabels.resolve(m.text, today); dateKnownSinceStart = true; continue }
            if (m.kind == Kind.VOICE) {
                // Vor dem ersten sichtbaren Datumstrenner ist das Datum unbekannt
                out.add(i to VoiceQuery(i, if (dateKnownSinceStart) date else null, DateLabels.time(m.time), DateLabels.durationSec(m.text)))
            }
        }
        return out
    }

    fun run(
        messages: List<ChatMessage>, cfg: VoiceStepConfig, source: VoiceFileSource, pipeline: VoicePipeline,
        zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone),
        cache: VoiceTextCache = MemoryVoiceCache(), cancelled: () -> Boolean = { false },
        log: (String) -> Unit = {},
        /** Fortschritt: (aktuelle Nachricht 1-basiert, Gesamtzahl der zu bearbeitenden Sprachnachrichten). */
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): VoiceReport {
        val rep = VoiceReport()
        val all = queries(messages, today)
        rep.voiceMessages = all.size
        val todo = all.filter { messages[it.first].transcript.isNullOrBlank() }
        if (todo.isEmpty()) return rep
        val chosen = todo.takeLast(cfg.maxPerChat)
        rep.skippedByLimit = todo.size - chosen.size
        rep.considered = chosen.size
        val files = try { source.list() } catch (e: Exception) {
            rep.folderError = "${e.javaClass.simpleName}: ${e.message}"
            rep.unmatched = chosen.size
            return rep
        }.filter { it.name.endsWith(".opus", true) || it.name.endsWith(".ogg", true) || it.name.endsWith(".oga", true) }
        rep.filesListed = files.size
        val bytesCache = HashMap<String, ByteArray?>()
        fun bytes(f: VoiceFile): ByteArray? = bytesCache.getOrPut(f.id) { runCatching { source.read(f) }.getOrNull() }
        val matches = VoiceMatcher.match(
            chosen.map { it.second }, files, { f -> bytes(f)?.let { OggOpusReader.durationSec(it) } },
            zone, cfg.toleranceMin, cfg.durationTolSec,
        ).associateBy { it.key }
        for ((n, pair) in chosen.withIndex()) {
            val (idx, q) = pair
            progress(n + 1, chosen.size)
            if (cancelled()) { rep.reason("abgebrochen"); break }
            val m = messages[idx]
            val mt = matches[q.key]
            val f = mt?.file
            if (f == null) { rep.unmatched++; rep.reason(mt?.reason ?: "nicht zugeordnet"); continue }
            rep.matched++
            val key = cacheKey(f)
            val cached = cache.get(key)
            if (cached != null) {
                m.transcript = cached; m.audioRef = f.name
                rep.transcribed++; rep.fromCache++
                continue
            }
            val data = bytes(f)
            if (data == null) { rep.failed++; rep.reason("Datei nicht lesbar"); continue }
            try {
                val t0 = System.nanoTime()
                val audio = pipeline.decode(data)
                val t1 = System.nanoTime()
                if (audio.durationSec > cfg.maxSeconds) { rep.tooLong++; rep.reason("zu lang (${audio.durationSec.toInt()} s)"); continue }
                val text = pipeline.recognize(audio)
                val t2 = System.nanoTime()
                rep.decodeMs += (t1 - t0) / 1_000_000
                rep.recognizeMs += (t2 - t1) / 1_000_000
                rep.audioSec += audio.durationSec
                if (text.isBlank()) { rep.failed++; rep.reason("kein Sprachtext erkannt"); continue }
                m.transcript = text
                m.audioRef = f.name
                cache.put(key, text)
                rep.transcribed++
                log("STIMME: ${audio.durationSec.toInt()} s Audio in ${(t2 - t1) / 1_000_000} ms erkannt (Dekodieren ${(t1 - t0) / 1_000_000} ms), ${text.length} Zeichen.")
            } catch (e: Exception) {
                rep.failed++
                rep.reason("Fehler: ${e.javaClass.simpleName}")
                log("STIMME: Fehler bei einer Sprachnachricht: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return rep
    }

    fun cacheKey(f: VoiceFile): String {
        val md = java.security.MessageDigest.getInstance("SHA-256").digest("${f.name}|${f.size}|${f.lastModified}".toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(32)
    }
}
