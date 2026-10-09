package app.chatlens.agent

import app.chatlens.service.ChatAccessibilityService

/** Speist den [ForegroundGuard] vom Bedienungshilfe-Dienst. */
class ServiceForegroundProbe(private val svc: ChatAccessibilityService, private val launchPackage: String) : ForegroundProbe {
    override fun rootPackage(): String? = svc.foregroundPackage()
    override fun eventPackage(): String? = svc.lastEventPackage
    override fun eventAgeMs(): Long = if (svc.lastEventAt == 0L) Long.MAX_VALUE else System.currentTimeMillis() - svc.lastEventAt
    override fun activity(): String? = svc.lastEventClass
    override fun windowsSummary(): String = svc.windowsSummary()
    override fun launch(): Boolean = svc.launchApp(launchPackage)
    override fun dismissSystemUi(): Boolean = svc.dismissSystemUi()
    override fun goHome(): Boolean = svc.goHome()
}
