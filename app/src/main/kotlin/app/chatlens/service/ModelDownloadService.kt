package app.chatlens.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.chatlens.R
import app.chatlens.data.AppLog
import app.chatlens.models.ChecksumMismatch
import app.chatlens.models.DeviceProbe
import app.chatlens.models.DlPhase
import app.chatlens.models.DlStatus
import app.chatlens.models.DlUi
import app.chatlens.models.DownloadCancelled
import app.chatlens.models.DownloadException
import app.chatlens.models.ModelAdvisor
import app.chatlens.models.ModelCatalog
import app.chatlens.models.ModelDownloader
import app.chatlens.models.ModelDownloads
import app.chatlens.models.ModelEntry
import app.chatlens.models.ModelStorage
import app.chatlens.models.MultiFileDownload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Laedt eine Modelldatei im Vordergrund (Typ dataSync) mit Fortschritt, Pause und Fortsetzen. Immer nur ein Download.
 * Die Teildatei bleibt bei Pause liegen; "Abbrechen" loescht sie. Ohne Hugging-Face-Zugangsschluessel, nur freie Dateien.
 */
class ModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var stopMode = 0 // 0 laeuft, 1 Pause, 2 Abbrechen
    private var wake: PowerManager.WakeLock? = null
    private var currentId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> { stopMode = 1; return START_NOT_STICKY }
            ACTION_CANCEL -> { stopMode = 2; return START_NOT_STICKY }
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
                val metered = intent.getBooleanExtra(EXTRA_METERED_OK, false)
                if (job?.isActive == true) return START_NOT_STICKY
                startForegroundNow(id, "Starte ...")
                stopMode = 0
                job = scope.launch { run(id, metered) }
            }
        }
        return START_NOT_STICKY
    }

    private fun run(id: String, meteredOk: Boolean) {
        val entry: ModelEntry? = runCatching { ModelCatalog.load(this).byId(id) }.getOrNull()
        currentId = id
        if (entry == null) { fail(id, "Modell nicht im Katalog."); return }
        val part = ModelStorage.partBytes(this, entry)
        val dev = DeviceProbe.read(this)
        val check = ModelAdvisor.downloadCheck(entry, dev, part)
        if (!check.allowed) { fail(id, check.blockReason ?: "Nicht erlaubt."); return }
        if (check.needsMeteredConfirm && !meteredOk) { fail(id, "Kein WLAN erkannt. Download nicht gestartet."); return }
        if (entry.multiFile) { runMulti(entry, meteredOk); return }
        val url = entry.downloadUrl ?: run { fail(id, "Kein Download-Link."); return }
        val target = File(ModelStorage.primaryDir(this), entry.file!!)
        acquireWake()
        ModelDownloads.set(id, DlUi(DlStatus.RUNNING, part, entry.sizeBytes, "Verbinde ..."))
        AppLog.i("Modell-Download: ${entry.id} ab $part von ${entry.sizeBytes} Byte")
        var lastNotif = 0L
        try {
            val res = ModelDownloader.download(
                url, target, entry.sizeBytes, entry.sha256,
                cancel = { stopMode != 0 },
                onProgress = { p ->
                    val st = if (p.phase == DlPhase.VERIFY) DlStatus.VERIFYING else DlStatus.RUNNING
                    val msg = if (p.phase == DlPhase.VERIFY) "Prüfe SHA-256 ..." else "Lade ..."
                    ModelDownloads.set(id, DlUi(st, p.bytesDone, if (p.total > 0) p.total else entry.sizeBytes, msg))
                    val now = System.currentTimeMillis()
                    if (now - lastNotif > 1000) { lastNotif = now; notify(entry, p.bytesDone, p.total, msg) }
                },
            )
            ModelDownloads.set(id, DlUi(DlStatus.DONE, entry.sizeBytes, entry.sizeBytes, "Fertig. SHA-256 stimmt mit dem Katalog überein.", res.verified))
            AppLog.i("Modell-Download fertig: ${entry.id}, Prüfsumme ${if (res.verified) "ok" else "nicht geprüft"}")
            finishNote(entry.name + " geladen", "Unter Modelle \"Verwenden\" tippen.")
        } catch (e: DownloadCancelled) {
            if (stopMode == 2) {
                File(target.path + ".part").delete()
                ModelDownloads.set(id, DlUi(DlStatus.IDLE, 0, entry.sizeBytes, "Abgebrochen, Teildatei gelöscht."))
            } else {
                val have = ModelStorage.partBytes(this, entry)
                ModelDownloads.set(id, DlUi(DlStatus.PAUSED, have, entry.sizeBytes, "Pausiert. Fortsetzen lädt ab ${have / 1_000_000} MB weiter."))
            }
        } catch (e: ChecksumMismatch) {
            fail(id, "Prüfsumme stimmt nicht. Die Datei auf Hugging Face hat sich vermutlich geändert (Katalog veraltet) oder der Download war beschädigt. Teildatei gelöscht.")
        } catch (e: DownloadException) {
            val have = ModelStorage.partBytes(this, entry)
            ModelDownloads.set(id, DlUi(DlStatus.FAILED, have, entry.sizeBytes, (e.message ?: "Fehler") + if (have > 0) " Teildatei bleibt, Fortsetzen möglich." else ""))
            AppLog.w("Modell-Download Fehler: ${e.message}")
        } catch (e: Throwable) {
            fail(id, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            releaseWake()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** Mehrteiliges Modell (Spracherkennung): Dateien nacheinander in den Ordner <modelle>/<id>/, je Datei mit SHA-256. */
    private fun runMulti(entry: ModelEntry, meteredOk: Boolean) {
        val id = entry.id
        val dir = MultiFileDownload.dirFor(ModelStorage.primaryDir(this), entry)
        val dev = DeviceProbe.read(this)
        val check = ModelAdvisor.downloadCheck(entry, dev, MultiFileDownload.haveBytes(dir, entry))
        if (!check.allowed) { fail(id, check.blockReason ?: "Nicht erlaubt."); return }
        if (check.needsMeteredConfirm && !meteredOk) { fail(id, "Kein WLAN erkannt. Download nicht gestartet."); return }
        acquireWake()
        val have = MultiFileDownload.haveBytes(dir, entry)
        ModelDownloads.set(id, DlUi(DlStatus.RUNNING, have, entry.sizeBytes, "Verbinde ..."))
        AppLog.i("Modell-Download: ${entry.id}, ${entry.files.size} Dateien, ab $have von ${entry.sizeBytes} Byte")
        var lastNotif = 0L
        try {
            val res = MultiFileDownload.download(entry, dir, cancel = { stopMode != 0 }, onProgress = { p ->
                val st = if (p.phase == DlPhase.VERIFY) DlStatus.VERIFYING else DlStatus.RUNNING
                val msg = (if (p.phase == DlPhase.VERIFY) "Prüfe SHA-256 " else "Lade ") + "(Datei ${p.fileIndex} von ${p.fileCount}) ..."
                ModelDownloads.set(id, DlUi(st, p.bytesDone, p.bytesTotal, msg))
                val now = System.currentTimeMillis()
                if (now - lastNotif > 1000) { lastNotif = now; notify(entry, p.bytesDone, p.bytesTotal, msg) }
            })
            ModelDownloads.set(id, DlUi(DlStatus.DONE, entry.sizeBytes, entry.sizeBytes, "Fertig. Jede Datei stimmt per SHA-256 mit dem Katalog überein.", res.allVerified))
            AppLog.i("Modell-Download fertig: ${entry.id}, geladen ${res.downloaded.size}, vorhanden ${res.skipped.size}, Prüfsummen ${if (res.allVerified) "ok" else "teilweise nicht geprüft"}")
            finishNote(entry.name + " geladen", "Unter Einstellungen die Sprachnachrichten einschalten.")
        } catch (e: DownloadCancelled) {
            if (stopMode == 2) {
                entry.files.forEach { File(dir, it.name + ".part").delete() }
                ModelDownloads.set(id, DlUi(DlStatus.IDLE, 0, entry.sizeBytes, "Abgebrochen, Teildatei gelöscht."))
            } else {
                val h = MultiFileDownload.haveBytes(dir, entry)
                ModelDownloads.set(id, DlUi(DlStatus.PAUSED, h, entry.sizeBytes, "Pausiert. Fortsetzen lädt ab ${h / 1_000_000} MB weiter."))
            }
        } catch (e: ChecksumMismatch) {
            fail(id, "Prüfsumme stimmt nicht. Die Datei auf Hugging Face hat sich vermutlich geändert (Katalog veraltet) oder der Download war beschädigt. Teildatei gelöscht.")
        } catch (e: DownloadException) {
            val h = MultiFileDownload.haveBytes(dir, entry)
            ModelDownloads.set(id, DlUi(DlStatus.FAILED, h, entry.sizeBytes, (e.message ?: "Fehler") + if (h > 0) " Bereits Geladenes bleibt, Fortsetzen möglich." else ""))
            AppLog.w("Modell-Download Fehler: ${e.message}")
        } catch (e: Throwable) {
            fail(id, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            releaseWake()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fail(id: String, msg: String) {
        AppLog.w("Modell-Download: $msg")
        ModelDownloads.set(id, DlUi(DlStatus.FAILED, 0, 0, msg))
        releaseWake()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWake() {
        val pm = getSystemService(PowerManager::class.java)
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chatlens:modeldownload").apply { acquire(3 * 60 * 60 * 1000L) }
    }

    private fun releaseWake() {
        runCatching { if (wake?.isHeld == true) wake?.release() }
        wake = null
    }

    private fun channel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Modell-Download", NotificationManager.IMPORTANCE_LOW))
    }

    private fun startForegroundNow(id: String, text: String) {
        channel()
        val n = build("Modell wird geladen", text, 0, 0, true)
        val type = if (android.os.Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun build(title: String, text: String, done: Long, total: Long, indeterminate: Boolean): Notification {
        fun act(a: String, code: Int) = PendingIntent.getService(this, code, Intent(this, ModelDownloadService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "Pause", act(ACTION_PAUSE, 11)).addAction(0, "Abbrechen", act(ACTION_CANCEL, 12))
        if (total > 0) b.setProgress(1000, (done * 1000 / total).toInt(), false) else b.setProgress(0, 0, indeterminate)
        return b.build()
    }

    private fun notify(e: ModelEntry, done: Long, total: Long, msg: String) {
        val t = if (total > 0) total else e.sizeBytes
        val text = "$msg ${done / 1_000_000} von ${t / 1_000_000} MB"
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(e.name, text, done, t, false))
    }

    private fun finishNote(title: String, text: String) {
        val n = NotificationCompat.Builder(this, CH).setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
    }

    override fun onDestroy() {
        releaseWake()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.chatlens.MODEL_START"
        const val ACTION_PAUSE = "app.chatlens.MODEL_PAUSE"
        const val ACTION_CANCEL = "app.chatlens.MODEL_CANCEL"
        const val EXTRA_ID = "id"
        const val EXTRA_METERED_OK = "meteredOk"
        const val CH = "models"
        const val NOTIF_ID = 4731

        fun start(ctx: Context, id: String, meteredOk: Boolean) {
            ModelDownloads.set(id, DlUi(DlStatus.RUNNING, 0, 0, "Starte ..."))
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, ModelDownloadService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, id).putExtra(EXTRA_METERED_OK, meteredOk),
            )
        }

        fun pause(ctx: Context) { ctx.startService(Intent(ctx, ModelDownloadService::class.java).setAction(ACTION_PAUSE)) }
        fun cancel(ctx: Context) { ctx.startService(Intent(ctx, ModelDownloadService::class.java).setAction(ACTION_CANCEL)) }
    }
}
