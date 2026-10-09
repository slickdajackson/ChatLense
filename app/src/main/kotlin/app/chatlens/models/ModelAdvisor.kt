package app.chatlens.models

/** Measured or stated properties of the device. [unmetered] = null: unknown. */
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
    /** true = estimate (no measurement on the vendor card). */
    val ramEstimated: Boolean,
    val storageOk: Boolean,
    /** Space is enough to download, but without a reserve for the runtime cache. */
    val storageTight: Boolean,
    val reasons: List<String>,
)

class Recommendation(val entry: ModelEntry, val assessment: Assessment, val reason: String)

class Limits(val maxTokens: Int, val contextChars: Int, val note: String)

class DownloadCheck(val allowed: Boolean, val blockReason: String?, val warnings: List<String>, val needsMeteredConfirm: Boolean)

/**
 * Recommendation logic. Pure functions without an Android dependency, so they run as a JVM test.
 * The RAM figures are measurements from vendor cards on other devices, or estimates, not a measurement on this device.
 */
object ModelAdvisor {
    const val LARGE_FILE_BYTES = 200L * 1_000_000L
    private const val MARGIN_BYTES = 100L * 1_000_000L

    /** Planned memory need in MB for the compute path [gpu]. Second value: true = estimate. */
    fun planRamMb(e: ModelEntry, gpu: Boolean): Pair<Int, Boolean> {
        val measured = if (gpu) e.ramGpuMb else e.ramCpuMb
        if (measured != null) return measured to false
        // No measurement for this path: use the other measurement as a guide, otherwise file size times 2 plus 1 GB.
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
     * Ranking of text models for this device: first by fit (GUT before KNAPP), never ZU_WENIG or without storage
     * (unless installed), then stable before experimental, then suitability for [focus], CPU path before GPU (that one is better tried),
     * then smaller need. Gated and preview entries do not take part.
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
     * Recommendation: the default (Gemma 4 E4B) stays as long as it fits the device (GUT or KNAPP) and there is space.
     * Otherwise the best fitting model from the ranking. If nothing fits, the smallest one, with a note.
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
     * Comparison candidate next to the default: only when the default is recommended, the candidate (catalog field comparison) fits the device
     * (not ZU_WENIG, storage ok or installed), and it is not the default, gated, or a preview. Otherwise null.
     */
    fun comparisonFor(models: List<ModelEntry>, d: DeviceInfo, installedIds: Set<String> = emptySet()): Recommendation? {
        val e = models.firstOrNull { it.comparison && it.kind == ModelKind.LLM && !it.gated && it.status != ModelStatus.PREVIEW && it.status != ModelStatus.STANDARD } ?: return null
        val a = assess(e, d, e.prefersGpu, e.id in installedIds)
        if (a.fit == Fit.ZU_WENIG || !a.storageOk) return null
        return Recommendation(e, a, "Zum Vergleich mit dem Standard: ${a.fit.name.lowercase()} auf diesem Gerät. Gleiche Aufgabe mit beiden Modellen laufen lassen und die Ergebnisse vergleichen.")
    }

    /** Check before download: storage (hard), Wi-Fi warning (for large files on mobile data or an unknown network). */
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

    /** Limits for tokens and context characters so the prompt fits the model's context window. Lower them only when the model is small. */
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
