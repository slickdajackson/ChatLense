package app.chatlens.models

/** Sortierung und Gesamtpunkte fuer die kompakte Modellliste. Reine Logik. */
object ModelRatings {
    const val MIN = 1
    const val MAX = 5

    fun valid(r: Rating) = r.score in MIN..MAX && r.why.isNotBlank()

    /** Mittel der vorhandenen Bereiche, 0 wenn keine. */
    fun average(e: ModelEntry): Double = if (e.ratings.isEmpty()) 0.0 else e.ratings.values.sumOf { it.score } / e.ratings.size.toDouble()

    /**
     * Reihenfolge der Liste. [byRecommendation]: bereits berechnete Reihenfolge (Empfehlung zuerst); sie bleibt bei [area] == null erhalten.
     * Mit [area]: absteigend nach Punkten dieses Bereichs, Gleichstand nach der Empfehlungsreihenfolge, Modelle ohne Wert am Ende.
     */
    fun order(models: List<ModelEntry>, byRecommendation: List<ModelEntry>, area: RatingArea?): List<ModelEntry> {
        val base = byRecommendation.ifEmpty { models }
        if (area == null) return base
        return base.withIndex().sortedWith(
            compareByDescending<IndexedValue<ModelEntry>> { it.value.ratings[area]?.score ?: -1 }.thenBy { it.index },
        ).map { it.value }
    }

    /** Status der Zeile: Aktiv, Geladen, Laedt, Nicht geladen. */
    enum class RowState(val label: String) { ACTIVE("Aktiv"), LOADED("Geladen"), LOADING("Lädt"), NOT_LOADED("Nicht geladen") }

    fun rowState(active: Boolean, installed: Boolean, dl: DlStatus?): RowState = when {
        active -> RowState.ACTIVE
        installed -> RowState.LOADED
        dl == DlStatus.RUNNING || dl == DlStatus.VERIFYING -> RowState.LOADING
        else -> RowState.NOT_LOADED
    }
}
