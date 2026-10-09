package app.chatlens

import app.chatlens.agent.ErrorText
import app.chatlens.data.BackendChoice
import app.chatlens.data.BackendPolicy
import app.chatlens.entitlement.DevEntitlements
import app.chatlens.entitlement.EntitlementProvider
import app.chatlens.entitlement.EntitlementRules
import app.chatlens.entitlement.EntitlementRules.CheckResult
import app.chatlens.entitlement.Tier
import app.chatlens.messenger.AdapterStatus
import app.chatlens.messenger.MessengerRegistry
import app.chatlens.messenger.SignalAdapter
import app.chatlens.messenger.TelegramAdapter
import app.chatlens.messenger.WhatsAppAdapter
import app.chatlens.profile.SelectorProfile
import app.chatlens.service.OverlayGeometry
import app.chatlens.service.OverlayGeometry.Area
import app.chatlens.service.OverlayGeometry.Side
import app.chatlens.ui.privacyText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Logic and rules of version 0.3.0 (no device). */
class V030LogicTest {
    private val area = Area(width = 1080, height = 2400, insetTop = 100, insetBottom = 120)

    // --- Overlay geometry (O1 to O4) ---

    @Test fun clampKeepsWindowInsideVisibleArea() {
        assertEquals(0 to 100, OverlayGeometry.clamp(-50, -50, 120, 120, area))
        assertEquals(960 to 2160, OverlayGeometry.clamp(5000, 5000, 120, 120, area))
        assertEquals(500 to 800, OverlayGeometry.clamp(500, 800, 120, 120, area))
    }

    @Test fun windowLargerThanAreaUsesTopLeft() {
        val small = Area(100, 100)
        assertEquals(0 to 0, OverlayGeometry.clamp(40, 40, 300, 300, small))
    }

    @Test fun snapGoesToNearerSideByWindowCenter() {
        assertEquals(Side.LEFT, OverlayGeometry.snapSide(100, 120, area))
        assertEquals(Side.RIGHT, OverlayGeometry.snapSide(700, 120, area))
        assertEquals(Side.RIGHT, OverlayGeometry.snapSide(480, 120, area)) // center 540 equals the area center: right
    }

    @Test fun xAtSideHonoursMargin() {
        assertEquals(8, OverlayGeometry.xAtSide(Side.LEFT, 120, area, 8))
        assertEquals(1080 - 120 - 8, OverlayGeometry.xAtSide(Side.RIGHT, 120, area, 8))
    }

    @Test fun yFractionSurvivesRotationRoundTrip() {
        val f = OverlayGeometry.yFraction(800, 120, area)
        val back = OverlayGeometry.yFromFraction(f, 120, area)
        assertTrue(kotlin.math.abs(back - 800) <= 1)
        val land = Area(2400, 1080, insetTop = 60)
        val y = OverlayGeometry.yFromFraction(f, 120, land)
        assertTrue(y in land.top..(land.bottom - 120))
        assertEquals(area.top, OverlayGeometry.yFromFraction(-1f, 120, area))
        assertEquals(area.bottom - 120, OverlayGeometry.yFromFraction(2f, 120, area))
    }

    @Test fun ringMovesInwardAtTheEdge() {
        val (rx, ry) = OverlayGeometry.ringOrigin(dotX = 0, dotY = 100, dot = 120, ring = 400, a = area)
        assertEquals(0, rx)
        assertEquals(100, ry)
        val (cx, cy) = OverlayGeometry.ringOrigin(dotX = 480, dotY = 1000, dot = 120, ring = 400, a = area)
        assertEquals(480 - 140, cx)
        assertEquals(1000 - 140, cy)
    }

    // --- Messenger adapters (section 26) ---

    @Test fun onlyWhatsAppIsActive() {
        assertEquals(AdapterStatus.ACTIVE, WhatsAppAdapter.status)
        assertEquals(AdapterStatus.PREPARED, SignalAdapter.status)
        assertEquals(AdapterStatus.PREPARED, TelegramAdapter.status)
        assertEquals(setOf("whatsapp"), MessengerRegistry.enabledIds("whatsapp,signal,telegram"))
    }

