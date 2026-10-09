package app.chatlens.models

/** Gemessene oder angegebene Eigenschaften des Geraets. [unmetered] = null: unbekannt. */
data class DeviceInfo(
    val totalRamMb: Long,
    val availRamMb: Long,
    val freeStorageBytes: Long,
    val unmetered: Boolean?,
)

enum class Fit { GUT, KNAPP, ZU_WENIG }

enum class Focus { CLASSIFY, SUMMARY, PROFILE }

class Assessment(
    val fit: Fit,
    val ramNeededMb: Int,
    /** true = Schaetzung (keine Messung auf der Herstellerkarte). */
    val ramEstimated: Boolean,
    val storageOk: Boolean,
    /** Platz reicht zum Laden, aber ohne Reserve fuer den Laufzeit-Cache. */
    val storageTight: Boolean,
    val reasons: List<String>,
)

class Recommendation(val entry: ModelEntry, val assessment: Assessment, val reason: String)

class Limits(val maxTokens: Int, val contextChars: Int, val note: String)

class DownloadCheck(val allowed: Boolean, val blockReason: String?, val warnings: List<String>, val needsMeteredConfirm: Boolean)

/**
 * Empfehlungslogik. Reine Funktionen ohne Android-Abhaengigkeit, damit sie als JVM-Test laufen.
 * Die RAM-Werte sind Messungen der Herstellerkarten auf anderen Geraeten oder Schaetzungen, keine Messung auf diesem Geraet.
 */
object ModelAdvisor {
    const val LARGE_FILE_BYTES = 200L * 1_000_000L
    private const val MARGIN_BYTES = 100L * 1_000_000L

    /** Geplanter Arbeitsspeicherbedarf in MB fuer den Rechenweg [gpu]. Zweiter Wert: true = Schaetzung. */
    fun planRamMb(e: ModelEntry, gpu: Boolean): Pair<Int, Boolean> {
        val measured = if (gpu) e.ramGpuMb else e.ramCpuMb
        if (measured != null) return measured to false
        // Ohne Messung fuer diesen Weg: die andere Messung als Anhalt nehmen, sonst Dateigroesse mal 2 plus 1 GB.
        val other = if (gpu) e.ramCpuMb else e.ramGpuMb
        val estimate = (e.sizeMb * 2 + 1024).toInt()
        return (if (other != null) maxOf(other, estimate / 2) else estimate) to true
    }

    fun assess(e: ModelEntry, d: DeviceInfo, gpu: Boolean = e.prefersGpu, installed: Boolean = false, partBytes: Long = 0L): Assessment {
        val (need, est) = planRamMb(e, gpu)
        val reasons = ArrayList<String>()
        val fit = when {
            need <= d.availRamMb * 0.85 && need <= d.totalRamMb * 0.6 -> Fit.GUT
            need <= d.totalRamMb * 0.7 -> Fit.KNAPP
            else -> Fit.ZU_WENIG
        }
        when (fit) {
            Fit.GUT -> reasons.add("Arbeitsspeicher reicht (Bedarf etwa $need MB, frei ${d.availRamMb} MB).")
            Fit.KNAPP -> reasons.add("Arbeitsspeicher knapp: Bedarf etwa $need MB, frei ${d.availRamMb} MB. Andere Apps vorher schließen.")
            Fit.ZU_WENIG -> reasons.add("Arbeitsspeicher zu klein: Bedarf etwa $need MB bei ${d.totalRamMb} MB Gesamtspeicher.")
        }
        if (est) reasons.add("Bedarf ist eine Schätzung, auf der Herstellerkarte fehlt eine Messung.")
        val remaining = (e.sizeBytes - partBytes).coerceAtLeast(0L)
        val storageOk = installed || d.freeStorageBytes >= remaining + MARGIN_BYTES
        val storageTight = !installed && storageOk && d.freeStorageBytes < remaining + e.sizeBytes + MARGIN_BYTES
        if (!storageOk) reasons.add("Speicherplatz fehlt: ${mb(remaining + MARGIN_BYTES)} nötig, frei ${mb(d.freeStorageBytes)}.")
        else if (storageTight) reasons.add("Speicherplatz reicht zum Laden, aber kaum für den Laufzeit-Cache (kann bis etwa Dateigröße zusätzlich belegen).")
        return Assessment(fit, need, est, storageOk, storageTight, reasons)
    }

