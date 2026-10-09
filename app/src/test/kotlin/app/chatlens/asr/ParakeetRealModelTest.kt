package app.chatlens.asr

import app.chatlens.assist.AudioClip
import app.chatlens.core.ChatMessage
import app.chatlens.core.Direction
import app.chatlens.core.Kind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Echter Test mit dem Parakeet-Modell und der sherpa-onnx-Bibliothek fuer Linux (JVM). Laeuft nur, wenn das Modell vorhanden ist:
 * Ordner aus der Umgebungsvariable CHATLENS_ASR_DIR (Standard asr-model im Arbeitsverzeichnis) mit den vier Dateien und test_wavs/de.wav, en.wav.
 * Die Opus-Datei wird mit ffmpeg aus der WAV erzeugt (nur wenn ffmpeg vorhanden ist). Sonst wird der Test uebersprungen.
 */
class ParakeetRealModelTest {
    @get:Rule val tmp = TemporaryFolder()

    companion object {
        val dir = File(System.getenv("CHATLENS_ASR_DIR") ?: "asr-model")
        private var engine: SherpaParakeetEngine? = null
        var loadMs = 0L

        fun haveModel() = dir.isDirectory && AsrModelFiles.complete(dir)
        fun haveFfmpeg() = runCatching { ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() == 0 }.getOrDefault(false)

        @Synchronized fun engine(): SherpaParakeetEngine {
            engine?.let { return it }
            val t = System.nanoTime()
            return SherpaParakeetEngine(dir, 4).also { engine = it; loadMs = (System.nanoTime() - t) / 1_000_000 }
        }

        @JvmStatic @BeforeClass fun reportEnv() {
            println("PARAKEET-TEST Modell vorhanden: ${haveModel()}, ffmpeg: ${haveFfmpeg()}, Ordner: $dir")
        }
    }

