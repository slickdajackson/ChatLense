package app.chatlens.agent

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import app.chatlens.core.Bounds
import app.chatlens.data.AppLog
import kotlinx.coroutines.delay
import app.chatlens.core.UiNode
import app.chatlens.service.ChatAccessibilityService

/** Real device: swipe gesture via dispatchGesture, page scroll via ACTION_SCROLL_BACKWARD/FORWARD. */
class AndroidScrollDevice(private val svc: ChatAccessibilityService, private val guard: ForegroundGuard? = null) : ScrollDevice {

    private fun listNode(want: Bounds?): AccessibilityNodeInfo? {
        val root = svc.liveRoot() ?: return null
        // Never target page switchers (ViewPager) or purely horizontal lists: they would switch the tabs.
        val all = svc.findAllLive(root) { n ->
            n.isScrollable && n.className?.toString()?.contains("ViewPager", ignoreCase = true) != true &&
                n.actionList.any { it.id == AccessibilityAction.ACTION_SCROLL_UP.id || it.id == AccessibilityAction.ACTION_SCROLL_DOWN.id }
        }
        return all.firstOrNull { want != null && svc.boundsOf(it) == want } ?: all.maxByOrNull { svc.boundsOf(it).area }
    }

    override fun snapshot(): UiNode? = svc.snapshot()

    private fun ids(want: Bounds?): List<Int>? = listNode(want)?.actionList?.map { it.id }

    override fun canScrollBack(list: Bounds?): Boolean? {
        val ids = ids(list) ?: return null
        val back = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in ids || AccessibilityAction.ACTION_SCROLL_UP.id in ids
        val fwd = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids || AccessibilityAction.ACTION_SCROLL_DOWN.id in ids
        return when {
            back -> true
            fwd -> false
            else -> null
        }
    }

    override fun canScrollForward(list: Bounds?): Boolean? {
        val ids = ids(list) ?: return null
        val fwd = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids || AccessibilityAction.ACTION_SCROLL_DOWN.id in ids
        val back = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in ids || AccessibilityAction.ACTION_SCROLL_UP.id in ids
        return when {
            fwd -> true
            back -> false
            else -> null
        }
    }

    override suspend fun swipe(list: Bounds, older: Boolean, distancePx: Int, durationMs: Long, holdMs: Long): Boolean {
        // Never swipe in a foreign window: only when the active window is the target app.
        if (svc.foregroundPackage() != svc.expectedPackage) return false
        val ins = svc.screenInsets()
        val segs = SwipeSafety.plan(list, ins, older, distancePx)
        if (segs.isEmpty()) {
            AppLog.w("SWIPE: kein Sicherheitsfenster fuer Liste ${list.t}..${list.b} (Bildschirm ${ins.width}x${ins.height}). Kein Wisch.")
            return false
        }
        val (wt, wb) = SwipeSafety.window(list, ins)
        if (segs.size > 1 || segs.sumOf { it.distance } < distancePx) {
            AppLog.i("SWIPE: gewuenscht $distancePx px, Fenster $wt..$wb, geteilt in ${segs.size} Wischer zu je ${segs[0].distance} px.")
        }
        for ((i, sg) in segs.withIndex()) {
            var repeats = 0
            while (true) {
                AppLog.i("SWIPE ${i + 1}/${segs.size}: (${sg.x},${sg.fromY}) nach (${sg.x},${sg.toY}), Liste ${list.t}..${list.b}, Fenster $wt..$wb, ${if (older) "aeltere" else "neuere"} Nachrichten, ${svc.screenState()}.")
                if (!svc.swipe(sg.x, sg.fromY, sg.toY, durationMs, holdMs)) return false
                // After every swipe: is WhatsApp still in front? Otherwise log the trigger, close the system UI, and bring WhatsApp back.
                val intervened = guard?.recoverAfter("Wisch ${i + 1}/${segs.size} von (${sg.x},${sg.fromY}) nach (${sg.x},${sg.toY})") ?: false
                if (svc.foregroundPackage() != svc.expectedPackage) return false
                if (intervened && repeats++ < MAX_REPEATS) {
                    // The shade or similar was open and has been closed: repeat the same step. This does not count as "Inhalt unveraendert".
                    AppLog.i("SWIPE: Systemoberflaeche war nach dem Wisch vorn und wurde geschlossen. Derselbe Wisch wird wiederholt (zaehlt nicht als unveraenderter Inhalt).")
                    continue
                }
                break
            }
            if (i < segs.size - 1) delay(150)
        }
        return true
    }

    override suspend fun action(list: Bounds, older: Boolean): Boolean {
        val n = listNode(list) ?: run { AppLog.w("ACTION: kein senkrecht scrollbarer Knoten fuer ${list}."); return false }
        val id = if (older) AccessibilityAction.ACTION_SCROLL_UP.id else AccessibilityAction.ACTION_SCROLL_DOWN.id
        val ok = n.performAction(id)
        // Diagnosis: if nothing moves (measured -1 px when 0 px was commanded), this records which node was targeted and with which actions
        AppLog.i("ACTION: ${if (older) "SCROLL_UP" else "SCROLL_DOWN"} auf ${n.className} id=${n.viewIdResourceName ?: "-"} b=${svc.boundsOf(n)} Aktionen=${n.actionList.map { it.id }.joinToString(",")} angenommen=$ok.")
        return ok
    }

    companion object {
        /** After the system UI is closed, the same swipe is repeated at most this many times. */
        const val MAX_REPEATS = 1
    }
}
