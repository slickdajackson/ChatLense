package app.chatlens.asr

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/** Files of the Parakeet model (INT8, sherpa-onnx) in one folder. */
object AsrModelFiles {
    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"
    val ALL = listOf(ENCODER, DECODER, JOINER, TOKENS)

    fun missing(dir: File): List<String> = ALL.filter { !File(dir, it).let { f -> f.isFile && f.length() > 0 } }
    fun complete(dir: File): Boolean = missing(dir).isEmpty()
}

/** Speech recognition on audio samples. One instance is not meant for parallel calls. */
interface AsrEngine : AutoCloseable {
    val name: String
    fun transcribe(samples: FloatArray, sampleRate: Int): String
}

/**
 * Parakeet TDT 0.6B v3 (INT8) via sherpa-onnx, model type nemo_transducer (encoder, decoder, joiner, tokens). Offline, without streaming.
 * Speech recognition picks among the 25 languages itself (no language parameter). Loading takes a while (encoder about 650 MB), so once per run.
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