    private fun mb(bytes: Long): String = "${bytes / 1_000_000L} MB"

    private fun score(e: ModelEntry, focus: Focus): Int = when (focus) {
        Focus.CLASSIFY -> e.suit.classify
        Focus.SUMMARY -> e.suit.summary
        Focus.PROFILE -> e.suit.profile
    }

    /**
     * Rangfolge der Textmodelle fuer dieses Geraet: erst nach Passung (GUT vor KNAPP), nie ZU_WENIG oder ohne Speicher
     * (ausser installiert), dann stabil vor experimentell, dann Eignung fuer [focus], CPU-Rechenweg vor GPU (der ist erprobter),
     * dann kleinerer Bedarf. Gated und Vorschau-Eintraege nehmen nicht teil.
     */
    fun rank(models: List<ModelEntry>, d: DeviceInfo, focus: Focus = Focus.PROFILE, installedIds: Set<String> = emptySet()): List<Recommendation> {
        val cand = models.filter { it.kind == ModelKind.LLM && !it.gated && it.status != ModelStatus.PREVIEW }
        val scored = cand.map { e ->
            val inst = e.id in installedIds
            e to assess(e, d, e.prefersGpu, inst)
        }.filter { (_, a) -> a.fit != Fit.ZU_WENIG && a.storageOk }
        val sorted = scored.sortedWith(
            compareBy<Pair<ModelEntry, Assessment>> { (_, a) -> if (a.fit == Fit.GUT) 0 else 1 }
                .thenBy { (e, _) -> if (e.status == ModelStatus.EXPERIMENTAL) 1 else 0 }
                .thenByDescending { (e, _) -> score(e, focus) }
                .thenBy { (e, _) -> if (e.prefersGpu) 1 else 0 }
                .thenBy { (_, a) -> a.ramNeededMb },
        )
        return sorted.map { (e, a) -> Recommendation(e, a, reasonFor(e, a, focus)) }
    }

    private fun reasonFor(e: ModelEntry, a: Assessment, focus: Focus): String {
        val s = score(e, focus)
        val what = when (focus) { Focus.CLASSIFY -> "Klassifikation"; Focus.SUMMARY -> "Zusammenfassung"; Focus.PROFILE -> "Profile" }
        return "Passt zum Gerät (${a.fit.name.lowercase().replace('_', ' ')}), Eignung für $what $s von 3 (Einschätzung)."
    }

    /**
     * Empfehlung: Der Standard (Gemma 4 E4B) bleibt, solange er auf das Geraet passt (GUT oder KNAPP) und Platz da ist.
     * Sonst das beste passende Modell der Rangfolge; passt gar nichts, das kleinste mit Hinweis.
     */
    fun recommend(models: List<ModelEntry>, d: DeviceInfo, focus: Focus = Focus.PROFILE, installedIds: Set<String> = emptySet()): Recommendation? {
        val ranked = rank(models, d, focus, installedIds)
        val std = models.firstOrNull { it.id == ModelCatalog.DEFAULT_ID }
        if (std != null) {
            val hit = ranked.firstOrNull { it.entry.id == std.id }
            if (hit != null) return Recommendation(std, hit.assessment, "Standardmodell der App und passt zum Gerät (${hit.assessment.fit.name.lowercase()}).")
        }
        ranked.firstOrNull()?.let { return Recommendation(it.entry, it.assessment, "Standardmodell passt nicht aufs Gerät. " + it.reason) }
        val smallest = models.filter { it.kind == ModelKind.LLM && !it.gated && it.status != ModelStatus.PREVIEW }
            .minByOrNull { planRamMb(it, it.prefersGpu).first } ?: return null
        val a = assess(smallest, d, smallest.prefersGpu, smallest.id in installedIds)
        return Recommendation(smallest, a, "Kein Modell passt sicher. Das kleinste ist ${smallest.name}; es kann trotzdem scheitern.")
    }