    @Test fun enabledFallsBackToWhatsApp() {
        assertEquals(setOf("whatsapp"), MessengerRegistry.enabledIds(""))
        assertEquals(setOf("whatsapp"), MessengerRegistry.enabledIds("unbekannt"))
        assertEquals(setOf("com.whatsapp"), MessengerRegistry.enabledPackages("signal"))
    }

    @Test fun preparedAdaptersCannotBeSwitchedOn() {
        assertEquals("whatsapp", MessengerRegistry.toggled("whatsapp", "signal", true))
        assertEquals("whatsapp", MessengerRegistry.toggled("whatsapp", "telegram", true))
        assertEquals("whatsapp", MessengerRegistry.toggled("whatsapp", "whatsapp", false)) // the last active one stays
    }

    @Test fun packageAllowedOnlyForEnabledMessengers() {
        assertTrue(MessengerRegistry.isPackageAllowed("com.whatsapp", "whatsapp"))
        assertFalse(MessengerRegistry.isPackageAllowed("org.telegram.messenger", "whatsapp"))
        assertFalse(MessengerRegistry.isPackageAllowed("org.thoughtcrime.securesms", "whatsapp"))
        assertFalse(MessengerRegistry.isPackageAllowed(null, "whatsapp"))
        assertEquals(TelegramAdapter, MessengerRegistry.byPackage("org.telegram.messenger.web"))
    }

    @Test fun memoryDirectoriesAreSeparatedAndWhatsAppKeepsTheOldOne() {
        assertEquals("memory", WhatsAppAdapter.memoryDirName)
        assertEquals("memory-signal", SignalAdapter.memoryDirName)
        assertEquals("memory-telegram", TelegramAdapter.memoryDirName)
        assertEquals(3, MessengerRegistry.all.map { it.memoryDirName }.toSet().size)
    }

    @Test fun preparedAdaptersNeverAllowApiMode() {
        assertFalse(SignalAdapter.apiModeAllowed)
        assertFalse(TelegramAdapter.apiModeAllowed)
    }

    @Test fun preparedProfilesParseAndStayUncalibrated() {
        val sig = SelectorProfile.parse(File("src/main/assets/profiles/signal.json").readText())
        val tg = SelectorProfile.parse(File("src/main/assets/profiles/telegram.json").readText())
        assertEquals("org.thoughtcrime.securesms", sig.packageName)
        assertEquals("org.telegram.messenger", tg.packageName)
        assertEquals("NOT_CALIBRATED", sig.calibrationStatus)
        assertEquals("NOT_CALIBRATED", tg.calibrationStatus)
        assertTrue(sig.messageTextIds.any { it.endsWith("conversation_item_body") })
        assertTrue("Telegram hat keine Ressourcen-IDs", tg.messageTextIds.isEmpty())
    }

    // --- Entitlement (MONETIZATION 4.3) ---

    @Test fun devEntitlementsAllowEverythingAndDescribeNoPurchase() {
        assertEquals(Tier.PRO_VERIFIED, DevEntitlements.tier)
        assertTrue(entitlementFeatures().all { DevEntitlements.allows(it) })
        assertTrue(EntitlementProvider.current.describe().contains("Kauf"))
    }

    private fun entitlementFeatures() = app.chatlens.entitlement.Feature.entries