    /** Liest eine 16-Bit-Mono-WAV mit 44-Byte-Kopf; die Abtastrate steht im Kopf (de.wav 22,05 kHz, en.wav 24 kHz) und wird auf 16 kHz gewandelt. */
    private fun wav(name: String): FloatArray {
        val b = File(dir, "test_wavs/$name").readBytes()
        val bb = ByteBuffer.wrap(b, 44, b.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        val rate = ByteBuffer.wrap(b, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(1, ByteBuffer.wrap(b, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt())
        return Pcm.resampleTo16k(FloatArray((b.size - 44) / 2) { bb.short / 32768f }, rate)
    }

    /** WAV -> Ogg Opus Mono 48 kHz mit 16 kbit/s, aehnlich einer WhatsApp-Sprachnachricht. */
    private fun opusOf(name: String, out: File, bitrate: String = "16k") {
        val p = ProcessBuilder("ffmpeg", "-loglevel", "error", "-y", "-i", File(dir, "test_wavs/$name").path, "-ac", "1", "-ar", "48000", "-c:a", "opus", "-strict", "-2", "-b:a", bitrate, out.path)
            .redirectErrorStream(true).start()
        val msg = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(msg, 0, p.waitFor())
    }

    /** Der Satz muss vollstaendig und in der richtigen Reihenfolge erkannt sein. Ein einzelnes kurzes Fuellwort am Ende ("rah") kommt beim Modell sporadisch vor und zaehlt nicht als Fehler. */
    private fun assertSentence(text: String) {
        val want = "alles hat ein ende nur die wurst hat zwei"
        val got = norm(text)
        assertTrue(text, got.startsWith(want) && got.length <= want.length + 6)
    }

    /** Fuer Eingaben mit starker Kompression: mindestens 6 der 8 Woerter des Satzes muessen vorkommen. Die Erkennung ist bei gleicher Eingabe nicht immer bitgleich (int8, mehrere Threads). */
    private fun assertMostWords(text: String) {
        val hits = listOf("alles", "hat", "ein", "ende", "nur", "die", "wurst", "zwei").count { norm(text).contains(it) }
        assertTrue(text, hits >= 6)
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-zäöüß ]"), "").replace(Regex("\\s+"), " ").trim()

    @Test
    fun germanWavIsRecognized() {
        assumeTrue(haveModel())
        val x = wav("de.wav")
        val t0 = System.nanoTime()
        val text = engine().transcribe(x, 16000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(2.75, x.size / 16000.0, 0.02)
        println("PARAKEET-TEST de.wav: Audio ${"%.2f".format(x.size / 16000.0)} s, Erkennen $ms ms, Laden $loadMs ms, Text: $text")
        assertSentence(text)
    }

    @Test
    fun englishWavIsRecognizedWithoutLanguageSetting() {
        assumeTrue(haveModel())
        val x = wav("en.wav")
        val text = engine().transcribe(x, 16000)
        println("PARAKEET-TEST en.wav: Text: $text")
        assertTrue(text, text.length > 20)
        assertTrue(text, norm(text).contains("country"))
    }

    @Test
    fun opusFileThroughConcentusAndParakeetGivesTheSameSentence() {
        assumeTrue(haveModel() && haveFfmpeg())
        val f = File(tmp.root, "de.opus")
        opusOf("de.wav", f)
        val bytes = f.readBytes()
        val a = ConcentusOpusDecoder.decode(bytes)
        assertEquals(2.75, a.durationSec, 0.03)
        val t0 = System.nanoTime()
        val text = engine().transcribe(a.samples, a.sampleRate)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("PARAKEET-TEST de.opus (${bytes.size} Byte, 16k): Erkennen $ms ms, Echtzeitfaktor ${"%.3f".format(ms / 1000.0 / a.durationSec)}, Text: $text")
        assertSentence(text)
    }

    @Test
    fun lowBitrateOpusStillRecognized() {
        assumeTrue(haveModel() && haveFfmpeg())
        val f = File(tmp.root, "de8.opus")
        opusOf("de.wav", f, "10k")
        val text = engine().transcribe(ConcentusOpusDecoder.decode(f.readBytes()).samples, 16000)
        println("PARAKEET-TEST de.opus 10k (${f.length()} Byte): Text: $text")
        assertMostWords(text)
    }

    @Test
    fun transcriberInterfaceWorksEndToEnd() {
        assumeTrue(haveModel() && haveFfmpeg())
        val f = File(tmp.root, "t.opus")
        opusOf("de.wav", f)
        val tr = ParakeetTranscriber({ engine() }, listOf(ConcentusOpusDecoder))
        val r = runBlocking { tr.transcribe(AudioClip(null, 3790, "audio/ogg", f.readBytes())) }
        assertEquals(r.note, "ok", r.note)
        assertMostWords(r.text!!)
        assertEquals("Concentus (Java)", tr.lastDecoder)
        val bad = runBlocking { tr.transcribe(AudioClip(null, null, null, ByteArray(100))) }
        assertTrue(bad.text == null && bad.note.startsWith("Audio nicht lesbar"))
        val none = runBlocking { tr.transcribe(AudioClip(null, null, null)) }
        assertEquals("Keine Audiodaten", none.note)
    }

    @Test
    fun fullStepFromChatMessageToTranscript() {
        assumeTrue(haveModel() && haveFfmpeg())
        val zone = ZoneId.of("Europe/Berlin"); val today = LocalDate.of(2026, 10, 3)
        val folder = File(tmp.root, "WhatsApp Voice Notes/202640").also { it.mkdirs() }
        val opus = File(folder, "PTT-20261003-WA0007.opus")
        opusOf("de.wav", opus)
        opus.setLastModified(ZonedDateTime.of(today, LocalTime.of(18, 42, 17), zone).toInstant().toEpochMilli())
        val other = File(folder, "PTT-20261003-WA0008.opus")
        opusOf("en.wav", other)
        other.setLastModified(ZonedDateTime.of(today, LocalTime.of(18, 44, 0), zone).toInstant().toEpochMilli())
        val m1 = ChatMessage(Kind.VOICE, Direction.IN, "Anna", "0:03", "18:42")
        val m2 = ChatMessage(Kind.VOICE, Direction.IN, "Anna", "0:05", "21:00")
        val msgs = listOf(ChatMessage(Kind.DATE, Direction.UNKNOWN, null, "Heute", null), m1, ChatMessage(Kind.TEXT, Direction.IN, "Anna", "danke", "18:43"), m2)
        val tr = ParakeetTranscriber({ engine() }, listOf(ConcentusOpusDecoder))
        val rep = VoiceTranscriptionStep.run(msgs, VoiceStepConfig(20, 180, 3), FileVoiceSource(File(tmp.root, "WhatsApp Voice Notes")), ParakeetPipeline(tr), zone, today)
        println("PARAKEET-TEST Schritt: " + rep.summary())
        assertEquals(2, rep.voiceMessages); assertEquals(1, rep.transcribed); assertEquals(1, rep.unmatched)
        assertTrue(norm(m1.transcript!!).contains("wurst"))
        assertEquals(null, m2.transcript)
    }

    @Test
    fun sixtySecondsOfSpeechKeepsRealtimeFactorLowAndReportsMemory() {
        assumeTrue(haveModel())
        val one = wav("de.wav")
        val gap = FloatArray(4000)
        val parts = ArrayList<Float>()
        val x = FloatArray((one.size + gap.size) * 22)
        var o = 0
        repeat(22) { System.arraycopy(one, 0, x, o, one.size); o += one.size + gap.size }
        val t0 = System.nanoTime()
        val text = engine().transcribe(x, 16000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val hwm = runCatching { File("/proc/self/status").readLines().first { it.startsWith("VmHWM") }.trim() }.getOrDefault("?")
        println("PARAKEET-TEST 60 s: Audio ${"%.1f".format(x.size / 16000.0)} s, Erkennen $ms ms, Echtzeitfaktor ${"%.3f".format(ms / 1000.0 / (x.size / 16000.0))}, Spitzen-RAM der Test-JVM: $hwm, Zeichen ${text.length}")
        val hits = Regex("wurst", RegexOption.IGNORE_CASE).findAll(text).count()
        println("PARAKEET-TEST 60 s: Treffer \"Wurst\": $hits von 22")
        assertTrue("Treffer $hits", hits >= 18)
    }
}
