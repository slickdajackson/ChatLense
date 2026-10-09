package app.chatlens

import app.chatlens.agent.ScreenInsets
import app.chatlens.agent.SwipeSafety
import app.chatlens.core.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SwipeSafetyTest {
    // Xiaomi 15 Ultra: 1440x3200, status bar about 130 px, gesture zone about 60 px
    private val ins = ScreenInsets(1440, 3200, 130, 60)
    private val lists = listOf(
        Bounds(0, 300, 1440, 2736),   // list height 2436, as in the device test
        Bounds(0, 0, 1440, 3200),     // list under the status bar and the tab bar (full height)
        Bounds(0, 130, 1440, 3200),
        Bounds(0, 400, 1440, 2800),
        Bounds(0, 200, 1440, 1500),   // smaller list
    )

    private fun assertSafe(l: Bounds, older: Boolean, dist: Int) {
        val segs = SwipeSafety.plan(l, ins, older, dist)
        assertTrue("empty for $l", segs.isNotEmpty())
        val (wt, wb) = SwipeSafety.window(l, ins)
        val minTop = 384 // 12 percent of 3200
        val maxBottom = 3200 - 384
        for (s in segs) {
            for (y in listOf(s.fromY, s.toY)) {
                assertTrue("y=$y ausserhalb Fenster $wt..$wb ($l, $dist)", y in wt..wb)
                assertTrue("y=$y naeher als 12 Prozent am Rand", y in minTop..maxBottom)
                assertTrue("y=$y unter Statusleiste+150", y >= ins.statusBar + 150)
                assertTrue("y=$y in der Gestenzone", y <= 3200 - (ins.gestureBottom + 250))
                assertTrue("y=$y ausserhalb der Liste", y in l.t..l.b)
            }
            assertTrue("x=${s.x}", s.x in 216..1224) // 15 percent margin
            // direction: older messages mean the finger moves down
            if (older) assertTrue(s.toY > s.fromY) else assertTrue(s.toY < s.fromY)
            assertTrue("Segment zu lang: ${s.distance}", s.distance <= ((wb - wt) * 0.70).toInt() + 1)
        }
        assertEquals("alle Wischer in einer Spalte", 1, segs.map { it.x }.toSet().size)
        assertTrue("hoechstens zwei Wischer: ${segs.size}", segs.size <= 2)
    }

    @Test fun neverLeavesSafetyWindowForAnyListAndDistance() {
        for (l in lists) for (older in listOf(true, false)) for (d in listOf(1, 50, 400, 900, 1500, 2070, 2436, 5000)) assertSafe(l, older, d)
    }

    @Test fun deviceTestCaseCommandOf2070PxIsSplitAndStaysInside() {
        val l = Bounds(0, 300, 1440, 2736)
        val segs = SwipeSafety.plan(l, ins, older = true, distancePx = 2070)
        assertEquals(2, segs.size)
        assertTrue(segs.sumOf { it.distance } > 1500)
        val (wt, wb) = SwipeSafety.window(l, ins)
        assertTrue(wt >= 384 && wb <= 2816)
        assertTrue(segs.all { it.fromY in wt..wb && it.toY in wt..wb })
    }

    @Test fun smallDistanceIsOneSwipe() {
        assertEquals(1, SwipeSafety.plan(lists[0], ins, true, 500).size)
        assertEquals(500, SwipeSafety.plan(lists[0], ins, true, 500)[0].distance)
    }

    @Test fun tinyListGivesNoSwipe() {
        assertTrue(SwipeSafety.plan(Bounds(0, 1500, 1440, 1560), ins, true, 300).isEmpty())
    }

    @Test fun xIsNeverNearTheSideEdge() {
        assertEquals(720, SwipeSafety.x(Bounds(0, 0, 1440, 3200), ins))
        assertEquals(216, SwipeSafety.x(Bounds(0, 0, 100, 3200), ins))
        assertEquals(1224, SwipeSafety.x(Bounds(1340, 0, 1440, 3200), ins))
    }

    @Test fun pointSafeRejectsStatusBarAndGestureZone() {
        assertTrue(!SwipeSafety.pointSafe(720, 100, ins))
        assertTrue(!SwipeSafety.pointSafe(720, 3150, ins))
        assertTrue(!SwipeSafety.pointSafe(720, 2900, ins))
        assertTrue(SwipeSafety.pointSafe(720, 1500, ins))
        assertTrue(!SwipeSafety.pointSafe(5, 1500, ins))
    }

    @Test fun smallerScreenAndBiggerInsetsStayInside() {
        val i2 = ScreenInsets(1080, 2400, 140, 120)
        val l = Bounds(0, 260, 1080, 2300)
        for (older in listOf(true, false)) for (d in listOf(300, 1200, 2000)) {
            for (s in SwipeSafety.plan(l, i2, older, d)) {
                for (y in listOf(s.fromY, s.toY)) assertTrue("y=$y", y >= maxOf(288, 290) && y <= 2400 - maxOf(288, 370))
            }
        }
    }

    /** Source check: the swipe gesture uses only one x coordinate (vertical only), not a second one. */
    @Test fun serviceSwipeIsVerticalOnly() {
        val src = File("src/main/kotlin/app/chatlens/service/ChatAccessibilityService.kt").readText()
        assertTrue(src.contains("fun swipe(x: Int, fromY: Int, toY: Int"))
        assertTrue(src.contains("SwipeSafety.pointSafe"))
        val dev = File("src/main/kotlin/app/chatlens/agent/AndroidScrollDevice.kt").readText()
        assertTrue(dev.contains("svc.swipe(sg.x, sg.fromY, sg.toY"))
        // LEFT/RIGHT are only read (UiNode.scrollHoriz, to exclude horizontal lists), never performed
        assertTrue(!Regex("performAction\\([^)]*SCROLL_(LEFT|RIGHT)").containsMatchIn(src))
        val nav = File("src/main/kotlin/app/chatlens/agent/WhatsAppNavigator.kt").readText()
        assertTrue(File("src/main/kotlin/app/chatlens/agent/ListLocator.kt").readText().contains("Pager"))
        assertTrue(!nav.contains("performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD"))
        assertTrue(!nav.contains("performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD"))
        assertTrue(!dev.contains("performAction(if (older) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD"))
    }
}
