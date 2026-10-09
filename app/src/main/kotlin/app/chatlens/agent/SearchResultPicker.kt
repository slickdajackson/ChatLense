package app.chatlens.agent

import app.chatlens.core.UiNode
import app.chatlens.match.NameMatcher
import app.chatlens.profile.SectionKind
import app.chatlens.profile.SelectorProfile

/**
 * A text node whose text matches the searched title, together with its section and click target.
 * [section] is null when no known section heading stands above the hit.
 */
class SearchHit(
    val title: UiNode,
    val section: SectionKind?,
    val header: UiNode?,
    /** Nearest clickable node from the title node upward (can be the node itself), otherwise null. */
    val clickTarget: UiNode?,
    val viaFallback: Boolean,
)

class PickResult(
    val hit: SearchHit?,
    val candidates: List<SearchHit>,
    val headersSeen: Int,
    val reason: String,
    /** Bottom edge of the search field in pixels; the list area begins below it. */
    val searchBottom: Int = 0,
    /** Number of distinct rows with the same (normalized) name in the allowed area; if more than one, nothing is chosen. */
    val ambiguous: Int = 0,
) {
    /** Short summary for the log. Contains no chat text, only counters. */
    fun summary(): String {
        val chats = candidates.count { it.section == SectionKind.CHATS }
        val denied = candidates.count { it.section == SectionKind.DENIED }
        val other = candidates.count { it.section == SectionKind.OTHER }
        val none = candidates.count { it.section == null }
        return "Titeltreffer=${candidates.size} (Chats=$chats, Gruppen=$denied, andere Abschnitte=$other, ohne Abschnitt=$none), " +
            "Abschnittsueberschriften sichtbar=$headersSeen. $reason"
    }
}

/**
 * Chooses the hit in the "Chats" section from the search result.
 * Rules:
 *  - The node text must match the title (trimmed, case-insensitive), not a partial match.
 *  - The section comes from the nearest known heading above the hit.
 *  - Only the "Chats" section is allowed. Groups and other sections are never chosen.
 *  - Only when the whole tree has no known heading (and the profile allows it) does the topmost
 *    title hit below the search field count as the fallback.
 * Pure function on UiNode, with no Android dependency, so testable.
 */
object SearchResultPicker {
    /** Without a recognizable search field, the top eighth of the screen counts as the search bar. */
    private const val NO_FIELD_FALLBACK_FRACTION = 0.12

    fun pick(root: UiNode, title: String, profile: SelectorProfile): PickResult {
        val t = title.trim()
        if (t.isEmpty()) return PickResult(null, emptyList(), 0, "Titel ist leer.")
        val h = root.bounds.b.coerceAtLeast(1)
        // Upper bound for the search bar. Hits may sit quite high (on tall displays, below 25 percent of the height),
        // so everything below the bottom edge of the editable search field counts as the list area.
        val fieldLine = (h * profile.searchFieldTopFraction).toInt()
        val searchBottom = root.walk()
            .filter { it.editable && it.visible && it.bounds.centerY < fieldLine }
            .maxOfOrNull { it.bounds.b } ?: (h * NO_FIELD_FALLBACK_FRACTION).toInt()

        val listNodes = root.walk()
            .filter { it.visible && it.hasText() && !it.editable && it.bounds.centerY > searchBottom && it.bounds.height > 0 }
            .toList()

        val headers = listNodes.mapNotNull { n -> profile.sectionKindOf(n.text)?.let { n to it } }
        // Identity set: UiNode is a data class, so a deep equals/hashCode would be expensive and ambiguous.
        val headerNodes: MutableSet<UiNode> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        headers.forEach { headerNodes.add(it.first) }

        val key = NameMatcher.normalize(t)
        val titleNodes = listNodes.filter { it !in headerNodes && NameMatcher.normalize(it.text!!) == key }
        val rootArea = root.bounds.area.coerceAtLeast(1)

        val hits = titleNodes.map { n ->
            val above = headers.filter { it.first.bounds.centerY <= n.bounds.centerY }.maxByOrNull { it.first.bounds.centerY }
            SearchHit(n, above?.second, above?.first, clickTarget(root, n, headerNodes, rootArea), false)
        }.sortedBy { it.title.bounds.t }

        fun rowId(h: SearchHit) = h.clickTarget?.bounds ?: h.title.bounds
        val chatHits = hits.filter { it.section == SectionKind.CHATS }
        val distinctChats = chatHits.map { rowId(it) }.distinct().size
        if (distinctChats > 1) {
            return PickResult(null, hits, headers.size, "Mehrdeutig: $distinctChats verschiedene Zeilen im Abschnitt Chats tragen diesen Namen. Es wird nichts geraten.", searchBottom, distinctChats)
        }
        val chats = chatHits.firstOrNull()
        if (chats != null) return PickResult(chats, hits, headers.size, "Gewaehlt: Treffer im Abschnitt Chats (Namensabgleich ohne Umlaute und Gross/Klein).", searchBottom)

        if (profile.searchFallbackWithoutHeaders && headers.isEmpty()) {
            val distinctAll = hits.map { rowId(it) }.distinct().size
            if (distinctAll > 1) {
                return PickResult(null, hits, 0, "Mehrdeutig: $distinctAll verschiedene Zeilen ohne Abschnittsueberschrift tragen diesen Namen. Es wird nichts geraten.", searchBottom, distinctAll)
            }
            val fb = hits.firstOrNull()
            if (fb != null) {
                return PickResult(
                    SearchHit(fb.title, null, null, fb.clickTarget, true), hits, 0,
                    "Rueckfall: keine Abschnittsueberschrift im Baum, oberster Titeltreffer gewaehlt.", searchBottom,
                )
            }
        }

        val reason = when {
            hits.isEmpty() -> "Kein Knoten mit dem Titel unterhalb des Suchfelds."
            hits.all { it.section == SectionKind.DENIED } -> "Titel nur in gesperrten Abschnitten (Gruppen), nicht in Chats."
            else -> "Titel nicht im Abschnitt Chats (Abschnitt unbekannt oder andere Abschnitte)."
        }
        return PickResult(null, hits, headers.size, reason, searchBottom)
    }

