package app.chatlens.match

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/** A candidate with a match in percent (0 to 100). */
class NameCandidate(val text: String, val percent: Int, val index: Int)

/**
 * Name matching for chat titles. Normalizes (lowercase, accents, emoji, punctuation, repeated spaces)
 * and takes the best of three measures: character distance, word set (order does not matter), and substring.
 * 100 percent only when the normalized text is equal; otherwise at most 99.
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

    /** Same title in the strict sense (only surrounding whitespace and case do not matter): no need to ask. */
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
        // Score a subset of the words (first name against the full name) a bit lower
        val wa = a.split(' ').toSet()
        val wb = b.split(' ').toSet()
        val subset = if ((wa.size < wb.size && wb.containsAll(wa)) || (wb.size < wa.size && wa.containsAll(wb))) {
            0.6 + 0.4 * min(wa.size, wb.size).toDouble() / max(wa.size, wb.size)
        } else 0.0
        val best = maxOf(direct, tokens, contain, subset)
        return min(99, (best * 100).toInt())
    }

    /** Best candidate, or null when the list is empty. On a tie the earliest one wins. */
    fun best(query: String, candidates: List<String>): NameCandidate? {
        var bestC: NameCandidate? = null
        candidates.forEachIndexed { i, c ->
            val p = similarity(query, c)
            if (bestC == null || p > bestC!!.percent) bestC = NameCandidate(c, p, i)
        }
        return bestC
    }

    /** Shorter search query for a second try (first word, otherwise the first half), or null when none is useful. */
    fun shorterQuery(title: String): String? {
        val t = title.trim()
        val tokens = t.split(Regex("\\s+"))
        if (tokens.size > 1 && tokens[0].length >= 3) return tokens[0]
        if (t.length >= 6) return t.take(maxOf(3, (t.length + 1) / 2))
        return null
    }

    /** Recommended minimum match, below which there is no point in asking (too dissimilar). */
    const val MIN_ASK_PERCENT = 55
}
