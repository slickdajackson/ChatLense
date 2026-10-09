package app.chatlens.models

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Ablageorte und Erkennung vorhandener Modelldateien. Die App-Ordner sind dieselben, die die Einstellungen schon durchsuchen. */
object ModelStorage {
    fun primaryDir(ctx: Context): File {
        val ext = ctx.getExternalFilesDir(null)
        val d = if (ext != null) File(ext, "models") else File(ctx.filesDir, "models")
        d.mkdirs()
        return d
    }

    fun allDirs(ctx: Context): List<File> = listOfNotNull(ctx.getExternalFilesDir(null)?.let { File(it, "models") }, File(ctx.filesDir, "models"))

    /** Datei zum Katalogeintrag in einem der App-Ordner (nach Dateiname), egal wie sie dorthin kam (Download, adb, Import). */
    fun locate(ctx: Context, e: ModelEntry): File? {
        if (e.multiFile) return allDirs(ctx).map { MultiFileDownload.dirFor(it, e) }.firstOrNull { MultiFileDownload.complete(it, e) }
        val name = e.file ?: return null
        return allDirs(ctx).map { File(it, name) }.firstOrNull { it.isFile }
    }

    fun partBytes(ctx: Context, e: ModelEntry): Long {
        if (e.multiFile) return allDirs(ctx).maxOfOrNull { MultiFileDownload.haveBytes(MultiFileDownload.dirFor(it, e), e) } ?: 0L
        val name = e.file ?: return 0L
        return allDirs(ctx).map { File(it, "$name.part") }.firstOrNull { it.isFile }?.length() ?: 0L
    }

    /** Alle .litertlm-Dateien in den App-Ordnern, die zu keinem Katalogeintrag gehoeren (eigene Dateien). */
    fun unknownFiles(ctx: Context, catalog: List<ModelEntry>): List<File> {
        val known = catalog.mapNotNull { it.file }.toSet()
        return allDirs(ctx).flatMap { d -> d.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") }?.toList().orEmpty() }
            .filter { it.name !in known }
    }
}

object DeviceProbe {
    fun read(ctx: Context): DeviceInfo {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val free = runCatching { StatFs(ModelStorage.primaryDir(ctx).path).availableBytes }.getOrDefault(0L)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val unmetered = runCatching {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }.getOrNull()
        return DeviceInfo(mi.totalMem / 1_000_000L, mi.availMem / 1_000_000L, free, unmetered)
    }
}

enum class DlStatus { IDLE, RUNNING, VERIFYING, PAUSED, FAILED, DONE }

data class DlUi(val status: DlStatus = DlStatus.IDLE, val done: Long = 0, val total: Long = 0, val message: String = "", val verified: Boolean = false)

object ModelDownloads {
    private val _state = MutableStateFlow<Map<String, DlUi>>(emptyMap())
    val state: StateFlow<Map<String, DlUi>> = _state

    @Synchronized
    fun set(id: String, ui: DlUi) {
        _state.value = _state.value + (id to ui)
    }

    fun get(id: String): DlUi = _state.value[id] ?: DlUi()

    val anyRunning: Boolean get() = _state.value.values.any { it.status == DlStatus.RUNNING || it.status == DlStatus.VERIFYING }
}
