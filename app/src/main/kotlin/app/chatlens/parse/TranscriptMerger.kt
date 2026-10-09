package app.chatlens.parse

import app.chatlens.core.ChatMessage
import app.chatlens.core.Direction
import app.chatlens.core.Kind
import app.chatlens.core.PageItem

/**
 * Merges pages (each top to bottom = old to new) while scrolling backward.
 * The first page is the newest. Later pages overlap the head of the collected list.
 *
 * Edge messages: a message cut off at the list edge is marked [ChatMessage.incomplete].
 * When matching two pages, a cut-off version counts as the same as the complete one when kind, direction,
 * sender, and time do not contradict and the text is equal or one text is contained in the other
 * (prefix, suffix, or substring). If the complete version appears later, it replaces the cut-off one.
 */
class TranscriptMerger {
    private val collected = ArrayList<ChatMessage>()

    val messages: List<ChatMessage> get() = collected

    class Result(
        val added: Int,
        /** For each PageItem, the canonical object in the full list. */
        val canonical: List<ChatMessage>,
        val gapInserted: Boolean,
        /** True when no reliable alignment was found and (when allowGap=false) nothing was taken over. */
        val noOverlap: Boolean = false,
        /** Number of page rows that were matched to a row already in the collection. */
        val overlapRows: Int = 0,
        /** Number of cut-off messages that were replaced by a complete version. */
        val completed: Int = 0,
        /** Of those, inserted into the collection even though they lay in the middle of the overlap (not recognized before). */
        val inserted: Int = 0,
        /** Page rows in the overlap with no counterpart (parser noise). */
        val unmatched: Int = 0,
    )

    /**
     * Inserts a page. The page is aligned to the head of the collection by a longest common subsequence:
     * individual rows without a counterpart (noise, edge rows recognized differently) no longer block the alignment.
     * It is reliable when at least one non-date row matches, older page rows have to be appended only at the head of the collection
     * (index 0 or 1), and at most as many rows are unpaired as there are anchors
     * (none when there is a single anchor).
     */
    fun add(page: List<PageItem>, allowGap: Boolean = true): Result {
        val msgs = page.map { it.message }
        if (msgs.isEmpty()) return Result(0, emptyList(), false)
        if (collected.isEmpty()) {
            collected.addAll(msgs)
            return Result(msgs.size, msgs, false)
        }

        val window = minOf(collected.size, msgs.size * 3 + 10)
        val matches = FrameAligner.lcs(msgs, collected.subList(0, window))
        val anchors = matches.count { msgs[it.a].kind != Kind.DATE }
        if (anchors > 0) {
            val first = matches.first()
            val matchedA = matches.map { it.a }.toSet()
            val older = first.a
            val unmatchedAfter = (first.a + 1 until msgs.size).count { it !in matchedA && isCountable(msgs[it]) }
            val allowed = if (anchors == 1) 0 else anchors
            val headOk = older == 0 || first.b <= 1
            if (headOk && unmatchedAfter <= allowed) {
                return merge(msgs, matches, unmatchedAfter)
            }
        }

        if (!allowGap) return Result(0, msgs, gapInserted = false, noOverlap = true)

        // No overlap found: append the whole page at the front, mark a gap
        val gap = ChatMessage(Kind.GAP, Direction.UNKNOWN, null, "mögliche Lücke: Seiten überlappen nicht", null)
        collected.addAll(0, msgs + gap)
        return Result(msgs.size, msgs, true, true, 0, 0)
    }

    private fun merge(msgs: List<ChatMessage>, matches: List<FrameAligner.Match>, unmatched: Int): Result {
        val byA = matches.associateBy { it.a }
        val first = matches.first()
        var completed = 0
        val canon = ArrayList<ChatMessage>(msgs.size)
        val insertAfter = HashMap<Int, MutableList<ChatMessage>>()
        var lastB = -1
        var inserted = 0
        for (i in msgs.indices) {
            val mt = byA[i]
            if (mt != null) {
                val target = collected[mt.b]
                if (target.absorb(msgs[i])) completed++
                canon.add(target)
                lastB = mt.b
            } else if (i > first.a && lastB >= 0 && (isCountable(msgs[i]) || msgs[i].kind == Kind.DATE)) {
                // in the middle of the overlap with no counterpart: insert after the last matched row
                insertAfter.getOrPut(lastB) { ArrayList() }.add(msgs[i])
                canon.add(msgs[i])
                inserted++
            } else {
                canon.add(msgs[i])
            }
        }
        val older = msgs.subList(0, first.a).toList()
        val rebuilt = ArrayList<ChatMessage>(collected.size + older.size + inserted)
        rebuilt.addAll(older)
        for (k in collected.indices) {
            rebuilt.add(collected[k])
            insertAfter[k]?.let { rebuilt.addAll(it) }
        }
        collected.clear()
        collected.addAll(rebuilt)
        return Result(older.size + inserted, canon, false, false, matches.size, completed, inserted, unmatched)
    }

    /** Number of messages still cut off (never seen in a complete version). */
    fun incompleteCount(): Int = collected.count { it.incomplete }

    /**
     * Removes cut-off messages at the oldest end, as long as at least [minKeep]
     * messages (text, image, voice) remain afterwards. Returns the number of removed entries.
     */
    fun trimIncompleteHead(minKeep: Int): Int {
        var removed = 0
        while (true) {
            val idx = collected.indexOfFirst { it.kind != Kind.DATE }
            if (idx < 0) break
            val m = collected[idx]
            if (!m.incomplete) break
            if (isCountable(m) && collected.count { isCountable(it) } - 1 < minKeep) break
            collected.subList(0, idx + 1).clear()
            removed += idx + 1
        }
        return removed
    }

    companion object {
        fun isCountable(m: ChatMessage): Boolean = m.kind == Kind.TEXT || m.kind == Kind.IMAGE || m.kind == Kind.VOICE

        private fun norm(s: String): String = s.replace(Regex("\\s+"), " ").trim().trimEnd('…', '.', ' ')

        /** True when both rows can be the same message. Strict, as long as neither side is cut off or truncated. */
        fun compatible(a: ChatMessage, b: ChatMessage): Boolean {
            if (a.kind == Kind.GAP || b.kind == Kind.GAP) return false
            val loose = a.incomplete || b.incomplete || a.truncated || b.truncated
            val kindOk = a.kind == b.kind ||
                (loose && a.kind in TEXT_LIKE && b.kind in TEXT_LIKE)
            if (!kindOk) return false
            if (a.direction != b.direction && !(loose && (a.direction == Direction.UNKNOWN || b.direction == Direction.UNKNOWN))) return false
            if (a.sender.orEmpty() != b.sender.orEmpty() && !(loose && (a.sender == null || b.sender == null))) return false
            if (a.time.orEmpty() != b.time.orEmpty() && !(loose && (a.time == null || b.time == null))) return false
            val ta = norm(a.text)
            val tb = norm(b.text)
            if (ta == tb) return true
            if (!loose) return false
            val (short, long) = if (ta.length <= tb.length) ta to tb else tb to ta
            return short.isNotEmpty() && long.contains(short)
        }

        private val TEXT_LIKE = setOf(Kind.TEXT, Kind.SYSTEM)
    }
}
