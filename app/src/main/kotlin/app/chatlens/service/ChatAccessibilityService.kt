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
 * Duenne Huelle um die Android-Accessibility-API.
 * Der Dienst liest nur und fuehrt Navigationsaktionen aus (Klick, Scrollen, Zurueck, Suchfeld ausfuellen).
 * Es gibt hier keine Funktion zum Schreiben oder Senden von Chatnachrichten.
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
        // Inhalte werden nicht ausgewertet; gelesen wird nur auf Anforderung. Gemerkt wird nur Paket und Klasse des letzten
        // Fensterwechsels (fuer das Log "Paket/Aktivitaet" und als zweite Meinung der Vordergrundpruefung). Der Dienst bekommt nur
        // Ereignisse der Pakete aus der Dienstkonfiguration (com.whatsapp); verlaesst man WhatsApp, kommt daher oft kein Ereignis.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastEventPackage = event.packageName?.toString()
            lastEventClass = event.className?.toString()
            lastEventAt = System.currentTimeMillis()
        }
    }

    override fun onInterrupt() {}

    val density: Float get() = resources.displayMetrics.density

    /** Paket, das als Ziel gilt (wird vom Navigator aus dem Profil gesetzt). Nur Fenster dieses Pakets werden gelesen oder bedient. */
    @Volatile
    var expectedPackage: String = "com.whatsapp"

    /** Pakete der ausdruecklich freigegebenen Messenger (ab 0.3.0). Bekannte Messenger-Pakete ausserhalb dieser Menge werden nie gelesen. */
    @Volatile
    var allowedPackages: Set<String> = setOf("com.whatsapp")
        private set

    /**
     * Uebernimmt die freigegebenen Messenger: Die Dienstkonfiguration (XML) nennt statisch nur die gelieferten Pakete; zur Laufzeit wird die
     * Ereignisquelle auf die freigegebenen Pakete eingeengt ([AccessibilityServiceInfo.packageNames]). Zusaetzlich verweigert [liveRoot] jedes Lesen
     * eines bekannten Messengers, der nicht freigegeben ist. Das Einengen ist laut API vorgesehen; sein Verhalten auf Android 13 bis 16 ist ungeprueft.
     */
    fun applyEnabledMessengers(setting: String) {
        allowedPackages = app.chatlens.messenger.MessengerRegistry.enabledPackages(setting)
        runCatching {
            val info = serviceInfo ?: return@runCatching
            info.packageNames = allowedPackages.toTypedArray()
            serviceInfo = info
        }.onFailure { AppLog.w("Paketfilter konnte nicht gesetzt werden: ${it.javaClass.simpleName}") }
    }

    /** true, wenn das Paket gelesen werden darf: unbekannte Pakete (z. B. Testattrappe per Profil) gelten wie bisher, bekannte nur wenn freigegeben. */
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

    /** Paket des aktiven Fensters, wie es das System meldet (auch fremde Apps). Nur fuer Pruefung und Log. */
    fun activePackage(): String? = foregroundPackage()

    /**
     * Paket des Vordergrunds fuer die Pruefung: Ist das aktive Fenster ein eigenes (Overlay), zaehlt ein darunter liegendes Fenster
     * der Ziel-App. Sonst das Paket des aktiven Fensters, auch wenn es fremd ist (dann wird nichts gelesen, siehe [liveRoot]).
     */
    fun foregroundPackage(): String? {
        liveRoot()?.let { return it.packageName?.toString() }
        return rootInActiveWindow?.packageName?.toString()
    }

    /**
     * Wurzel des aktiven Fensters, aber NUR wenn es zur Ziel-App gehoert ([expectedPackage]). Fremde Fenster liefern null:
     * Lesen, Klicken, Scrollen und Debug-Export laufen alle darueber und koennen so nie in einem fremden Fenster landen.
     * (Bis 0.2.2 wurde hier ersatzweise irgendein anderes App-Fenster genommen; das ist entfernt.)
     * Ist das aktive Fenster ein eigenes (ChatLens-Overlay), wird ein Fenster der Ziel-App unter den Fenstern gesucht.
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

    /** Wurzel des aktiven Fensters irgendeiner fremden App, nur fuer den manuellen Debug-Export. Nie fuer Navigation. */
    fun anyForeignRoot(): AccessibilityNodeInfo? {
        val r = rootInActiveWindow ?: return null
        return if (r.packageName?.toString() != packageName) r else liveRoot()
    }

    /** Bildschirmzustand fuer das Log: an/aus (PowerManager.isInteractive) und ob der Sperrbildschirm aktiv ist. Keine Inhalte. */
    fun screenState(): String = try {
        val pm = getSystemService(android.os.PowerManager::class.java)
        val km = getSystemService(android.app.KeyguardManager::class.java)
        "Bildschirm ${if (pm?.isInteractive == true) "an" else "AUS"}, Sperrbildschirm ${if (km?.isKeyguardLocked == true) "aktiv" else "nein"}"
    } catch (e: Exception) {
        "Bildschirmzustand nicht lesbar"
    }

    private var awakeView: android.view.View? = null

    /**
     * Haelt den Bildschirm waehrend eines Laufs an: ein 1x1 Pixel grosses, unsichtbares, nicht beruehrbares Bedienungshilfe-Overlay mit FLAG_KEEP_SCREEN_ON
     * (braucht keine zusaetzliche Berechtigung). Hintergrund: Knotenaktionen und eingespeiste Gesten gelten nicht sicher als Nutzeraktivitaet, der Bildschirm
     * kann nach der Zeitspanne der Bildschirmsperre ausgehen, und dann liegt der Sperrbildschirm (com.android.systemui) vorn.
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

    /** Fenster-Uebersicht fuer das Log: Typ, Ebene, Paket. Keine Inhalte. Am Ende der Bildschirmzustand. */
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

    /** Holt die App per Intent nach vorn. Aus dem Dienst heraus gestartet (Dienste duerfen Aktivitaeten leichter starten als Hintergrund-Apps). */
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

    /** Schnappschuss des aktiven Fensters der Ziel-App (null, wenn ein fremdes Fenster aktiv ist). [anyApp] nur fuer den manuellen Debug-Export. */
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

    /** Sucht einen lebenden Knoten per Praedikat (Tiefensuche). */
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

    /** Oberkante der Tastatur in Pixeln oder null, wenn keine Tastatur sichtbar ist (braucht flagRetrieveInteractiveWindows). */
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
     * Einzelner Fingertipp per Geste. Nur fuer die Navigation (Suchtreffer antippen) gedacht;
     * der Aufrufer prueft, dass die Koordinate im Ergebnislistenbereich liegt.
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
     * Wischgeste mit definiertem Weg, nur zum Scrollen der Nachrichtenliste.
     * Nach der Bewegung haelt ein zweiter, stillstehender Abschnitt den Finger kurz an ([holdMs]), damit kein Nachschwung (Fling)
     * entsteht und die Schrittweite dem geplanten Weg entspricht. Ob das auf dem Geraet so wirkt, ist ungetestet.
     */
    suspend fun swipe(x: Int, fromY: Int, toY: Int, durationMs: Long, holdMs: Long): Boolean {
        // Letzte Sperre: Start und Ende liegen strikt im Sicherheitsfenster (nie nahe Statusleiste, Gestenzone oder Seitenrand).
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

    /** Bildschirmmasse und Systemzonen fuer die Gestenplanung. Status- und Gestenzone aus Systemressourcen, wenn moeglich aus den Fensterraendern. */
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

    /** Schliesst Benachrichtigungsleiste oder Kontrollzentrum: erst DISMISS_NOTIFICATION_SHADE (ab Android 12), dann Zurueck. */
    fun dismissSystemUi(): Boolean {
        var ok = false
        if (Build.VERSION.SDK_INT >= 31) ok = performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        return performGlobalAction(GLOBAL_ACTION_BACK) || ok
    }

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** Screenshot des gesamten Bildschirms (ab Android 11). */
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
