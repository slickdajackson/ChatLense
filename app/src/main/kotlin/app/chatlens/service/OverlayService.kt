package app.chatlens.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.chatlens.MainActivity
import app.chatlens.R
import app.chatlens.agent.AgentController
import app.chatlens.agent.AgentState
import app.chatlens.agent.Phase
import app.chatlens.agent.ProfileStore
import app.chatlens.assist.InsertOutcome
import app.chatlens.assist.ReplyInserter
import app.chatlens.agent.PromptChoiceBroker
import app.chatlens.core.ScrollRunConfig
import app.chatlens.core.StopMode
import app.chatlens.core.TaskMode
import app.chatlens.data.AppLog
import app.chatlens.data.BackendChoice
import app.chatlens.data.SettingsRepo
import app.chatlens.ui.GlassTheme
import app.chatlens.ui.OVERLAY_DOT_DP
import app.chatlens.ui.OVERLAY_RING_DP
import app.chatlens.ui.OverlayDot
import app.chatlens.ui.OverlayPanel
import app.chatlens.ui.RingAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val reg = LifecycleRegistry(this)
    private val ssc = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = reg
    override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()
    fun start() {
        ssc.performAttach()
        ssc.performRestore(null)
        reg.currentState = Lifecycle.State.RESUMED
    }
    fun stop() {
        reg.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

/**
 * Floating dot (overlay) as a foreground service. Window: TYPE_APPLICATION_OVERLAY with FLAG_NOT_FOCUSABLE, so WhatsApp
 * stays the active window and reading is not disturbed. The dot starts only jobs the user taps;
 * it never sends anything. Unverified on device (HyperOS can restrict overlays).
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private val owner = OverlayOwner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var dotView: ComposeView? = null
    private var panelView: ComposeView? = null
    private var dotLp: WindowManager.LayoutParams? = null
    private var expanded by mutableStateOf(false)
    private var panelShown by mutableStateOf(false)
    private var removeArmedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        owner.start()
        // While reading (swipes run under the overlay), the dot and panel let touches pass through and are semi-transparent
        scope.launch {
            AgentState.state.map { it.phase in READING_PHASES }.distinctUntilChanged().collect { setReadingMode(it) }
        }
        // Prompt choice: while it is open, the panel needs focus for the keyboard (otherwise it stays non-focusable, so WhatsApp keeps the input)
        scope.launch {
            PromptChoiceBroker.pending.collect { req ->
                if (req != null) {
                    if (dotView != null) { showPanel(); setPanelFocusable(true) }
                } else {
                    setPanelFocusable(false)
                }
            }
        }
    }

    private var reading = false

    /**
     * Read mode: FLAG_NOT_TOUCHABLE on the dot and panel, so injected swipes and taps pass through to WhatsApp and do not land
     * in our own window (the panel sits at the top center, exactly where swipes start). The ring is closed. Cancel during reading: the NOTAUS notification.
     */
    private fun setReadingMode(on: Boolean) {
        reading = on
        if (on) applyExpanded(false)
        for (v in listOfNotNull(dotView, panelView)) {
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: continue
            lp.flags = if (on) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            v.alpha = if (on) 0.5f else 1f
            runCatching { wm.updateViewLayout(v, lp) }
        }
        AppLog.i("OVERLAY: Lesemodus ${if (on) "an (Punkt und Panel beruehrungsdurchlaessig)" else "aus"}.")
    }

    /** Makes the panel window focusable (keyboard) or switches it back. Hypothesis: not yet tested on HyperOS; the fallback is the dialog in the app. */
    private fun setPanelFocusable(on: Boolean) {
        val v = panelView ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        lp.softInputMode = if (on) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE else WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        runCatching { wm.updateViewLayout(v, lp) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // "Punkt entfernen" action in the notification: same as in the menu, but without canceling a run (that continues in its own service)
            removeDot(OverlayRemoval.MSG_REMOVED)
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotif()
        if (dotView == null) addDot()
        return START_NOT_STICKY
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Schwebender Punkt", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 5, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 6, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("ChatLens: schwebender Punkt aktiv")
            .setContentText("Tippen öffnet ChatLens. Zum Entfernen des Punkts: Knopf unten.")
            .setContentIntent(open)
            .addAction(0, "Punkt entfernen", stop)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun baseParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun attach(v: ComposeView) {
        v.setViewTreeLifecycleOwner(owner)
        v.setViewTreeSavedStateRegistryOwner(owner)
        v.setViewTreeViewModelStoreOwner(owner)
    }

    /** Visible area without system bars. */
    private fun area(): OverlayGeometry.Area {
        val dm = resources.displayMetrics
        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            val i = m.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            return OverlayGeometry.Area(m.bounds.width(), m.bounds.height(), i.left, i.top, i.right, i.bottom)
        }
        val sb = resources.getIdentifier("status_bar_height", "dimen", "android").let { if (it > 0) resources.getDimensionPixelSize(it) else dp(24) }
        return OverlayGeometry.Area(dm.widthPixels, dm.heightPixels, 0, sb, 0, 0)
    }

    private val margin get() = dp(8)
    private var dotX = 0
    private var dotY = 0

    /** Places the dot from the remembered side and height fraction (start, rotation). */
    private fun placeFromSaved() {
        val lp = dotLp ?: return
        val s = SettingsRepo(this).load()
        val a = area()
        val dot = dp(OVERLAY_DOT_DP)
        dotX = OverlayGeometry.xAtSide(OverlayGeometry.sideOf(s.dotSide), dot, a, margin)
        dotY = OverlayGeometry.yFromFraction(s.dotYFraction, dot, a)
        applyPosition(lp)
    }

    private fun applyPosition(lp: WindowManager.LayoutParams) {
        val a = area()
        if (expanded) {
            val (rx, ry) = OverlayGeometry.ringOrigin(dotX, dotY, dp(OVERLAY_DOT_DP), dp(OVERLAY_RING_DP), a)
            lp.x = rx; lp.y = ry
        } else {
            val (cx, cy) = OverlayGeometry.clamp(dotX, dotY, dp(OVERLAY_DOT_DP), dp(OVERLAY_DOT_DP), a)
            dotX = cx; dotY = cy
            lp.x = cx; lp.y = cy
        }
        dotView?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    /** After release: snap to the nearest edge (a short animation) and remember the side together with the height fraction. */
    private fun snapAndSave() {
        val lp = dotLp ?: return
        val a = area()
        val dot = dp(OVERLAY_DOT_DP)
        val side = OverlayGeometry.snapSide(dotX, dot, a)
        val targetX = OverlayGeometry.xAtSide(side, dot, a, margin)
        val (_, ty) = OverlayGeometry.clamp(dotX, dotY, dot, dot, a)
        val fromX = dotX
        dotY = ty
        android.animation.ValueAnimator.ofInt(fromX, targetX).apply {
            duration = 160
            addUpdateListener { dotX = it.animatedValue as Int; applyPosition(lp) }
            start()
        }
        val repo = SettingsRepo(this)
        runCatching { repo.save(repo.load().copy(dotSide = side.value, dotYFraction = OverlayGeometry.yFraction(ty, dot, a))) }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation, foldable, split screen: recompute the position from the side and the height fraction, and clamp it
        if (dotView != null) placeFromSaved()
    }

    private fun addDot() {
        val lp = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        dotLp = lp
        val v = ComposeView(this)
        attach(v)
        v.setContent {
            GlassTheme {
                OverlayDot(
                    expanded = expanded,
                    onToggle = { applyExpanded(!expanded) },
                    onDrag = { dx, dy ->
                        dotX += dx.toInt()
                        dotY += dy.toInt()
                        applyPosition(lp)
                    },
                    onDragEnd = { snapAndSave() },
                    onAction = { onAction(it) },
                )
            }
        }
        wm.addView(v, lp)
        dotView = v
        placeFromSaved()
        if (reading) setReadingMode(true)
    }

    /** Expand or collapse the ring: the window grows around the dot so the dot stays in place. */
    private fun applyExpanded(e: Boolean) {
        if (e == expanded) return
        val lp = dotLp ?: return
        expanded = e
        // Ring at the edge: the window shifts inward and the dot moves with it (see OverlayGeometry.ringOrigin); on close the dot returns
        applyPosition(lp)
    }

    private fun onAction(a: RingAction) {
        applyExpanded(false)
        val s = SettingsRepo(this).load()
        when (a) {
            RingAction.ANALYSE -> startForOpenChat(TaskMode.ANALYSE)
            RingAction.SUGGEST -> startForOpenChat(TaskMode.SUGGEST)
            RingAction.ADVISE -> {
                // The user has to enter the desired result in the app; without a goal there is no advice.
                if (s.lastGoal.isBlank()) { toast("Berater braucht ein Ziel. Bitte in den Einstellungen unter Aufträge eintragen.", true); openApp(TAB_SETTINGS, null) } else startForOpenChat(TaskMode.ADVISE)
            }
            RingAction.AUTO -> openApp(TAB_AUTO, null)
            // Self-analysis is a multi-chat task: it needs the checkup and is therefore chosen on the start page
            RingAction.SELF -> openApp(TAB_START, null)
            RingAction.SETTINGS -> openApp(TAB_SETTINGS, null)
            RingAction.REMOVE -> onRemove()
        }
    }

    /** "Entfernen": if a job is running, warn first (a toast), then on the second tap cancel and remove. */
    private fun onRemove() {
        val now = System.currentTimeMillis()
        val running = AgentController.isRunning()
        val step = OverlayRemoval.decide(running, removeArmedAt, now)
        when (step) {
            OverlayRemoval.Step.WARN_FIRST -> {
                removeArmedAt = now
                toast(OverlayRemoval.message(step), long = true)
            }
            OverlayRemoval.Step.REMOVE_NOW -> removeDot(OverlayRemoval.message(step))
            OverlayRemoval.Step.CANCEL_AND_REMOVE -> {
                AgentController.cancel("Lauf abgebrochen: Punkt wurde entfernt.")
                removeDot(OverlayRemoval.message(step))
            }
        }
    }

    private fun toast(msg: String, long: Boolean = false) {
        android.widget.Toast.makeText(applicationContext, msg, if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Stop the dot and the service, save the switch in settings (off), and notify the open app. */
    private fun removeDot(msg: String) {
        val repo = SettingsRepo(this)
        runCatching { repo.save(repo.load().copy(overlayEnabled = false)) }
        AppLog.i("OVERLAY: Punkt entfernt (Schalter gespeichert: aus).")
        OverlayEvents.removed.value = OverlayEvents.removed.value + 1
        toast(msg, long = true)
        hidePanel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openApp(tab: Int, task: TaskMode?) {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_TAB, tab).putExtra(MainActivity.EXTRA_TASK, task?.name),
        )
    }

    private fun startForOpenChat(task: TaskMode) {
        val s = SettingsRepo(this).load()
        showPanel()
        if (ChatAccessibilityService.instance == null) {
            AgentState.update { it.copy(phase = Phase.FAILED, error = "Bedienungshilfe ist nicht aktiv.", message = "Nicht gestartet.") }
            return
        }
        if (!s.privacyAcknowledged || s.backend == BackendChoice.EXTRACT_ONLY) {
            AgentState.update { it.copy(phase = Phase.FAILED, error = "Datenschutzhinweis bestätigen und in den Einstellungen ein Modell wählen.", message = "Nicht gestartet.") }
            return
        }
        val cfg = ScrollRunConfig(
            chatTitle = "", chatAlreadyOpen = true, scrollCount = s.lastScrollCount, instruction = s.lastInstruction,
            // Analyse reads the chat that is currently open, up to the target amount, then the choice "wie immer" or a custom prompt
            stopMode = if (task == TaskMode.ANALYSE) StopMode.TARGET else s.lastStopMode,
            targetMessages = s.lastTargetMessages.coerceIn(1, 5000),
            task = task, goal = s.lastGoal.trim(), useMemory = true, askPrompt = task == TaskMode.ANALYSE,
        )
        if (!AgentController.start(this, cfg, s)) {
            AgentState.update { it.copy(message = "Es läuft bereits ein Auftrag.") }
            return
        }
        scope.launch {
            withTimeoutOrNull(8000) { AgentState.state.first { it.phase == Phase.WAITING } }
            delay(300)
            AgentController.requestReadNow()
        }
    }

    private fun showPanel() {
        if (panelView != null) {
            panelShown = true
            return
        }
        val lp = baseParams(dp(320), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(60)
        }
        val v = ComposeView(this)
        attach(v)
        v.setContent {
            GlassTheme {
                OverlayPanel(
                    onInsert = { insert(it) },
                    onClose = { hidePanel() },
                    onOpenApp = { openApp(TAB_START, null) },
                    onCancel = { AgentController.cancel("Lauf abgebrochen (Overlay-Panel).") },
                    onRemoveDot = { onRemove() },
                    maxHeightDp = (resources.displayMetrics.heightPixels / resources.displayMetrics.density * PANEL_MAX_FRACTION).toInt(),
                )
            }
        }
        wm.addView(v, lp)
        panelView = v
        panelShown = true
        if (reading) setReadingMode(true)
    }

    private fun hidePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        panelShown = false
    }

    private fun insert(text: String) {
        val svc = ChatAccessibilityService.instance
        if (svc == null) {
            AgentState.update { it.copy(message = "Bedienungshilfe nicht aktiv.") }
            return
        }
        val r = ReplyInserter(svc, ProfileStore.load(this)).insert(text)
        AgentState.update {
            it.copy(
                message = when (r) {
                    InsertOutcome.OK -> "Eingetragen. Prüfen und selbst absenden."
                    InsertOutcome.NO_INPUT_FIELD -> "Eingabefeld nicht gefunden. Chat in WhatsApp öffnen oder Text kopieren."
                    else -> "Eintragen fehlgeschlagen."
                },
            )
        }
    }

    override fun onDestroy() {
        hidePanel()
        dotView?.let { runCatching { wm.removeView(it) } }
        dotView = null
        owner.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Phases in which swipes and taps run: the overlay then lets touches pass through. */
        val READING_PHASES = setOf(Phase.NAVIGATING, Phase.SCROLLING, Phase.CAPTURING)

        /** Maximum fraction of the screen height for the panel; above that, the content scrolls. */
        const val PANEL_MAX_FRACTION = 0.6
        const val ACTION_STOP = "app.chatlens.OVERLAY_STOP"
        const val CH = "overlay"
        const val NOTIF_ID = 4721
        const val TAB_START = 0
        const val TAB_AUTO = 1
        const val TAB_SETTINGS = 4

        fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)

        fun start(ctx: Context) {
            if (!canDraw(ctx)) return
            ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
