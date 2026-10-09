package app.chatlens.llm

/**
 * Remembered state per model and acceleration. [okLevel]: level last loaded successfully (0 = unknown).
 * [failedLevel]: smallest level that failed last time (0 = none). [probedAt]: when a higher level was last tried again.
 */
data class RememberedLevel(val okLevel: Int = 0, val failedLevel: Int = 0, val probedAt: Long = 0L) {
    fun encode() = "$okLevel,$failedLevel,$probedAt"

    companion object {
        fun decode(s: String?): RememberedLevel {
            val p = s?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: return RememberedLevel()
            return if (p.size == 3) RememberedLevel(p[0].toInt(), p[1].toInt(), p[2]) else RememberedLevel()
        }
    }
}

/** Planning result: levels in the order they will be tried, plus a short reason for the log. */
class ContextPlan(val candidates: List<Int>, val reason: String)

/**
 * Chooses the context level (tokens) for the local model. Pure logic (JVM-testable).
 * Levels 32768, 16384, 8192, 4096, capped above by the model limit, by available memory
 * (a heuristic for the KV cache, NOT a measurement), and, on a manual choice, by that choice. On errors it steps down one level at a time.
 */
object ContextPlanner {
    val LEVELS = listOf(32768, 16384, 8192, 4096)

    /** Assumption: KV cache in MB per token (a hypothesis, not measured; set cautiously high). */
    const val KV_MB_PER_TOKEN = 0.10

    /** The measurement on the vendor card was based on a context of 2048. */
    const val BASE_CONTEXT = 2048

    /** A higher level is retested at most every 7 days. */
    const val PROBE_INTERVAL_MS = 7L * 24 * 3600 * 1000

    /** Levels the model can do: every level from [modelCtx] downward. A smaller model uses exactly its limit. Unknown (<= 0) means 4096. */
    fun modelLevels(modelCtx: Int): List<Int> {
        val ctx = if (modelCtx <= 0) 4096 else modelCtx
        val l = LEVELS.filter { it <= ctx }
        return l.ifEmpty { listOf(ctx) }
    }

    /** Estimated memory need in MB for the level. */
    fun neededMb(baseRamMb: Int, level: Int): Double = baseRamMb + (level - BASE_CONTEXT).coerceAtLeast(0) * KV_MB_PER_TOKEN

    /** Levels whose estimated need fits in free memory (85 percent of free, 70 percent of total memory). */
    fun ramAllowed(levels: List<Int>, baseRamMb: Int, availMb: Long, totalMb: Long): List<Int> =
        levels.filter { neededMb(baseRamMb, it) <= availMb * 0.85 && neededMb(baseRamMb, it) <= totalMb * 0.7 }

    /**
     * @param manual 0 = "automatisch", otherwise the cap chosen by the user
     * @param availMb free memory, negative = unknown (then no RAM cap)
     */
    fun plan(modelCtx: Int, manual: Int, baseRamMb: Int, availMb: Long, totalMb: Long, remembered: RememberedLevel, now: Long): ContextPlan {
        val model = modelLevels(modelCtx)
        if (manual > 0) {
            val c = model.filter { it <= manual }.ifEmpty { listOf(model.last()) }
            return ContextPlan(c, "manuelle Wahl $manual Token (Rueckfall nach unten bei Fehlern)")
        }
        var cand = if (availMb >= 0 && totalMb > 0) ramAllowed(model, baseRamMb, availMb, totalMb) else model
        val ramNote = if (cand.size < model.size) ", RAM-Heuristik begrenzt auf ${cand.firstOrNull() ?: model.last()}" else ""
        if (cand.isEmpty()) cand = listOf(model.last())
        var reason = "automatisch, Modellobergrenze ${model.first()}$ramNote"
        val ok = remembered.okLevel
        if (ok > 0 && ok in cand) {
            val higher = cand.filter { it > ok }
            val probeDue = now - remembered.probedAt >= PROBE_INTERVAL_MS
            // Higher levels: retest only if they did not fail last time, or the retest interval has elapsed
            val tryHigher = higher.filter { remembered.failedLevel == 0 || it < remembered.failedLevel || probeDue }
            cand = if (higher.isNotEmpty() && probeDue && tryHigher.isNotEmpty()) {
                reason += ", gemerkte Stufe $ok, hoehere wird neu getestet"
                tryHigher + cand.filter { it <= ok }
            } else {
                reason += ", gemerkte Stufe $ok zuerst"
                cand.filter { it <= ok }
            }
        }
        return ContextPlan(cand, reason)
    }

    /** Character budget for the transcript in the prompt: a conservative 3 characters per token (German), minus a reserve for the instruction and the reply. */
    fun charsFor(level: Int, reserveTokens: Int = 1500): Int = ((level - reserveTokens) * 3).coerceAtLeast(1500)

    /** Load the setting: the old value 8192 from 0.2.8 (the previous default) becomes "automatisch" once. */
    fun loadTokens(raw: Int, migrated: Boolean): Int = if (raw <= 0 || (!migrated && raw == 8192)) 0 else raw.coerceIn(1024, 32768)

    /** Old value 12000 from 0.2.8 (the previous default) becomes "automatisch" once. */
    fun loadChars(raw: Int, migrated: Boolean): Int = if (raw <= 0 || (!migrated && raw == 12_000)) 0 else raw.coerceIn(500, 100_000)

    /** Character budget of the transcript: the configured value, or, at 0, derived from the level in use. */
    fun effectiveChars(setting: Int, level: Int): Int = if (setting > 0) setting else charsFor(level)

    /** New remembered state after a load attempt. */
    fun afterSuccess(r: RememberedLevel, level: Int, topCandidate: Int, now: Long): RememberedLevel =
        RememberedLevel(okLevel = level, failedLevel = if (level >= topCandidate) 0 else r.failedLevel, probedAt = now)

    fun afterFailure(r: RememberedLevel, level: Int, now: Long): RememberedLevel =
        RememberedLevel(okLevel = if (r.okLevel >= level) 0 else r.okLevel, failedLevel = if (r.failedLevel == 0) level else minOf(r.failedLevel, level), probedAt = now)
}
