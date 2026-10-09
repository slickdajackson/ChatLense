package app.chatlens.models

import org.json.JSONArray
import org.json.JSONObject

enum class ModelKind { LLM, ASR }

enum class ModelFormat(val label: String) {
    LITERT_LM("LiteRT-LM (.litertlm)"),
    SHERPA_ONNX("sherpa-onnx (ONNX)"),
}

/** STANDARD = Vorgabe der App, STABLE = offizielle Datei, EXPERIMENTAL = wenig erprobt, PREVIEW = noch ohne Funktion in der App. */
enum class ModelStatus { STANDARD, STABLE, EXPERIMENTAL, PREVIEW }

/** Einschaetzung 0 bis 3 (0 = nicht zutreffend). Das ist keine Messung, sondern eine Schaetzung nach Modellgroesse und Typ. */
data class Suitability(val classify: Int, val summary: Int, val profile: Int)

/** Bereiche der Bewertung (unsere Einschaetzung, 1 bis 5, 5 ist am besten; beim Speicherbedarf heisst 5 wenig Bedarf). */
enum class RatingArea(val key: String, val label: String, val short: String) {
    GERMAN("german", "Qualität Deutsch", "Deutsch"),
    SPEED("speed", "Geschwindigkeit", "Tempo"),
    RAM("ram", "Speicherbedarf (RAM), 5 = wenig", "RAM"),
    ANALYSIS("analysis", "Zusammenfassen und Analyse", "Analyse"),
    DEVICE("device", "Eignung Xiaomi 15 Ultra", "Gerät"),
}

/** Punkte 1 bis 5 mit kurzer Begruendung. Nicht gemessen, solange nichts anderes dabeisteht. */
data class Rating(val score: Int, val why: String)

/** Eine Datei eines mehrteiligen Modells (Spracherkennung): Name im Repo, Groesse und SHA-256 je Datei. */
data class ModelFile(val name: String, val sizeBytes: Long, val sha256: String)

data class CatalogLink(val label: String, val url: String)

data class ModelEntry(
    val id: String,
    val name: String,
    val kind: ModelKind,
    val format: ModelFormat,
    val repo: String,
    val file: String?,
    /** Commit der Hugging-Face-Revision, auf die Groesse und Pruefsumme zutreffen. Der Download nutzt genau diese Revision. */
    val revision: String,
    val sizeBytes: Long,
    /** SHA-256 der Datei (LFS-Hash laut Hugging Face), leer wenn nicht verfuegbar (gated oder mehrteilig). */
    val sha256: String,
    val license: String,
    val gated: Boolean,
    val downloadable: Boolean,
    val status: ModelStatus,
    /** Kontextfenster in Token laut Karte, 0 = nicht angegeben. */
    val contextTokens: Int,
    val vision: Boolean,
    val ramCpuMb: Int?,
    val ramGpuMb: Int?,
    val ramSource: String,
    val preferredAccel: String,
    val suit: Suitability,
    val notes: String,
    val pageUrl: String,
    val links: List<CatalogLink>,
    /** Hinweis zur Deutsch-Unterstuetzung laut Karte, leer = keine Besonderheit. */
    val german: String = "",
    /** Vergleichskandidat zum Standard (wird neben dem Standard als Alternative gezeigt). */
    val comparison: Boolean = false,
    /** Dateien eines mehrteiligen Modells (Encoder, Decoder, Joiner, Tokens). Leer bei einteiligen Modellen (dann gilt file). */
    val files: List<ModelFile> = emptyList(),
    /** Unsere Bewertung je Bereich (Einschaetzung, nicht gemessen). Spracherkennung hat keinen Bereich Analyse. */
    val ratings: Map<RatingArea, Rating> = emptyMap(),
) {
    val multiFile: Boolean get() = files.isNotEmpty()

    /** Download-Link einer Teildatei (fest auf die Revision). */
    fun fileUrl(f: ModelFile): String = "https://huggingface.co/$repo/resolve/$revision/${f.name}"

    /** Ob die App dieses Modell selbst laden darf: frei, feste Revision, und jede Datei mit voller SHA-256. */
    val canDownload: Boolean
        get() = if (multiFile) downloadable && !gated && revision.isNotBlank() && files.all { it.sha256.length == 64 && it.sizeBytes > 0 }
        else downloadUrl != null

    val sizeMb: Long get() = sizeBytes / 1_000_000L

    /** Direkter Download-Link (fest auf die Revision), nur fuer freie, herunterladbare Dateien. */
    val downloadUrl: String?
        get() = if (downloadable && !gated && !file.isNullOrBlank() && revision.isNotBlank() && sha256.length == 64)
            "https://huggingface.co/$repo/resolve/$revision/$file" else null

    val prefersGpu: Boolean get() = preferredAccel.equals("gpu", ignoreCase = true)
}

