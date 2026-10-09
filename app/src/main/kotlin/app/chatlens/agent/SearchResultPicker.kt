package app.chatlens.agent

import app.chatlens.core.UiNode
import app.chatlens.match.NameMatcher
import app.chatlens.profile.SectionKind
import app.chatlens.profile.SelectorProfile

/**
 * Ein Textknoten, dessen Text dem gesuchten Titel entspricht, samt Abschnitt und Klickziel.
 * [section] ist null, wenn oberhalb des Treffers keine bekannte Abschnittsueberschrift steht.
 */
class SearchHit(
    val title: UiNode,
    val section: SectionKind?,
    val header: UiNode?,
    /** Naechster klickbarer Knoten ab dem Titelknoten aufwaerts (kann der Knoten selbst sein), sonst null. */
    val clickTarget: UiNode?,
    val viaFallback: Boolean,
)

class PickResult(
    val hit: SearchHit?,
    val candidates: List<SearchHit>,
    val headersSeen: Int,
    val reason: String,
    /** Untere Kante des Suchfelds in Pixeln; darunter beginnt der Listenbereich. */
    val searchBottom: Int = 0,
    /** Zahl verschiedener Zeilen mit gleichem (normalisiertem) Namen im erlaubten Bereich, wenn mehr als eine: dann wird nichts gewaehlt. */
    val ambiguous: Int = 0,
) {
    /** Kurze Zusammenfassung fuers Log. Enthaelt keinen Chattext, nur Zaehler. */
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
 * Waehlt aus dem Suchergebnis den Treffer im Abschnitt "Chats".
 * Regeln:
 *  - Der Text des Knotens muss dem Titel entsprechen (getrimmt, Gross/Klein egal), kein Teiltreffer.
 *  - Der Abschnitt ergibt sich aus der naechsten bekannten Ueberschrift oberhalb des Treffers.
 *  - Nur Abschnitt "Chats" ist erlaubt. Gruppen und andere Abschnitte werden nie gewaehlt.
 *  - Nur wenn im ganzen Baum keine bekannte Ueberschrift steht (und das Profil es erlaubt), gilt der oberste
 *    Titeltreffer unterhalb des Suchfelds als Rueckfall.
 * Reine Funktion auf UiNode, ohne Android-Abhaengigkeit, daher testbar.
 */
object SearchResultPicker {
    /** Ohne erkennbares Suchfeld gilt das obere Achtel des Bildschirms als Suchleiste. */
    private const val NO_FIELD_FALLBACK_FRACTION = 0.12

    fun pick(root: UiNode, title: String, profile: SelectorProfile): PickResult {
        val t = title.trim()
        if (t.isEmpty()) return PickResult(null, emptyList(), 0, "Titel ist leer.")
        val h = root.bounds.b.coerceAtLeast(1)
        // Obergrenze fuer die Suchleiste. Die Treffer duerfen weit oben liegen (auf hohen Displays unter 25 Prozent der Hoehe),
        // deshalb zaehlt als Listenbereich alles unterhalb der Unterkante des editierbaren Suchfelds.
        val fieldLine = (h * profile.searchFieldTopFraction).toInt()
        val searchBottom = root.walk()
            .filter { it.editable && it.visible && it.bounds.centerY < fieldLine }
            .maxOfOrNull { it.bounds.b } ?: (h * NO_FIELD_FALLBACK_FRACTION).toInt()

        val listNodes = root.walk()
            .filter { it.visible && it.hasText() && !it.editable && it.bounds.centerY > searchBottom && it.bounds.height > 0 }
            .toList()

        val headers = listNodes.mapNotNull { n -> profile.sectionKindOf(n.text)?.let { n to it } }
        // Identitaetsmenge: UiNode ist eine data class, ein tiefer equals/hashCode waere teuer und mehrdeutig.
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
     * Wie [pick], aber ohne exakte Namensgleichheit: der Textknoten im Abschnitt "Chats" (oder, ohne Ueberschriften, unterhalb
     * des Suchfelds) mit der groessten Uebereinstimmung ab [NameMatcher.MIN_ASK_PERCENT]. Gruppen und andere Abschnitte nie.
     * Der Treffer wird nur vorgeschlagen; gewaehlt wird er erst nach Ja des Nutzers.
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
     * Naechster klickbarer Knoten auf dem Pfad Wurzel bis Titelknoten (von unten nach oben).
     * Ein Knoten, der fast den ganzen Bildschirm fuellt oder eine Abschnittsueberschrift enthaelt,
     * ist kein Zeilen-Container und wird nicht genommen.
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
