package app.chatlens.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.chatlens.MainActivity
import app.chatlens.R
import app.chatlens.agent.AgentController
import app.chatlens.agent.AgentException
import app.chatlens.agent.AgentState
import app.chatlens.agent.RunProgress
import app.chatlens.agent.AutoRunner
import app.chatlens.agent.AutoStart
import app.chatlens.agent.AutoState
import app.chatlens.agent.ChatRunner
import app.chatlens.agent.ConfirmBroker
import app.chatlens.agent.Phase
import app.chatlens.data.AppLog
import app.chatlens.data.SettingsRepo
import app.chatlens.llm.LlmException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Haelt den Lauf im Vordergrund (sichtbare Benachrichtigung mit Notaus-Knopf). */
class AgentForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var statusJob: Job? = null
    private var confirmJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.init(this)
        when (intent?.action) {
            ACTION_STOP -> {
                AgentController.cancel()
                return START_NOT_STICKY
            }
            ACTION_READ_NOW -> {
                AgentController.requestReadNow()
                return START_NOT_STICKY
            }
            ACTION_CONFIRM_YES -> {
                ConfirmBroker.answerCurrent(true)
                return START_NOT_STICKY
            }
            ACTION_CONFIRM_NO -> {
                ConfirmBroker.answerCurrent(false)
                return START_NOT_STICKY
            }
            ACTION_START -> startRun()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun describeMode(auto: AutoStart?, cfg: app.chatlens.core.ScrollRunConfig?): String = when {
        auto is AutoStart.Checkup -> "Checkup: bis zu ${auto.limit} Chats aus der Chatliste lesen (kein Chat wird geoeffnet)"
        auto is AutoStart.Setup && auto.selectedTitles != null -> "Setup: ${auto.selectedTitles.size} im Checkup gewaehlte Chats, Ziel ${auto.target} Nachrichten je Chat"
        auto is AutoStart.Setup -> "Setup: neueste ${auto.count} Chats, Ziel ${auto.target} Nachrichten je Chat, Gruppen ${if (auto.includeGroups) "ja" else "nein"}, Angeheftete ${if (auto.pinnedCounts) "zaehlen" else "ausgeschlossen"}"
        auto is AutoStart.Names -> "Aktualisieren: ${auto.titles.size} Namen, Ziel ${auto.target} Nachrichten"
        auto is AutoStart.Resume -> "Fortsetzen der Warteschlange (Fehlgeschlagene erneut: ${if (auto.retryFailed) "ja" else "nein"})"
        auto == AutoStart.ReadList -> "Chatliste lesen"
        cfg != null -> "Einzelchat: Aufgabe ${cfg.task}, Stopp ${cfg.stopMode}, Chat schon geoeffnet ${cfg.chatAlreadyOpen}, aus Liste ${cfg.fromList}"
        else -> "unbekannt"
    }

    private fun startRun() {
        val cfg = AgentController.pendingConfig
        val auto = AgentController.pendingAuto
        val settings = AgentController.pendingSettings
        if ((cfg == null && auto == null) || settings == null) {
            stopSelf()
            return
        }
        createChannels()
        val n = buildNotification("Starte ...", if (cfg?.chatAlreadyOpen == true) Phase.WAITING else Phase.STARTING)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)

        val repo = SettingsRepo(this)
        val runner = ChatRunner(applicationContext, repo)
        RunLogStore.begin(describeMode(auto, cfg), settings)
        val job = scope.launch {
            try {
                if (auto != null) {
                    AgentController.pendingAuto = null
                    AutoRunner(applicationContext, repo).run(auto, settings)
                } else {
                    runner.run(cfg!!, settings)
                }
            } catch (e: CancellationException) {
                AgentState.update { it.copy(phase = Phase.CANCELLED, message = "Abgebrochen (Notaus).", error = "") }
                AppLog.w("Lauf abgebrochen.")
            } catch (e: AgentException) {
                AgentState.update { it.copy(phase = Phase.FAILED, message = "Fehler.", error = e.message.orEmpty()) }
                AppLog.e("Lauf fehlgeschlagen: ${e.message}")
            } catch (e: LlmException) {
                AgentState.update { it.copy(phase = Phase.FAILED, message = "LLM-Fehler.", error = e.message.orEmpty()) }
                AppLog.e("LLM-Fehler: ${e.message}")
            } catch (e: Throwable) {
                AgentState.update { it.copy(phase = Phase.FAILED, message = "Unerwarteter Fehler.", error = app.chatlens.agent.ErrorText.friendly(e)) }
                AppLog.e("Unerwarteter Fehler", e)
            } finally {
                AgentController.job = null
                val st = AgentState.state.value
                RunLogStore.finish(applicationContext, settings, "Phase ${st.phase}: ${st.message}" + if (st.error.isNotEmpty()) " Fehler: ${st.error}" else "")
                postDone(st.phase == Phase.DONE, st.message + if (st.error.isNotEmpty()) " " + st.error else "")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        AgentController.job = job
        confirmJob?.cancel()
        confirmJob = scope.launch {
            ConfirmBroker.pending.collect { req ->
                val nm = getSystemService(NotificationManager::class.java)
                if (req == null) {
                    nm.cancel(NOTIF_ID + 2)
                } else {
                    val yes = PendingIntent.getService(this@AgentForegroundService, 3, Intent(this@AgentForegroundService, AgentForegroundService::class.java).setAction(ACTION_CONFIRM_YES), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    val no = PendingIntent.getService(this@AgentForegroundService, 4, Intent(this@AgentForegroundService, AgentForegroundService::class.java).setAction(ACTION_CONFIRM_NO), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    val n2 = NotificationCompat.Builder(this@AgentForegroundService, CH_ARMED)
                        .setSmallIcon(R.drawable.ic_stat)
                        .setContentTitle(req.title)
                        .setContentText(req.body.replace('\n', ' '))
                        .setStyle(NotificationCompat.BigTextStyle().bigText(req.body))
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setSilent(true)
                        .setOnlyAlertOnce(true)
                        .setOngoing(true)
                        .addAction(0, "Ja", yes)
                        .addAction(0, "Nein", no)
                        .build()
                    nm.notify(NOTIF_ID + 2, n2)
                }
            }
        }
        statusJob?.cancel()
        statusJob = scope.launch {
            AgentState.state.collect { st ->
                if (st.running) {
                    val au = AutoState.state.value
                    val step = if (st.progress.active) "Schritt " + RunProgress.notificationLine(st.progress) + " (" + RunProgress.counter(st.progress, RunProgress.End.RUNNING) + "). " else ""
                    val text = step + when {
                        au.running && au.total > 0 -> "Chat ${au.done + 1} von ${au.total}: ${au.current.ifEmpty { st.message }}"
                        st.targetMessages > 0 -> "${st.message} (Nachrichten ${st.messageCount}/${st.targetMessages}, Schritte ${st.scrollDone})"
                        st.scrollTotal > 0 -> "${st.message} (Scroll ${st.scrollDone}/${st.scrollTotal})"
                        else -> st.message
                    }
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text, st.phase))
                }
            }
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(text: String, phase: Phase): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AgentForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val waiting = phase == Phase.WAITING
        val b = NotificationCompat.Builder(this, if (waiting) CH_ARMED else CH_RUN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (waiting) "ChatLens wartet auf WhatsApp" else "ChatLens liest einen Chat")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openAppIntent())
        if (waiting) {
            val now = PendingIntent.getService(
                this, 2, Intent(this, AgentForegroundService::class.java).setAction(ACTION_READ_NOW),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, "Jetzt lesen", now)
        }
        return b.addAction(0, "NOTAUS", stop).build()
    }

    private fun postDone(ok: Boolean, text: String) {
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (ok) "ChatLens: fertig" else "ChatLens: beendet")
            .setContentText(text.take(120))
            .setAutoCancel(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1, n)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        NotificationChannels.create(nm)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.chatlens.START"
        const val ACTION_STOP = "app.chatlens.STOP"
        const val ACTION_READ_NOW = "app.chatlens.READ_NOW"
        const val ACTION_CONFIRM_YES = "app.chatlens.CONFIRM_YES"
        const val ACTION_CONFIRM_NO = "app.chatlens.CONFIRM_NO"
        const val CH_ARMED = NotificationChannels.ARMED
        const val CH_RUN = NotificationChannels.RUN
        const val CH_DONE = NotificationChannels.DONE
        const val NOTIF_ID = 4711

        fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            NotificationChannels.create(nm)
        }
    }
}