object ModelCatalog {
    const val ASSET = "model-catalog.json"
    const val DEFAULT_ID = "gemma-4-e4b"

    class Parsed(val checkedAt: String, val source: String, val models: List<ModelEntry>, val ratingsNote: String = "") {
        fun byId(id: String): ModelEntry? = models.firstOrNull { it.id == id }
        val default: ModelEntry get() = byId(DEFAULT_ID) ?: models.first()
    }

    fun parse(json: String): Parsed {
        val root = JSONObject(json)
        val arr = root.getJSONArray("models")
        val list = ArrayList<ModelEntry>()
        for (i in 0 until arr.length()) list.add(entry(arr.getJSONObject(i)))
        return Parsed(root.optString("checkedAt"), root.optString("source"), list, root.optString("ratingsNote"))
    }

    private fun JSONObject.intOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null

    private fun entry(o: JSONObject): ModelEntry {
        val s = o.optJSONObject("suit")
        val links = ArrayList<CatalogLink>()
        o.optJSONArray("links")?.let { a: JSONArray -> for (i in 0 until a.length()) links.add(CatalogLink(a.getJSONObject(i).getString("label"), a.getJSONObject(i).getString("url"))) }
        return ModelEntry(
            id = o.getString("id"),
            name = o.getString("name"),
            kind = if (o.optString("kind") == "asr") ModelKind.ASR else ModelKind.LLM,
            format = if (o.optString("format") == "sherpa-onnx") ModelFormat.SHERPA_ONNX else ModelFormat.LITERT_LM,
            repo = o.getString("repo"),
            file = if (o.isNull("file")) null else o.optString("file").ifBlank { null },
            revision = o.optString("revision"),
            sizeBytes = o.optLong("sizeBytes"),
            sha256 = o.optString("sha256"),
            license = o.optString("license"),
            gated = o.optBoolean("gated"),
            downloadable = o.optBoolean("downloadable"),
            status = when (o.optString("status")) {
                "standard" -> ModelStatus.STANDARD
                "experimental" -> ModelStatus.EXPERIMENTAL
                "preview" -> ModelStatus.PREVIEW
                else -> ModelStatus.STABLE
            },
            contextTokens = o.optInt("contextTokens"),
            vision = o.optBoolean("vision"),
            ramCpuMb = o.intOrNull("ramCpuMb"),
            ramGpuMb = o.intOrNull("ramGpuMb"),
            ramSource = o.optString("ramSource"),
            preferredAccel = o.optString("preferredAccel", "cpu"),
            suit = Suitability(s?.optInt("classify") ?: 0, s?.optInt("summary") ?: 0, s?.optInt("profile") ?: 0),
            notes = o.optString("notes"),
            pageUrl = o.getString("pageUrl"),
            links = links,
            german = o.optString("german"),
            comparison = o.optBoolean("comparison"),
            ratings = o.optJSONObject("ratings")?.let { ro ->
                RatingArea.entries.mapNotNull { a -> ro.optJSONObject(a.key)?.let { x -> a to Rating(x.optInt("score"), x.optString("why")) } }.toMap()
            }.orEmpty(),
            files = o.optJSONArray("files")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { ModelFile(it.getString("name"), it.optLong("sizeBytes"), it.optString("sha256")) } } }.orEmpty(),
        )
    }

    fun load(ctx: android.content.Context): Parsed =
        parse(ctx.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
}