    @Test fun graceRuleKeepsProForSevenDaysWithoutConfirmation() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(Tier.PRO_VERIFIED, EntitlementRules.afterCheck(Tier.PRO_VERIFIED, 0, 3 * day, CheckResult.NO_PURCHASE))
        assertEquals(Tier.FREE, EntitlementRules.afterCheck(Tier.PRO_VERIFIED, 0, 7 * day, CheckResult.NO_PURCHASE))
        assertEquals(Tier.PRO_VERIFIED, EntitlementRules.afterCheck(Tier.PRO_VERIFIED, 0, 30 * day, CheckResult.ERROR_OR_OFFLINE))
        assertEquals(Tier.PRO_VERIFIED, EntitlementRules.afterCheck(Tier.FREE, 0, 0, CheckResult.PURCHASED))
        assertEquals(Tier.PRO_PENDING, EntitlementRules.afterCheck(Tier.FREE, 0, 0, CheckResult.PENDING))
        assertEquals(Tier.FREE, EntitlementRules.afterCheck(Tier.FREE, 0, 100 * day, CheckResult.NO_PURCHASE))
    }

    // --- Flavors (Play compliance) ---

    @Test fun playPolicyRemovesApiBackend() {
        assertEquals(BackendChoice.EXTRACT_ONLY, BackendPolicy.effective(BackendChoice.API, allowed = false))
        assertEquals(BackendChoice.API, BackendPolicy.effective(BackendChoice.API, allowed = true))
        assertEquals(BackendChoice.LOCAL, BackendPolicy.effective(BackendChoice.LOCAL, allowed = false))
        assertFalse(BackendChoice.API in BackendPolicy.choices(allowed = false))
        assertTrue(BackendChoice.API in BackendPolicy.choices(allowed = true))
    }

    @Test fun privacyTextMatchesFlavor() {
        assertTrue(privacyText(true).contains("API"))
        assertFalse(privacyText(false).contains("API"))
        assertTrue(privacyText(false).startsWith("Datenschutz in Kürze"))
        assertTrue("Satzanfang gross", privacyText(false).contains("Die Haushaltsausnahme"))
    }

    @Test fun privacyAssetExistsAndNamesTheCoreFacts() {
        val t = File("src/main/assets/datenschutz.md").readText()
        for (k in listOf("Verantwortlicher", "Bedienungshilfe", "Löschen", "AES-GCM", "sendet nie")) assertTrue("missing: $k", t.contains(k))
    }

    // --- Rule: no LLM result triggers accessibility actions (MONETIZATION phase 0) ---

    @Test fun llmPackageDoesNotReachAccessibilityClasses() {
        val dir = File("src/main/kotlin/app/chatlens/llm")
        assertTrue(dir.isDirectory)
        val bad = listOf("app.chatlens.service", "app.chatlens.assist", "app.chatlens.accessibility", "AccessibilityService", "GestureDescription", "performAction", "dispatchGesture")
        val offenders = dir.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().filter { l -> !l.trimStart().startsWith("//") && !l.trimStart().startsWith("*") && bad.any { l.contains(it) } }.map { f.name + ": " + it.trim() }
        }.toList()
        assertTrue("llm darf keine Bedienungshilfe-Klassen kennen: $offenders", offenders.isEmpty())
    }

    @Test fun setTextOnlyInKnownPlaces() {
        // ACTION_SET_TEXT appears only in the navigator (search field), in the reply actions (insert after confirmation), and as a note in MainActivity.
        val root = File("src/main/kotlin/app/chatlens")
        val hits = root.walkTopDown().filter { it.extension == "kt" && it.readText().contains("ACTION_SET_TEXT") }.map { it.name }.toSet()
        assertTrue("ACTION_SET_TEXT nur im Eintragen: $hits", hits.all { it in setOf("WhatsAppNavigator.kt", "ReplyActions.kt", "MainActivity.kt") })
    }

    // --- Error texts (N3) ---

    @Test fun errorTextHidesClassNames() {
        val all = listOf(OutOfMemoryError(), SecurityException("x"), java.io.FileNotFoundException("f"), java.io.IOException("io"),
            java.util.concurrent.TimeoutException("t"), IllegalStateException("state"), RuntimeException("boom"))
        for (e in all) {
            val t = ErrorText.friendly(e)
            assertNotNull(t)
            assertFalse("keine Klassennamen: $t", t.contains("Exception") || t.contains("Error") || t.contains("java."))
            assertTrue(t.endsWith("."))
        }
    }

    // --- Manifest ---

    @Test fun manifestUsesPlaceholderForCleartext() {
        val m = File("src/main/AndroidManifest.xml").readText()
        assertTrue(m.contains("usesCleartextTraffic=\"\${cleartext}\""))
        assertFalse(m.contains("isAccessibilityTool=\"true\""))
    }
}
