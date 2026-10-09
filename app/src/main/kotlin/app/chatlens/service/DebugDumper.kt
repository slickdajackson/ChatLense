package app.chatlens.service

import android.content.Context
import app.chatlens.agent.AgentState
import app.chatlens.agent.ProfileStore
import app.chatlens.core.TreeDump
import app.chatlens.data.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exports the accessibility tree of the view that is currently active (with a lead time for switching to WhatsApp). */
object DebugDumper {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun dumpDir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "dumps").apply { mkdirs() }

    fun listDumps(ctx: Context): List<File> =
        dumpDir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Waits [delaySeconds] seconds, then exports. A notification follows. */
    fun dumpAfter(ctx: Context, delaySeconds: Int, mask: Boolean) {
        val appCtx = ctx.applicationContext
        scope.launch {
            AppLog.i("Debug-Baum-Export in $delaySeconds s. Jetzt zu WhatsApp wechseln und die Ansicht oeffnen.")
            AgentState.update { it.copy(message = "Debug-Export in $delaySeconds s ...") }
            delay(delaySeconds * 1000L)
            val file = dumpNow(appCtx, mask, anyApp = true)
            AgentForegroundService.ensureChannels(appCtx)
            val msg = if (file != null) "Baum gespeichert: ${file.name}" else "Export fehlgeschlagen (kein aktives Fenster oder Dienst aus)."
            val nm = appCtx.getSystemService(android.app.NotificationManager::class.java)
            val pi = android.app.PendingIntent.getActivity(
                appCtx, 2,
                android.content.Intent(appCtx, app.chatlens.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(
                4713,
                androidx.core.app.NotificationCompat.Builder(appCtx, AgentForegroundService.CH_DONE)
                    .setSmallIcon(app.chatlens.R.drawable.ic_stat)
                    .setContentTitle("ChatLens Debug")
                    .setContentText(msg)
                    .setAutoCancel(true)
                    .setSilent(true)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                    .setContentIntent(pi)
                    .build(),
            )
            AgentState.update { it.copy(message = msg, dumpPaths = listDumps(appCtx).map { f -> f.absolutePath }) }
        }
    }

    /**
     * Writes the tree of the active window. Default: only if WhatsApp (the target app) is in front, otherwise null (and a log entry with the
     * active package). [anyApp] applies only to the manual export on the Debug tab.
     */
    fun dumpNow(ctx: Context, mask: Boolean, anyApp: Boolean = false, tag: String = "tree"): File? {
        val svc = ChatAccessibilityService.instance ?: return null
        val snap = svc.snapshot(8000, anyApp)
        if (snap == null) {
            AppLog.w("Baum-Export nicht moeglich: aktives Paket ${svc.activePackage() ?: "keines"}, Aktivitaet ${svc.lastEventClass ?: "unbekannt"}, Fenster ${svc.windowsSummary()}")
            return null
        }
        val profile = runCatching { ProfileStore.load(ctx) }.getOrNull()
        val dm = ctx.resources.displayMetrics
        val header = listOf(
            "ChatLens Accessibility-Baum",
            "Zeit: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY).format(Date()),
            "Paket des aktiven Fensters: ${svc.activePackage()}",
            "Letzte Aktivitaet (Fensterwechsel-Ereignis): ${svc.lastEventClass ?: "unbekannt"}",
            "Fenster: ${svc.windowsSummary()}",
            "Tastatur sichtbar: ${svc.imeTop() != null}",
            "Bildschirm: ${dm.widthPixels}x${dm.heightPixels} px, Dichte ${dm.density}",
            "Android SDK: ${android.os.Build.VERSION.SDK_INT}, Geraet: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "Profilstatus: ${profile?.calibrationStatus}",
        )
        val text = TreeDump.dump(snap, header, mask, profile)
        val f = File(dumpDir(ctx), "$tag-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.GERMANY).format(Date()) + ".txt")
        f.writeText(text)
        AppLog.i("Baum exportiert: ${f.name} (${snap.walk().count()} Knoten, maskiert=$mask)")
        // Clean up the oldest files (keep at most MAX_DUMPS) and refresh the Debug tab
        listDumps(ctx).drop(MAX_DUMPS).forEach { runCatching { it.delete() } }
        AgentState.update { it.copy(dumpPaths = listDumps(ctx).map { x -> x.absolutePath }) }
        return f
    }

    private const val MAX_DUMPS = 30
}
