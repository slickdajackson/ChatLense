package app.chatlens

import app.chatlens.agent.AgentException
import app.chatlens.agent.ForegroundGuard
import app.chatlens.agent.ForegroundProbe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ForegroundGuardTest {
    private class Fake : ForegroundProbe {
        var clock = 0L
        var root: String? = "com.whatsapp"
        var event: String? = null
        var eventAt = 0L
        var act: String? = "com.whatsapp.HomeActivity"
        var launches = 0
        var dismisses = 0
        var homes = 0
        var onDismiss: (Fake) -> Unit = {}
        var onLaunch: (Fake) -> Unit = {}
        override fun dismissSystemUi(): Boolean { dismisses++; onDismiss(this); return true }
        override fun goHome(): Boolean { homes++; return true }
        override fun rootPackage() = root
        override fun eventPackage() = event
        override fun eventAgeMs() = if (event == null) Long.MAX_VALUE else clock - eventAt
        override fun activity() = act
        override fun windowsSummary() = "APP/L1/$root/aktiv"
        override fun launch(): Boolean { launches++; onLaunch(this); return true }
    }

    private fun guard(f: Fake, expected: String = "com.whatsapp", log: MutableList<String> = ArrayList()) =
        ForegroundGuard(f, expected, "app.chatlens", { log.add(it) }, { f.clock += it }, { f.clock })

    private fun fails(block: suspend () -> Unit): AgentException {
        try { runBlocking { block() } } catch (e: AgentException) { return e }
        fail("AgentException erwartet")
        throw IllegalStateException()
    }

    @Test fun okWhenWhatsAppIsFront() = runBlocking {
        val f = Fake()
        val log = ArrayList<String>()
        val g = guard(f, log = log)
        g.ensure(true)
        assertEquals(0, f.launches)
        assertTrue(g.isForeground())
        assertTrue(log.any { it.contains("aktives Paket com.whatsapp") && it.contains("com.whatsapp.HomeActivity") })
    }

    @Test fun foreignWindowIsBroughtBackOnSecondAttempt() = runBlocking {
        val f = Fake().apply { root = "com.other.app" }
        f.onLaunch = { if (it.launches == 2) it.root = "com.whatsapp" }
        val log = ArrayList<String>()
        guard(f, log = log).ensure(true)
        assertEquals(2, f.launches)
        assertTrue(log.any { it.contains("Fremdes Fenster") && it.contains("Versuch 1 von 3") })
        assertTrue(log.any { it.contains("Versuch 2 von 3") })
    }

    @Test fun threeFailedAttemptsAbortWithClearMessage() {
        val f = Fake().apply { root = "com.other.app" }
        val e = fails { guard(f).ensure(true, "Setup") }
        assertEquals(3, f.launches)
        assertTrue(e.message!!, e.message!!.contains("com.other.app"))
        assertTrue(e.message!!.contains("3 Versuchen"))
        assertTrue(e.message!!.contains("nichts angetippt"))
        assertTrue(e.message!!.startsWith("Setup: "))
    }

    @Test fun shortTransitionNeedsNoLaunch() = runBlocking {
        val f = Fake().apply { root = "com.android.launcher" }
        val g = ForegroundGuard(f, "com.whatsapp", "app.chatlens", {}, { f.clock += it; if (f.clock >= 400) f.root = "com.whatsapp" }, { f.clock })
        g.ensure(true)
        assertEquals(0, f.launches)
    }

    @Test fun businessAndOwnAppGetSpecificMessages() {
        val b = Fake().apply { root = "com.whatsapp.w4b" }
        assertTrue(fails { guard(b).ensure(true) }.message!!.contains("WhatsApp Business"))
        val o = Fake().apply { root = "app.chatlens" }
        assertTrue(fails { guard(o).ensure(true) }.message!!.contains("ChatLens selbst"))
        val n = Fake().apply { root = null }
        assertTrue(fails { guard(n).ensure(true) }.message!!.contains("kein lesbares Fenster"))
    }

    @Test fun manualModeNeverLaunches() {
        val f = Fake().apply { root = "com.other.app" }
        val e = fails { guard(f).ensure(false, "Lesen") }
        assertEquals(0, f.launches)
        assertTrue(e.message!!.contains("holt ChatLens WhatsApp nie selbst nach vorn"))
        assertTrue(e.message!!.contains("nichts angetippt"))
    }

    @Test fun freshEventOfOtherAppCountsAgainstButStaleOneDoesNot() {
        val f = Fake()
        val g = guard(f)
        f.event = "com.other.app"; f.eventAt = f.clock
        assertFalse("frisches fremdes Ereignis", g.isForeground())
        f.clock += 2_000
        assertTrue("altes Ereignis zaehlt nicht mehr, Wurzel entscheidet", g.isForeground())
        f.event = "com.android.systemui"; f.eventAt = f.clock
        assertTrue("System-UI und Tastatur stoeren nicht", g.isForeground())
        f.root = "com.other.app"
        assertFalse("Wurzel ist massgeblich", g.isForeground())
    }

    @Test fun expectedPackageCanBeBusiness() = runBlocking {
        val f = Fake().apply { root = "com.whatsapp.w4b" }
        guard(f, "com.whatsapp.w4b").ensure(true)
        assertEquals(0, f.launches)
    }

    @Test fun systemUiInFrontIsDismissedBeforeLaunch() = runBlocking {
        val f = Fake().apply { root = "com.android.systemui" }
        f.onDismiss = { it.root = "com.whatsapp" }
        val log = ArrayList<String>()
        guard(f, log = log).ensure(true)
        assertEquals(1, f.dismisses)
        assertEquals(0, f.launches)
        assertTrue(log.any { it.contains("Systemoberflaeche com.android.systemui vorn") })
    }

    @Test fun launcherInFrontIsDismissedThenLaunched() = runBlocking {
        val f = Fake().apply { root = "com.miui.home" }
        f.onLaunch = { it.root = "com.whatsapp" }
        guard(f).ensure(true)
        assertTrue(f.dismisses >= 1)
        assertEquals(1, f.launches)
        assertEquals(0, f.homes)
    }

    @Test fun stuckSystemUiPressesHomeOnlyOnLastAttempt() {
        val f = Fake().apply { root = "com.android.systemui" }
        val e = fails { guard(f).ensure(true, "Setup") }
        assertEquals(3, f.dismisses)
        assertEquals(1, f.homes)
        assertEquals(3, f.launches)
        assertTrue(e.message!!.contains("3 Versuchen"))
    }

    @Test fun recoverAfterLogsTriggerAndBringsAppBack() = runBlocking {
        val f = Fake().apply { root = "com.android.systemui" }
        f.onDismiss = { it.root = "com.whatsapp" }
        val log = ArrayList<String>()
        guard(f, log = log).recoverAfter("Wisch 1/2 von (720,384) nach (720,1400)")
        assertTrue(log.any { it.startsWith("AUSLOESER: Nach Wisch 1/2 von (720,384) nach (720,1400)") && it.contains("com.android.systemui") })
        assertEquals(1, f.dismisses)
    }

    @Test fun recoverAfterIsSilentWhenWhatsAppIsFront() = runBlocking {
        val f = Fake()
        val log = ArrayList<String>()
        guard(f, log = log).recoverAfter("Wisch")
        assertEquals(0, f.dismisses)
        assertEquals(0, f.launches)
        assertTrue(log.none { it.startsWith("AUSLOESER") })
    }

    @Test fun systemSurfaceDetection() {
        val g = guard(Fake())
        assertTrue(g.isSystemSurface("com.android.systemui"))
        assertTrue(g.isSystemSurface("com.miui.home"))
        assertTrue(g.isSystemSurface("com.android.launcher3"))
        assertFalse(g.isSystemSurface("com.whatsapp"))
        assertFalse(g.isSystemSurface(null))
    }
}
