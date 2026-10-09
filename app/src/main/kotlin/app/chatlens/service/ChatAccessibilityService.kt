package app.chatlens.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.chatlens.agent.ScreenInsets
import app.chatlens.agent.SwipeSafety
import app.chatlens.core.Bounds
import app.chatlens.core.UiNode
import app.chatlens.data.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed class ShotResult {
    class Ok(val bitmap: Bitmap) : ShotResult()
    class Fail(val code: Int, val reason: String) : ShotResult()
}

/**
 * Thin wrapper around the Android accessibility API.
 * The service only reads and performs navigation actions (click, scroll, back, fill the search field).
 * There is no function here for writing or sending chat messages.
 */
class ChatAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
        applyEnabledMessengers(app.chatlens.data.SettingsRepo(this).load().enabledMessengers)
        AppLog.i("Bedienungshilfe verbunden.")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        _connected.value = false
        AppLog.i("Bedienungshilfe getrennt.")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        _connected.value = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Contents are not evaluated; reading happens only on request. Only the package and class of the last
        // window change are remembered (for the "Paket/Aktivitaet" log, and as a second opinion for the foreground check). The service receives only
        // events for the packages in the service configuration (com.whatsapp); leaving WhatsApp therefore often produces no event.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastEventPackage = event.packageName?.toString()
            lastEventClass = event.className?.toString()
            lastEventAt = System.currentTimeMillis()
        }
    }

    override fun onInterrupt() {}

    val density: Float get() = resources.displayMetrics.density

    /** Package treated as the target (set by the navigator from the profile). Only windows of this package are read or operated. */
    @Volatile
    var expectedPackage: String = "com.whatsapp"

    /** Packages of the messengers explicitly allowed (from 0.3.0). Known messenger packages outside this set are never read. */
    @Volatile
    var allowedPackages: Set<String> = setOf("com.whatsapp")
        private set

    /**
     * Applies the allowed messengers. The service configuration (XML) statically names only the shipped packages; at runtime the
     * event source is narrowed to the allowed packages ([AccessibilityServiceInfo.packageNames]). In addition, [liveRoot] refuses any read
     * of a known messenger that is not allowed. Narrowing is provided for by the API; its behavior on Android 13 to 16 is untested.
     */
    fun applyEnabledMessengers(setting: String) {
        allowedPackages = app.chatlens.messenger.MessengerRegistry.enabledPackages(setting)
        runCatching {
            val info = serviceInfo ?: return@runCatching
            info.packageNames = allowedPackages.toTypedArray()
            serviceInfo = info
        }.onFailure { AppLog.w("Paketfilter konnte nicht gesetzt werden: ${it.javaClass.simpleName}") }
    }

    /** True when the package may be read: unknown packages (for example a test dummy via the profile) behave as before; known ones only if allowed. */
    private fun packageReadable(pkg: String): Boolean = app.chatlens.messenger.MessengerRegistry.byPackage(pkg) == null || pkg in allowedPackages

    @Volatile
    var lastEventPackage: String? = null
        private set

    @Volatile
    var lastEventClass: String? = null
        private set

    @Volatile
    var lastEventAt: Long = 0L
        private set

    /** Package of the active window, as reported by the system (including other apps). For checks and the log only. */
    fun activePackage(): String? = foregroundPackage()

    /**
     * Foreground package for the check: if the active window is our own (the overlay), a window underneath
     * that belongs to the target app counts. Otherwise the package of the active window, even if it belongs to another app (then nothing is read; see [liveRoot]).
     */
    fun foregroundPackage(): String? {
        liveRoot()?.let { return it.packageName?.toString() }
        return rootInActiveWindow?.packageName?.toString()
    }

    /**
     * Root of the active window, but ONLY if it belongs to the target app ([expectedPackage]). Windows of other apps return null:
     * reading, clicking, scrolling, and debug export all go through this, so they can never land in another app's window.
     * (Until 0.2.2 some other app window was used as a fallback here; that is removed.)
     * If the active window is our own (the ChatLens overlay), a window of the target app is searched for among the windows.
     */
    fun liveRoot(): AccessibilityNodeInfo? {
        if (!packageReadable(expectedPackage)) return null
        val r = rootInActiveWindow
        val pkg = r?.packageName?.toString()
        if (r != null && pkg == expectedPackage) return r
        if (pkg != null && pkg != packageName) return null
        val ws = runCatching { windows }.getOrNull().orEmpty()
        for (w in ws) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val wr = w.root ?: continue
            if (wr.packageName?.toString() == expectedPackage) return wr
        }
        return null
    }

    /** Root of the active window of any other app, only for the manual debug export. Never for navigation. */
    fun anyForeignRoot(): AccessibilityNodeInfo? {
        val r = rootInActiveWindow ?: return null
        return if (r.packageName?.toString() != packageName) r else liveRoot()
    }

    /** Screen state for the log: on or off (PowerManager.isInteractive) and whether the lock screen is active. No contents. */
    fun screenState(): String = try {
        val pm = getSystemService(android.os.PowerManager::class.java)
        val km = getSystemService(android.app.KeyguardManager::class.java)
        "Bildschirm ${if (pm?.isInteractive == true) "an" else "AUS"}, Sperrbildschirm ${if (km?.isKeyguardLocked == true) "aktiv" else "nein"}"
    } catch (e: Exception) {
        "Bildschirmzustand nicht lesbar"
    }

    private var awakeView: android.view.View? = null

    /**
     * Keeps the screen on during a run: a 1x1 pixel, invisible, untouchable accessibility overlay with FLAG_KEEP_SCREEN_ON
     * (needs no extra permission). Background: node actions and injected gestures do not reliably count as user activity, so the screen
     * can turn off after the screen-lock timeout, and then the lock screen (com.android.systemui) is in front.
     */
    fun setKeepScreenOn(on: Boolean) {
        runCatching {
            val wm = getSystemService(android.view.WindowManager::class.java)
            if (on && awakeView == null) {
                val v = android.view.View(this)
                val lp = android.view.WindowManager.LayoutParams(
                    1, 1, android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    android.graphics.PixelFormat.TRANSPARENT,
                ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START }
                wm.addView(v, lp)
                awakeView = v
                AppLog.i("BILDSCHIRM: wird waehrend des Laufs wachgehalten (${screenState()}).")
            } else if (!on) {
                awakeView?.let { wm.removeView(it) }
                if (awakeView != null) AppLog.i("BILDSCHIRM: Wachhalten beendet.")
                awakeView = null
            }
        }.onFailure { AppLog.w("BILDSCHIRM: Wachhalten nicht moeglich: ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** Window summary for the log: type, layer, package. No contents. The screen state is appended at the end. */
    fun windowsSummary(): String = try {
        windows.joinToString("; ") { w ->
            val t = when (w.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> "APP"
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
                AccessibilityWindowInfo.TYPE_SYSTEM -> "SYS"
                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y"
                else -> "T${w.type}"
            }
            "$t/L${w.layer}/${w.root?.packageName ?: "?"}" + (if (w.isActive) "/aktiv" else "") + (if (w.isFocused) "/fokus" else "")
        }.ifEmpty { "keine" } + "; " + screenState()
    } catch (e: Exception) {
        "nicht lesbar (${e.javaClass.simpleName}); " + screenState()
    }

    /** Brings the app to the front with an intent. Started from the service (services may start activities more easily than background apps). */
    fun launchApp(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            AppLog.w("FG: Intent-Start fehlgeschlagen: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Snapshot of the active window of the target app (null if another app's window is active). [anyApp] is only for the manual debug export. */
    fun snapshot(maxNodes: Int = 5000, anyApp: Boolean = false): UiNode? {
        val root = (if (anyApp) anyForeignRoot() else liveRoot()) ?: return null
        val budget = intArrayOf(maxNodes)
        return toUiNode(root, 0, budget)
    }

    private fun toUiNode(n: AccessibilityNodeInfo, depth: Int, budget: IntArray): UiNode {
        budget[0]--
        val r = Rect()
        n.getBoundsInScreen(r)
        val kids = ArrayList<UiNode>()
        if (depth < 60) {
            for (i in 0 until n.childCount) {
                if (budget[0] <= 0) break
                val c = n.getChild(i) ?: continue
                kids.add(toUiNode(c, depth + 1, budget))
            }
        }
        val ids = n.actionList.map { it.id }
        return UiNode(
            className = n.className?.toString() ?: "",
            viewId = n.viewIdResourceName,
            text = n.text?.toString(),
            desc = n.contentDescription?.toString(),
            bounds = Bounds(r.left, r.top, r.right, r.bottom),
            clickable = n.isClickable,
            scrollable = n.isScrollable,
            editable = n.isEditable,
            visible = n.isVisibleToUser,
            children = kids,
            focused = n.isFocused,
            selected = n.isSelected,
            scrollUp = ids.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id),
            scrollDown = ids.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id),
            scrollHoriz = ids.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id) || ids.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id),
            scrollGeneric = ids.contains(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) || ids.contains(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD),
        )
    }

    /** Finds a live node by predicate (depth-first search). */
    fun findLive(root: AccessibilityNodeInfo? = liveRoot(), pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        fun rec(n: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (pred(n)) return n
            if (depth > 60) return null
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                rec(c, depth + 1)?.let { return it }
            }
            return null
        }
        return rec(root, 0)
    }

    fun findAllLive(root: AccessibilityNodeInfo? = liveRoot(), pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        if (root == null) return out
        fun rec(n: AccessibilityNodeInfo, depth: Int) {
            if (pred(n)) out.add(n)
            if (depth > 60) return
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                rec(c, depth + 1)
            }
        }
        rec(root, 0)
        return out
    }

    fun boundsOf(n: AccessibilityNodeInfo): Bounds {
        val r = Rect()
        n.getBoundsInScreen(r)
        return Bounds(r.left, r.top, r.right, r.bottom)
    }

    /** Top edge of the keyboard in pixels, or null if no keyboard is visible (needs flagRetrieveInteractiveWindows). */
    fun imeTop(): Int? = try {
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }.mapNotNull { w ->
            val r = Rect()
            w.getBoundsInScreen(r)
            if (r.height() > 0) r.top else null
        }.minOrNull()
    } catch (e: Exception) {
        null
    }

    /**
     * A single finger tap as a gesture. Intended only for navigation (tapping a search result);
     * the caller checks that the coordinate lies in the result-list area.
     */
    suspend fun tap(x: Int, y: Int): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null,
        )
        if (!accepted && cont.isActive) cont.resume(false)
    }

    private suspend fun dispatch(g: GestureDescription): Boolean = suspendCancellableCoroutine { cont ->
        val accepted = dispatchGesture(
            g,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null,
        )
        if (!accepted && cont.isActive) cont.resume(false)
    }

    /**
     * Swipe gesture along a defined path, only for scrolling the message list.
     * After the movement, a second, stationary segment holds the finger briefly ([holdMs]) so there is no fling
     * and the step size matches the planned path. Whether it behaves that way on the device is untested.
     */
    suspend fun swipe(x: Int, fromY: Int, toY: Int, durationMs: Long, holdMs: Long): Boolean {
        // Final guard: start and end lie strictly inside the safety window (never near the status bar, the gesture zone, or the screen edge).
        val ins = screenInsets()
        if (!SwipeSafety.pointSafe(x, fromY, ins) || !SwipeSafety.pointSafe(x, toY, ins)) {
            AppLog.w("SWIPE verweigert: ($x,$fromY) nach ($x,$toY) liegt ausserhalb des Sicherheitsfensters (Bildschirm ${ins.width}x${ins.height}, Statusleiste ${ins.statusBar}, Gestenzone ${ins.gestureBottom}).")
            return false
        }
        val move = Path().apply {
            moveTo(x.toFloat(), fromY.toFloat())
            lineTo(x.toFloat(), toY.toFloat())
        }
        val first = GestureDescription.StrokeDescription(move, 0, durationMs.coerceAtLeast(1), holdMs > 0)
        if (!dispatch(GestureDescription.Builder().addStroke(first).build())) return false
        if (holdMs <= 0) return true
        val hold = Path().apply { moveTo(x.toFloat(), toY.toFloat()) }
        val second = first.continueStroke(hold, 0, holdMs, false)
        return dispatch(GestureDescription.Builder().addStroke(second).build())
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    @Volatile
    private var insetsCache: ScreenInsets? = null

    /** Screen size and system zones for planning gestures. The status and gesture zones come from system resources, or from the window insets when possible. */
    fun screenInsets(): ScreenInsets {
        insetsCache?.let { return it }
        val dm = resources.displayMetrics
        var w = dm.widthPixels
        var h = dm.heightPixels
        fun dimen(name: String): Int {
            val id = resources.getIdentifier(name, "dimen", "android")
            return if (id > 0) resources.getDimensionPixelSize(id) else 0
        }
        var status = dimen("status_bar_height").takeIf { it > 0 } ?: (24 * dm.density).toInt()
        var gesture = maxOf(dimen("navigation_bar_height"), (24 * dm.density).toInt())
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val wm = getSystemService(android.view.WindowManager::class.java)
                val m = wm.maximumWindowMetrics
                w = m.bounds.width(); h = m.bounds.height()
                val st = m.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top
                val sg = m.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemGestures()).bottom
                if (st > 0) status = st
                if (sg > 0) gesture = maxOf(gesture, sg)
            } catch (e: Exception) {
                AppLog.w("Fensterraender nicht lesbar (${e.javaClass.simpleName}), nutze Systemressourcen.")
            }
        }
        val r = ScreenInsets(w, h, status, gesture)
        insetsCache = r
        AppLog.i("GEOMETRIE: Bildschirm ${w}x$h px, Statusleiste $status px, Gestenzone unten $gesture px.")
        return r
    }

    /** Closes the notification shade or the control center: first DISMISS_NOTIFICATION_SHADE (from Android 12), then Back. */
    fun dismissSystemUi(): Boolean {
        var ok = false
        if (Build.VERSION.SDK_INT >= 31) ok = performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        return performGlobalAction(GLOBAL_ACTION_BACK) || ok
    }

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** Screenshot of the whole screen (from Android 11). */
    suspend fun screenshot(): ShotResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ShotResult.Fail(-1, "takeScreenshot braucht Android 11 oder neuer")
        }
        val since = System.currentTimeMillis() - lastShotAt
        if (since < 1_100) delay(1_100 - since)
        var res = shootOnce()
        if (res is ShotResult.Fail && res.code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
            delay(1_300)
            res = shootOnce()
        }
        lastShotAt = System.currentTimeMillis()
        return res
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun shootOnce(): ShotResult = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val hb = result.hardwareBuffer
                        val bmp = try {
                            Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (e: Exception) {
                            null
                        } finally {
                            hb.close()
                        }
                        if (cont.isActive) {
                            cont.resume(if (bmp != null) ShotResult.Ok(bmp) else ShotResult.Fail(-2, "Bitmap konnte nicht erzeugt werden"))
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val why = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "Fenster ist gegen Screenshots gesperrt (FLAG_SECURE)"
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Screenshots zu schnell hintereinander"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "Dienst hat kein Screenshot-Recht (canTakeScreenshot)"
                            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "interner Fehler"
                            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "ungueltiges Display"
                            else -> "Fehlercode $errorCode"
                        }
                        if (cont.isActive) cont.resume(ShotResult.Fail(errorCode, why))
                    }
                },
            )
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(ShotResult.Fail(-3, "${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    companion object {
        @Volatile
        var instance: ChatAccessibilityService? = null
            private set
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected
        private var lastShotAt = 0L
    }
}
