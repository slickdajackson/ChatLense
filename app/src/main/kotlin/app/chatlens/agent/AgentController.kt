package app.chatlens.agent

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.chatlens.core.ScrollRunConfig
import app.chatlens.data.AppLog
import app.chatlens.data.AppSettings
import app.chatlens.service.AgentForegroundService
import kotlinx.coroutines.Job

/** Einstiegspunkt fuer Start und Notaus. Haelt die Parameter fuer den Foreground-Service. */
object AgentController {
    @Volatile
    internal var pendingConfig: ScrollRunConfig? = null

    @Volatile
    internal var pendingSettings: AppSettings? = null

    @Volatile
    internal var pendingAuto: AutoStart? = null

    @Volatile
    internal var job: Job? = null

    @Volatile
    private var readNow = false

    /** Aus der Benachrichtigung oder der App: Countdown beenden und jetzt lesen. */
    fun requestReadNow() {
        if (isRunning()) {
            AppLog.i("\"Jetzt lesen\" ausgeloest.")
            readNow = true
        }
    }

    internal fun consumeReadNow(): Boolean {
        val r = readNow
        readNow = false
        return r
    }

    fun isRunning(): Boolean = job?.isActive == true

    /** Startet den Service. WhatsApp wird anschliessend aus der Activity heraus geoeffnet (siehe MainActivity). */
    fun start(context: Context, cfg: ScrollRunConfig, settings: AppSettings): Boolean {
        if (isRunning()) return false
        pendingConfig = cfg
        pendingSettings = settings
        readNow = false
        AgentState.reset()
        AgentState.update { it.copy(phase = Phase.STARTING, message = "Starte ...") }
        ContextCompat.startForegroundService(context, Intent(context, AgentForegroundService::class.java).setAction(AgentForegroundService.ACTION_START))
        return true
    }

    /** Startet Setup, Auto-Modus, Fortsetzen oder Chatliste lesen im Vordergrunddienst. */
    fun startAuto(context: Context, start: AutoStart, settings: AppSettings): Boolean {
        if (isRunning()) return false
        pendingConfig = null
        pendingAuto = start
        pendingSettings = settings
        readNow = false
        AgentState.reset()
        AgentState.update { it.copy(phase = Phase.STARTING, message = "Starte ...") }
        ContextCompat.startForegroundService(context, Intent(context, AgentForegroundService::class.java).setAction(AgentForegroundService.ACTION_START))
        return true
    }

    /** Notaus: bricht den laufenden Auftrag sofort ab. */
    fun cancel(reason: String = "Notaus ausgeloest.") {
        val j = job
        if (j != null && j.isActive) {
            AppLog.w(reason)
            j.cancel()
        }
    }
}
