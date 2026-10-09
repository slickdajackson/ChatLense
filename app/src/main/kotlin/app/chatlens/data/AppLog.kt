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
 * Simple logging: a ring buffer for the UI plus a log file in app storage (rotates at 1 MB).
 * No chat contents are logged, only counters and status messages.
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

    // All lines since the start of the current (or last) run, for the Markdown log. A cap so memory does not grow.
    private const val MAX_RUN_LINES = 30_000
    private val runLines = ArrayList<String>()

    /** Starts a new run: the run buffer is cleared. */
    @Synchronized
    fun beginRun() {
        runLines.clear()
    }

    /** Lines of the run with a full timestamp (yyyy-MM-dd HH:mm:ss level text). Without a run: empty. */
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