    class FuzzyHit(val hit: SearchHit, val name: String, val percent: Int)

    /**
     * Like [pick], but without an exact name match: the text node in the "Chats" section (or, with no headings, below
     * the search field) with the highest similarity from [NameMatcher.MIN_ASK_PERCENT] up. Groups and other sections never.
     * The hit is only proposed; it is chosen only after the user says yes.
     */
    fun pickFuzzy(root: UiNode, title: String, profile: SelectorProfile): FuzzyHit? {
        val t = title.trim()
        if (t.isEmpty()) return null
        val h = root.bounds.b.coerceAtLeast(1)
        val fieldLine = (h * profile.searchFieldTopFraction).toInt()
        val searchBottom = root.walk()
            .filter { it.editable && it.visible && it.bounds.centerY < fieldLine }
            .maxOfOrNull { it.bounds.b } ?: (h * NO_FIELD_FALLBACK_FRACTION).toInt()
        val listNodes = root.walk()
            .filter { it.visible && it.hasText() && !it.editable && it.bounds.centerY > searchBottom && it.bounds.height > 0 }
            .toList()
        val headers = listNodes.mapNotNull { n -> profile.sectionKindOf(n.text)?.let { n to it } }
        val headerNodes: MutableSet<UiNode> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        headers.forEach { headerNodes.add(it.first) }
        val rootArea = root.bounds.area.coerceAtLeast(1)
        var best: FuzzyHit? = null
        for (n in listNodes) {
            if (n in headerNodes) continue
            val txt = n.text!!.trim()
            if (txt.length > 60) continue
            val above = headers.filter { it.first.bounds.centerY <= n.bounds.centerY }.maxByOrNull { it.first.bounds.centerY }
            val section = above?.second
            val allowed = section == SectionKind.CHATS || (headers.isEmpty() && profile.searchFallbackWithoutHeaders)
            if (!allowed) continue
            val p = NameMatcher.similarity(t, txt)
            if (p < NameMatcher.MIN_ASK_PERCENT) continue
            if (best == null || p > best.percent) {
                best = FuzzyHit(SearchHit(n, section, above?.first, clickTarget(root, n, headerNodes, rootArea), section == null), txt, p)
            }
        }
        return best
    }

    /**
     * Nearest clickable node on the path from root to the title node (from the bottom upward).
     * A node that fills almost the whole screen or contains a section heading
     * is not a row container and is not taken.
     */
    private fun clickTarget(root: UiNode, node: UiNode, headerNodes: Set<UiNode>, rootArea: Long): UiNode? {
        val path = pathTo(root, node) ?: return null
        for (n in path.asReversed()) {
            if (!n.clickable) continue
            if (n.bounds.area * 10 > rootArea * 6) return null
            if (n.walk().any { it in headerNodes }) return null
            return n
        }
        return null
    }

    private fun pathTo(cur: UiNode, target: UiNode): List<UiNode>? {
        if (cur === target) return listOf(cur)
        for (c in cur.children) {
            val p = pathTo(c, target)
            if (p != null) return listOf(cur) + p
        }
        return null
    }
}
