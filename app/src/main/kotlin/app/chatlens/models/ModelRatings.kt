package app.chatlens.models

/** Sorting and total points for the compact model list. Pure logic. */
object ModelRatings {
    const val MIN = 1
    const val MAX = 5

    fun valid(r: Rating) = r.score in MIN..MAX && r.why.isNotBlank()

    /** Mean of the areas that are present, 0 if none. */
    fun average(e: ModelEntry): Double = if (e.ratings.isEmpty()) 0.0 else e.ratings.values.sumOf { it.score } / e.ratings.size.toDouble()

    /**
     * Order of the list. [byRecommendation]: an order already computed (recommendation first). It is kept when [area] == null.
     * With [area]: descending by points in that area, ties by the recommendation order, models without a score at the end.
     */
    fun order(models: List<ModelEntry>, byRecommendation: List<ModelEntry>, area: RatingArea?): List<ModelEntry> {
        val base = byRecommendation.ifEmpty { models }
        if (area == null) return base
        return base.withIndex().sortedWith(
            compareByDescending<IndexedValue<ModelEntry>> { it.value.ratings[area]?.score ?: -1 }.thenBy { it.index },
        ).map { it.value }
    }

    /** Row status: "Aktiv", "Geladen", "Lädt", "Nicht geladen". */
    enum class RowState(val label: String) { ACTIVE("Aktiv"), LOADED("Geladen"), LOADING("Lädt"), NOT_LOADED("Nicht geladen") }

    fun rowState(active: Boolean, installed: Boolean, dl: DlStatus?): RowState = when {
        active -> RowState.ACTIVE
        installed -> RowState.LOADED
        dl == DlStatus.RUNNING || dl == DlStatus.VERIFYING -> RowState.LOADING
        else -> RowState.NOT_LOADED
    }
}
