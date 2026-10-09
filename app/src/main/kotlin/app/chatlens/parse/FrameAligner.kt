package app.chatlens.parse

import app.chatlens.core.ChatMessage
import app.chatlens.core.Kind
import app.chatlens.core.PageItem

/**
 * Aligns two views (row lists) with each other and measures the shift in pixels.
 * The matching is a longest common subsequence over [TranscriptMerger.compatible]. It tolerates individual
 * rows that were recognized in only one view (cut-off images, parser noise).
 */
object FrameAligner {
    class Match(val a: Int, val b: Int)

    fun lcs(x: List<ChatMessage>, y: List<ChatMessage>): List<Match> {
        val n = x.size
        val m = y.size
        if (n == 0 || m == 0) return emptyList()
        val c = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                c[i][j] = if (TranscriptMerger.compatible(x[i], y[j])) 1 + c[i + 1][j + 1] else maxOf(c[i + 1][j], c[i][j + 1])
            }
        }
        val out = ArrayList<Match>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (TranscriptMerger.compatible(x[i], y[j]) && c[i][j] == 1 + c[i + 1][j + 1]) {
                out.add(Match(i, j))
                i++
                j++
            } else if (c[i + 1][j] >= c[i][j + 1]) {
                i++
            } else {
                j++
            }
        }
        return out
    }

    /** True when at least one match is not a pure date row (date separators alone are not enough as an anchor). */
    fun hasAnchor(x: List<ChatMessage>, matches: List<Match>): Boolean = matches.any { x[it.a].kind != Kind.DATE }

    /**
     * Median of the shift of rows from [prev] to [next] in pixels. Positive: the content moved downward
     * (scrolled toward older messages). Only row edges that are not cut off in either view count
     * (top edge, otherwise bottom edge). Null when no row is usable for that.
     */
    fun shift(prev: List<PageItem>, next: List<PageItem>, matches: List<Match>): Int? {
        val dys = ArrayList<Int>()
        for (mt in matches) {
            val p = prev[mt.a]
            val q = next[mt.b]
            val pb = p.rowBounds ?: continue
            val qb = q.rowBounds ?: continue
            val mp = p.message
            val mq = q.message
            if (!mp.clipTop && !mq.clipTop) dys.add(qb.t - pb.t)
            else if (!mp.clipBottom && !mq.clipBottom) dys.add(qb.b - pb.b)
        }
        if (dys.isEmpty()) return null
        dys.sort()
        return dys[dys.size / 2]
    }
}
