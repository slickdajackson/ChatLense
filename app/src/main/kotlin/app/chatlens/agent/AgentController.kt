package app.chatlens.agent

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.chatlens.core.ScrollRunConfig
import app.chatlens.data.AppLog
import app.chatlens.data.AppSettings
import app.chatlens.service.AgentForegroundService
import kotlinx.coroutines.Job

/** Entry point for start and emergency stop. Holds the parameters for the foreground service. */
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

    /** From the notification or the app: end the countdown and read now. */
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

    /** Starts the service. WhatsApp is then opened from the activity (see MainActivity). */
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

    /** Starts setup, auto mode, resume, or chat-list reading in the foreground service. */
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

    /** Emergency stop: aborts the running job immediately. */
    fun cancel(reason: String = "Notaus ausgeloest.") {
        val j = job
        if (j != null && j.isActive) {
            AppLog.w(reason)
            j.cancel()
        }
    }
}
