package app.chatlens.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Einfaches Logging: Ringpuffer fuer die UI plus Logdatei im App-Speicher (rotiert bei 1 MB).
 * Es werden keine Chatinhalte geloggt, nur Zaehler und Statusmeldungen.
 */
object AppLog {
    private const val TAG = "ChatLens"
    private const val MAX_LINES = 400
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)
    private val fileFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY)
    private var file: File? = null

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "chatlens.log")
        if (f.exists() && f.length() > 1_000_000) {
            f.renameTo(File(context.filesDir, "chatlens.log.1"))
        }
        file = File(context.filesDir, "chatlens.log")
    }

    fun logFile(): File? = file

    // Alle Zeilen seit Beginn des laufenden (oder letzten) Laufs, fuer das Markdown-Log. Obergrenze, damit der Speicher nicht waechst.
    private const val MAX_RUN_LINES = 30_000
    private val runLines = ArrayList<String>()

    /** Beginnt einen neuen Lauf: der Laufpuffer wird geleert. */
    @Synchronized
    fun beginRun() {
        runLines.clear()
    }

    /** Zeilen des Laufs mit vollem Zeitstempel (yyyy-MM-dd HH:mm:ss Stufe Text). Ohne Lauf: leer. */
    @Synchronized
    fun runSnapshot(): List<String> = ArrayList(runLines)

    @Synchronized
    fun i(msg: String) = add("I", msg)

    @Synchronized
    fun w(msg: String) = add("W", msg)

    @Synchronized
    fun e(msg: String, t: Throwable? = null) = add("E", msg + (t?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))

    private fun add(level: String, msg: String) {
        Log.println(if (level == "E") Log.ERROR else if (level == "W") Log.WARN else Log.INFO, TAG, msg)
        val now = Date()
        val line = "${fmt.format(now)} $level $msg"
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
        val full = "${fileFmt.format(now)} $level $msg"
        runCatching { file?.appendText(full + "\n") }
        if (runLines.size < MAX_RUN_LINES) runLines.add(full)
    }

    fun clearView() {
        _lines.value = emptyList()
    }
}
