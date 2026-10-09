package app.chatlens.asr

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/** Dateien des Parakeet-Modells (INT8, sherpa-onnx) in einem Ordner. */
object AsrModelFiles {
    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"
    val ALL = listOf(ENCODER, DECODER, JOINER, TOKENS)

    fun missing(dir: File): List<String> = ALL.filter { !File(dir, it).let { f -> f.isFile && f.length() > 0 } }
    fun complete(dir: File): Boolean = missing(dir).isEmpty()
}

/** Spracherkennung auf Tondaten. Eine Instanz ist nicht fuer parallele Aufrufe gedacht. */
interface AsrEngine : AutoCloseable {
    val name: String
    fun transcribe(samples: FloatArray, sampleRate: Int): String
}

/**
 * Parakeet TDT 0.6B v3 (INT8) ueber sherpa-onnx, Modelltyp nemo_transducer (Encoder, Decoder, Joiner, Tokens). Offline, ohne Streaming.
 * Die Spracherkennung waehlt unter den 25 Sprachen selbst (kein Sprachparameter). Das Laden dauert (Encoder etwa 650 MB), daher einmal je Lauf.
 */
class SherpaParakeetEngine(modelDir: File, threads: Int = 4) : AsrEngine {
    override val name: String = "Parakeet TDT 0.6B v3 INT8 (sherpa-onnx)"
    private val recognizer: OfflineRecognizer

    init {
        val miss = AsrModelFiles.missing(modelDir)
        if (miss.isNotEmpty()) throw IllegalStateException("Modelldateien fehlen: ${miss.joinToString()}")
        fun p(n: String) = File(modelDir, n).absolutePath
        val cfg = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(encoder = p(AsrModelFiles.ENCODER), decoder = p(AsrModelFiles.DECODER), joiner = p(AsrModelFiles.JOINER)),
                tokens = p(AsrModelFiles.TOKENS),
                numThreads = threads.coerceIn(1, 8),
                modelType = "nemo_transducer",
                debug = false,
            ),
        )
        recognizer = OfflineRecognizer(null, cfg)
    }

    @Synchronized
    override fun transcribe(samples: FloatArray, sampleRate: Int): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    @Synchronized
    override fun close() {
        recognizer.release()
    }
}
