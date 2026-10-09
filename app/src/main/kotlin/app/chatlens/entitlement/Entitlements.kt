package app.chatlens.entitlement

/**
 * Platzhalter fuer Pro (ab 0.3.0, ohne Kauf). Die Schnittstelle gibt es, damit Funktionen schon heute ueber eine Frage laufen
 * ("darf das?") und spaeter Play Billing dahinter haengt (MONETARISIERUNG.md 4.3). Solange kein Kauf eingebaut ist, ist ALLES freigeschaltet:
 * [DevEntitlements] meldet [Tier.PRO_VERIFIED] und setzt keine Grenzen. Es gibt weder Bezahlschranke noch Netzwerkzugriff.
 */
enum class Tier { FREE, PRO_PENDING, PRO_VERIFIED, PRO_CACHED }

/** Funktionen, die spaeter Pro sein koennen (Aufteilung in MONETARISIERUNG.md 2.2). Heute entscheidet nichts daran. */
enum class Feature { UNLIMITED_CHATS, LARGE_MEMORY, DISC, SELF_PROFILE, SELF_ANALYSIS, MULTI_MESSENGER }

interface Entitlements {
    val tier: Tier
    fun allows(f: Feature): Boolean
    /** Kurzer Satz fuer die Einstellungen. */
    fun describe(): String
}

object DevEntitlements : Entitlements {
    override val tier = Tier.PRO_VERIFIED
    override fun allows(f: Feature) = true
    override fun describe() = "Entwicklungsstand: alle Funktionen sind freigeschaltet. Einen Kauf gibt es noch nicht."
}

/** Zentrale Stelle. Spaeter wird hier der Play-Billing-Anbieter eingesetzt (Tests setzen eigene). */
object EntitlementProvider {
    @Volatile var current: Entitlements = DevEntitlements
}

/** Rechenregel fuer die spaetere Schonfrist (MONETARISIERUNG.md 4.3): leere Antwort von Play setzt erst nach 7 Tagen auf FREE, Fehler aendern nichts. */
object EntitlementRules {
    const val GRACE_MS = 7L * 24 * 60 * 60 * 1000

    fun afterCheck(prev: Tier, lastVerifiedMs: Long, nowMs: Long, result: CheckResult): Tier = when (result) {
        CheckResult.PURCHASED -> Tier.PRO_VERIFIED
        CheckResult.PENDING -> if (prev == Tier.FREE) Tier.PRO_PENDING else prev
        CheckResult.ERROR_OR_OFFLINE -> prev
        CheckResult.NO_PURCHASE -> if (prev == Tier.FREE) Tier.FREE else if (nowMs - lastVerifiedMs >= GRACE_MS) Tier.FREE else prev
    }

    enum class CheckResult { PURCHASED, PENDING, NO_PURCHASE, ERROR_OR_OFFLINE }
}
