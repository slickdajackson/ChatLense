package app.chatlens.memory

import org.json.JSONObject

/** Wie belastbar eine DISC-Einschaetzung ist. ZU_WENIG: nicht genug Text, es werden keine Anteile gezeigt. */
enum class DiscLevel(val label: String) { ZU_WENIG("zu wenig Daten"), NIEDRIG("niedrig"), MITTEL("mittel"), HOCH("hoch") }

/**
 * Vorsichtige DISC-Einschaetzung aus Chattext (D Dominanz, I Initiative, S Stetigkeit, C Gewissenhaftigkeit), Anteile in Prozent (Summe 100).
 * Keine Diagnose und kein Test: nur eine Lesart des Schreibstils in diesem Chat. [basis] ist die Zahl der Nachrichten, auf der sie beruht.
 */
data class DiscProfile(
    val d: Int = 0, val i: Int = 0, val s: Int = 0, val c: Int = 0,
    val confidence: DiscLevel = DiscLevel.ZU_WENIG,
    val reason: String = "",
    val basis: Int = 0,
    val updatedAt: Long = 0L,
) {
    val insufficient: Boolean get() = confidence == DiscLevel.ZU_WENIG || d + i + s + c == 0

    /** Kompakte Zeile fuer Leiste und Prompt. */
    fun line(): String = if (insufficient) "DISC: zu wenig Daten" else "DISC: D $d, I $i, S $s, C $c (Konfidenz ${confidence.label})"
}

object Disc {
    const val MIN_MESSAGES = 20
    const val DISCLAIMER = "Vorsichtige Einschätzung aus Chattext, keine Diagnose und kein Persönlichkeitstest. Sie sagt nichts Gesichertes über die Person."

    /** Konfidenz-Obergrenze nach Datenmenge (Nachrichten der Person). */
    fun levelFor(messages: Int): DiscLevel = when {
        messages < MIN_MESSAGES -> DiscLevel.ZU_WENIG
        messages < 60 -> DiscLevel.NIEDRIG
        messages < 150 -> DiscLevel.MITTEL
        else -> DiscLevel.HOCH
    }

    /** Auf Summe 100 normieren (Verfahren der groessten Reste). Negative Werte zaehlen als 0; Summe 0 ergibt null. */
    fun normalize(d: Int, i: Int, s: Int, c: Int): IntArray? {
        val raw = intArrayOf(d.coerceAtLeast(0), i.coerceAtLeast(0), s.coerceAtLeast(0), c.coerceAtLeast(0))
        val sum = raw.sum()
        if (sum == 0) return null
        val exact = raw.map { it * 100.0 / sum }
        val out = exact.map { it.toInt() }.toIntArray()
        var rest = 100 - out.sum()
        exact.indices.sortedByDescending { exact[it] - out[it] }.forEach { if (rest > 0) { out[it]++; rest-- } }
        return out
    }

    private fun levelOf(word: String): DiscLevel? = when (word.lowercase().trim('.', ' ', ',')) {
        "hoch" -> DiscLevel.HOCH
        "mittel" -> DiscLevel.MITTEL
        "niedrig" -> DiscLevel.NIEDRIG
        else -> null
    }

    /**
     * Wertet eine DISC-Zeile der Modellantwort aus, zum Beispiel
     * "D=40 I=30 S=20 C=10; Konfidenz: mittel; Begründung: ..." oder "zu wenig Daten". [basis] = Nachrichten der Person.
     * Die Konfidenz ist hoechstens die der Datenmenge. Unlesbares ergibt "zu wenig Daten", nie erfundene Werte.
     */
    fun parse(value: String, basis: Int, now: Long): DiscProfile {
        val v = value.trim()
        val cap = levelFor(basis)
        if (cap == DiscLevel.ZU_WENIG || v.contains("zu wenig", ignoreCase = true)) return DiscProfile(basis = basis, updatedAt = now, reason = if (cap == DiscLevel.ZU_WENIG) "Weniger als $MIN_MESSAGES Nachrichten der Person." else "")
        fun num(k: String) = Regex("""(?<![A-Za-z])$k\s*[=:]?\s*(\d{1,3})""", RegexOption.IGNORE_CASE).find(v)?.groupValues?.get(1)?.toIntOrNull()
        val d = num("D"); val i = num("I"); val s = num("S"); val c = num("C")
        if (d == null || i == null || s == null || c == null) return DiscProfile(basis = basis, updatedAt = now, reason = "Antwort nicht lesbar.")
        val p = normalize(d, i, s, c) ?: return DiscProfile(basis = basis, updatedAt = now)
        val said = Regex("""Konfidenz\s*[:=]?\s*([A-Za-zäöü]+)""", RegexOption.IGNORE_CASE).find(v)?.groupValues?.get(1)?.let(::levelOf) ?: DiscLevel.NIEDRIG
        val level = if (said.ordinal < cap.ordinal) said else cap
        val reason = Regex("""Begr[uü]ndung\s*[:=]?\s*(.+)$""", RegexOption.IGNORE_CASE).find(v)?.groupValues?.get(1)?.trim()?.take(300).orEmpty()
        return DiscProfile(p[0], p[1], p[2], p[3], level, reason, basis, now)
    }

    /**
     * Fortschreibung bei neuen Laeufen: gewichtetes Mittel nach Datenmenge. Neu zu wenig: alt bleibt. Alt zu wenig: neu gilt.
     * Die Konfidenz richtet sich nach der Summe der Nachrichten (nicht hoeher als die schwaechere Aussage, wenn beide vorliegen? nein: Summe zaehlt).
     */
    fun merge(old: DiscProfile?, new: DiscProfile): DiscProfile {
        if (old == null || old.insufficient) return if (new.insufficient && old != null) old.copy(basis = maxOf(old.basis, new.basis)) else new
        if (new.insufficient) return old
        val wo = old.basis.coerceAtLeast(1).toDouble(); val wn = new.basis.coerceAtLeast(1).toDouble()
        fun mix(a: Int, b: Int) = ((a * wo + b * wn) / (wo + wn)).toInt()
        val p = normalize(mix(old.d, new.d), mix(old.i, new.i), mix(old.s, new.s), mix(old.c, new.c)) ?: return old
        val basis = old.basis + new.basis
        val said = if (new.confidence.ordinal >= old.confidence.ordinal) new.confidence else old.confidence
        val level = if (levelFor(basis).ordinal < said.ordinal) levelFor(basis) else said
        return DiscProfile(p[0], p[1], p[2], p[3], level, new.reason.ifBlank { old.reason }, basis, new.updatedAt)
    }

    fun toJson(p: DiscProfile?): JSONObject? = p?.let {
        JSONObject().put("d", it.d).put("i", it.i).put("s", it.s).put("c", it.c).put("conf", it.confidence.name).put("why", it.reason).put("basis", it.basis).put("at", it.updatedAt)
    }

    fun fromJson(o: JSONObject?): DiscProfile? = o?.let {
        DiscProfile(
            it.optInt("d"), it.optInt("i"), it.optInt("s"), it.optInt("c"),
            runCatching { DiscLevel.valueOf(it.optString("conf")) }.getOrDefault(DiscLevel.ZU_WENIG),
            it.optString("why"), it.optInt("basis"), it.optLong("at"),
        )
    }
}