    /**
     * Vergleichskandidat zum Standard: nur wenn der Standard empfohlen wird, der Kandidat (Katalogfeld comparison) aufs Geraet passt
     * (nicht ZU_WENIG, Speicher ok oder installiert) und kein Standard, gated oder Vorschau ist. Sonst null.
     */
    fun comparisonFor(models: List<ModelEntry>, d: DeviceInfo, installedIds: Set<String> = emptySet()): Recommendation? {
        val e = models.firstOrNull { it.comparison && it.kind == ModelKind.LLM && !it.gated && it.status != ModelStatus.PREVIEW && it.status != ModelStatus.STANDARD } ?: return null
        val a = assess(e, d, e.prefersGpu, e.id in installedIds)
        if (a.fit == Fit.ZU_WENIG || !a.storageOk) return null
        return Recommendation(e, a, "Zum Vergleich mit dem Standard: ${a.fit.name.lowercase()} auf diesem Gerät. Gleiche Aufgabe mit beiden Modellen laufen lassen und die Ergebnisse vergleichen.")
    }

    /** Pruefung vor dem Download: Speicher (hart), WLAN-Hinweis (bei grossen Dateien auf Mobilfunk oder unbekanntem Netz). */
    fun downloadCheck(e: ModelEntry, d: DeviceInfo, partBytes: Long): DownloadCheck {
        if (!e.canDownload) {
            val why = when {
                e.gated -> "Gated: Hugging Face verlangt Anmeldung und Zustimmung. Bitte im Browser öffnen und die Datei manuell importieren."
                e.kind == ModelKind.ASR -> "Dieses Modell ist in der App nicht zum Laden freigegeben. Nur Link."
                else -> "Kein direkter Download-Link im Katalog."
            }
            return DownloadCheck(false, why, emptyList(), false)
        }
        val remaining = (e.sizeBytes - partBytes).coerceAtLeast(0L)
        if (d.freeStorageBytes < remaining + MARGIN_BYTES) {
            return DownloadCheck(false, "Zu wenig freier Speicher: ${mb(remaining + MARGIN_BYTES)} nötig, frei ${mb(d.freeStorageBytes)}.", emptyList(), false)
        }
        val warns = ArrayList<String>()
        if (d.freeStorageBytes < remaining + e.sizeBytes + MARGIN_BYTES) {
            warns.add("Freier Speicher reicht für den Download, aber kaum für den Laufzeit-Cache des Modells.")
        }
        val metered = d.unmetered != true && e.sizeBytes >= LARGE_FILE_BYTES
        if (metered) warns.add("Große Datei (${mb(e.sizeBytes)}) ohne erkanntes WLAN. Das kann Datenvolumen kosten.")
        return DownloadCheck(true, null, warns, metered)
    }

    /** Grenzen fuer Token und Kontextzeichen, damit der Prompt ins Kontextfenster des Modells passt. Nur senken, wenn das Modell klein ist. */
    fun limitsFor(e: ModelEntry, defaultMaxTokens: Int = 8192, defaultContextChars: Int = 12_000): Limits {
        val assumed = e.contextTokens <= 0
        val ctx = if (assumed) 4096 else e.contextTokens
        if (ctx >= defaultMaxTokens) return Limits(defaultMaxTokens, defaultContextChars, "")
        val chars = ((ctx - 1100) * 3).coerceAtLeast(1500)
        val note = "Kontextfenster ${if (assumed) "(auf der Karte nicht angegeben, vorsichtig angenommen) " else ""}$ctx Token: " +
            "Verlauf im Prompt auf etwa $chars Zeichen begrenzt, also nur kurze Verläufe."
        return Limits(ctx, minOf(chars, defaultContextChars), note)
    }
}
