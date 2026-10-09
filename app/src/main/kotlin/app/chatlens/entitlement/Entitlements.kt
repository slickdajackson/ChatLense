package app.chatlens.entitlement

/**
 * Placeholder for Pro (from 0.3.0, without a purchase). The interface exists so features already go through one question
 * ("is this allowed?") and Play Billing can sit behind it later (MONETARISIERUNG.md 4.3). Until a purchase is built in, EVERYTHING is unlocked:
 * [DevEntitlements] reports [Tier.PRO_VERIFIED] and sets no limits. There is neither a paywall nor network access.
 */
enum class Tier { FREE, PRO_PENDING, PRO_VERIFIED, PRO_CACHED }

/** Features that can become Pro later (split in MONETARISIERUNG.md 2.2). Nothing decides on them today. */
enum class Feature { UNLIMITED_CHATS, LARGE_MEMORY, DISC, SELF_PROFILE, SELF_ANALYSIS, MULTI_MESSENGER }

interface Entitlements {
    val tier: Tier
    fun allows(f: Feature): Boolean
    /** Short sentence for the settings. */
    fun describe(): String
}

object DevEntitlements : Entitlements {
    override val tier = Tier.PRO_VERIFIED
    override fun allows(f: Feature) = true
    override fun describe() = "Entwicklungsstand: alle Funktionen sind freigeschaltet. Einen Kauf gibt es noch nicht."
}

/** Central point. The Play Billing provider will be plugged in here later (tests supply their own). */
object EntitlementProvider {
    @Volatile var current: Entitlements = DevEntitlements
}

/** Calculation rule for the later grace period (MONETARISIERUNG.md 4.3): an empty reply from Play switches to FREE only after 7 days, errors change nothing. */
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
