package app.chatlens.llm

/**
 * Gemerkter Zustand je Modell und Beschleunigung. [okLevel]: zuletzt erfolgreich geladene Stufe (0 = unbekannt).
 * [failedLevel]: kleinste Stufe, die zuletzt scheiterte (0 = keine). [probedAt]: wann zuletzt eine hoehere Stufe neu getestet wurde.
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

/** Ergebnis der Planung: Stufen in der Reihenfolge der Versuche, dazu eine kurze Begruendung fuers Log. */
class ContextPlan(val candidates: List<Int>, val reason: String)

/**
 * Waehlt die Kontextstufe (Token) fuer das lokale Modell. Reine Logik (JVM-testbar).
 * Stufen 32768, 16384, 8192, 4096; nach oben begrenzt durch die Modellobergrenze, durch den verfuegbaren Arbeitsspeicher
 * (Heuristik fuer den KV-Cache, KEINE Messung) und, bei manueller Wahl, durch diese Wahl. Bei Fehlern geht es Stufe fuer Stufe nach unten.
 */
object ContextPlanner {
    val LEVELS = listOf(32768, 16384, 8192, 4096)

    /** Annahme: KV-Cache in MB je Token (Hypothese, nicht gemessen; vorsichtig hoch angesetzt). */
    const val KV_MB_PER_TOKEN = 0.10

    /** Basis der Messung auf der Herstellerkarte war Kontext 2048. */
    const val BASE_CONTEXT = 2048

    /** Eine hoehere Stufe wird hoechstens alle 7 Tage neu getestet. */
    const val PROBE_INTERVAL_MS = 7L * 24 * 3600 * 1000

    /** Stufen, die das Modell kann: alle ab [modelCtx] abwaerts; bei kleinerem Modell genau seine Obergrenze; unbekannt (<= 0) gilt 4096. */
    fun modelLevels(modelCtx: Int): List<Int> {
        val ctx = if (modelCtx <= 0) 4096 else modelCtx
        val l = LEVELS.filter { it <= ctx }
        return l.ifEmpty { listOf(ctx) }
    }

    /** Geschaetzter Arbeitsspeicherbedarf in MB fuer die Stufe. */
    fun neededMb(baseRamMb: Int, level: Int): Double = baseRamMb + (level - BASE_CONTEXT).coerceAtLeast(0) * KV_MB_PER_TOKEN

    /** Stufen, deren geschaetzter Bedarf in den freien Speicher passt (85 Prozent des freien, 70 Prozent des gesamten Arbeitsspeichers). */
    fun ramAllowed(levels: List<Int>, baseRamMb: Int, availMb: Long, totalMb: Long): List<Int> =
        levels.filter { neededMb(baseRamMb, it) <= availMb * 0.85 && neededMb(baseRamMb, it) <= totalMb * 0.7 }

    /**
     * @param manual 0 = automatisch, sonst die vom Nutzer gewaehlte Obergrenze
     * @param availMb freier Arbeitsspeicher, negativ = unbekannt (dann keine RAM-Begrenzung)
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
            // Hoehere Stufen: nur neu testen, wenn sie nicht zuletzt scheiterten oder der Testabstand um ist
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

    /** Zeichenbudget fuer den Verlauf im Prompt: konservativ 3 Zeichen je Token (Deutsch), abzueglich Reserve fuer Anweisung und Antwort. */
    fun charsFor(level: Int, reserveTokens: Int = 1500): Int = ((level - reserveTokens) * 3).coerceAtLeast(1500)

    /** Einstellung laden: Altwert 8192 aus 0.2.8 (frueherer Standard) wird einmalig zu "automatisch". */
    fun loadTokens(raw: Int, migrated: Boolean): Int = if (raw <= 0 || (!migrated && raw == 8192)) 0 else raw.coerceIn(1024, 32768)

    /** Altwert 12000 aus 0.2.8 (frueherer Standard) wird einmalig zu "automatisch". */
    fun loadChars(raw: Int, migrated: Boolean): Int = if (raw <= 0 || (!migrated && raw == 12_000)) 0 else raw.coerceIn(500, 100_000)

    /** Zeichenbudget des Verlaufs: eingestellter Wert, bei 0 aus der genutzten Stufe. */
    fun effectiveChars(setting: Int, level: Int): Int = if (setting > 0) setting else charsFor(level)

    /** Neue Merkung nach einem Ladeversuch. */
    fun afterSuccess(r: RememberedLevel, level: Int, topCandidate: Int, now: Long): RememberedLevel =
        RememberedLevel(okLevel = level, failedLevel = if (level >= topCandidate) 0 else r.failedLevel, probedAt = now)

    fun afterFailure(r: RememberedLevel, level: Int, now: Long): RememberedLevel =
        RememberedLevel(okLevel = if (r.okLevel >= level) 0 else r.okLevel, failedLevel = if (r.failedLevel == 0) level else minOf(r.failedLevel, level), probedAt = now)
}
