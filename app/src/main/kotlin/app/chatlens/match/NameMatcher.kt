package app.chatlens.match

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/** Ein Kandidat mit Uebereinstimmung in Prozent (0 bis 100). */
class NameCandidate(val text: String, val percent: Int, val index: Int)

/**
 * Namensabgleich fuer Chattitel. Normalisiert (Kleinschreibung, Akzente, Emojis, Satzzeichen, Mehrfachleerzeichen)
 * und nimmt die beste von drei Messungen: Zeichenabstand, Wortmenge (Reihenfolge egal) und Teilstring.
 * 100 Prozent gibt es nur bei gleichem normalisierten Text; sonst hoechstens 99.
 */
object NameMatcher {

    fun normalize(s: String): String {
        val d = Normalizer.normalize(s.replace("ß", "ss").replace("ẞ", "ss"), Normalizer.Form.NFD)
        val sb = StringBuilder()
        var lastSpace = true
        for (ch in d) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            if (Character.isLetterOrDigit(ch)) {
                sb.append(ch.lowercaseChar())
                lastSpace = false
            } else if (!lastSpace) {
                sb.append(' ')
                lastSpace = true
            }
        }
        return sb.toString().trim()
    }

    /** Gleicher Titel im strengen Sinn (nur Leerraum am Rand und Gross/Klein egal): kein Nachfragen noetig. */
    fun isExact(query: String, candidate: String): Boolean = query.trim().equals(candidate.trim(), ignoreCase = true)

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun ratio(a: String, b: String): Double {
        val m = max(a.length, b.length)
        return if (m == 0) 1.0 else 1.0 - lev(a, b).toDouble() / m
    }

    fun similarity(query: String, candidate: String): Int {
        val a = normalize(query)
        val b = normalize(candidate)
        if (a.isEmpty() || b.isEmpty()) return 0
        if (a == b) return 100
        val direct = ratio(a, b)
        val ta = a.split(' ').sorted().joinToString(" ")
        val tb = b.split(' ').sorted().joinToString(" ")
        val tokens = ratio(ta, tb)
        val (shortS, longS) = if (a.length <= b.length) a to b else b to a
        val contain = if (shortS.length >= 3 && longS.contains(shortS)) 0.6 + 0.4 * shortS.length.toDouble() / longS.length else 0.0
        // Teilmenge der Woerter (Vorname gegen vollen Namen) etwas niedriger bewerten
        val wa = a.split(' ').toSet()
        val wb = b.split(' ').toSet()
        val subset = if ((wa.size < wb.size && wb.containsAll(wa)) || (wb.size < wa.size && wa.containsAll(wb))) {
            0.6 + 0.4 * min(wa.size, wb.size).toDouble() / max(wa.size, wb.size)
        } else 0.0
        val best = maxOf(direct, tokens, contain, subset)
        return min(99, (best * 100).toInt())
    }

    /** Bester Kandidat oder null, wenn die Liste leer ist. Bei Gleichstand gewinnt der frueheste. */
    fun best(query: String, candidates: List<String>): NameCandidate? {
        var bestC: NameCandidate? = null
        candidates.forEachIndexed { i, c ->
            val p = similarity(query, c)
            if (bestC == null || p > bestC!!.percent) bestC = NameCandidate(c, p, i)
        }
        return bestC
    }

    /** Kuerzere Suchanfrage fuer einen zweiten Versuch (erstes Wort, sonst die erste Haelfte), oder null, wenn keine sinnvoll ist. */
    fun shorterQuery(title: String): String? {
        val t = title.trim()
        val tokens = t.split(Regex("\\s+"))
        if (tokens.size > 1 && tokens[0].length >= 3) return tokens[0]
        if (t.length >= 6) return t.take(maxOf(3, (t.length + 1) / 2))
        return null
    }

    /** Empfohlene Mindestuebereinstimmung, unter der gar nicht erst nachgefragt wird (zu unaehnlich). */
    const val MIN_ASK_PERCENT = 55
}
