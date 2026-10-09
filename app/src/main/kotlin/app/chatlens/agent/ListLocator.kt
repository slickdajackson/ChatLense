package app.chatlens.agent

import app.chatlens.core.Bounds
import app.chatlens.core.UiNode

/** Wie die scrollbare Chatliste gefunden wurde. */
enum class ListHow(val text: String) {
    VERTICAL_ACTION("Knoten mit senkrechter Scrollaktion"),
    ROW_ANCESTOR("naechster scrollbarer Vorfahr der Chatzeilen (keine senkrechte Aktion gemeldet)"),
    ROW_GEOMETRY("nur Zeilengeometrie (kein scrollbarer Vorfahr, Wischgeste ohne Listenknoten)"),
    NONE("keine Liste und keine Zeilen"),
}

/**
 * Ergebnis der Listenwahl. [node] ist der Knoten fuer Knotenaktionen (null bei ROW_GEOMETRY), [bounds] der Bereich fuer die Wischgeste.
 * [allowGeneric]: ACTION_SCROLL_FORWARD/BACKWARD darf auf dem Knoten benutzt werden (nur beim naechsten Vorfahr der Zeilen, nie bei Seitenwechslern).
 */
class ListChoice(val node: UiNode?, val bounds: Bounds?, val how: ListHow, val allowGeneric: Boolean, val skipped: List<String>)

/**
 * Waehlt die senkrecht scrollbare Chatliste aus einem Schnappschuss. Reine Logik (JVM-testbar).
 * Seitenwechsler (ViewPager, die Tabs Chats, Aktuelles, Communities, Anrufe) und waagerechte Listen werden nie als Listenknoten gewaehlt:
 * eine Vorwaertsaktion darauf wuerde zum naechsten Tab blaettern. Ein Seitenwechsler, der der naechste scrollbare Vorfahr der Zeilen ist,
 * ergibt nur die Zeilengeometrie fuer die Wischgeste.
 */
object ListLocator {
    fun isPager(n: UiNode): Boolean = n.className.contains("Pager", ignoreCase = true)

    fun isHorizontal(n: UiNode): Boolean =
        isPager(n) || n.className.contains("HorizontalScrollView", ignoreCase = true) || (n.scrollHoriz && !n.hasVerticalAction)

    private fun scrollable(n: UiNode) = n.visible && (n.scrollable || n.hasAnyScrollAction)

    private fun coversRows(n: UiNode, rows: List<Bounds>) = rows.isEmpty() || rows.all { n.bounds.contains(Bounds(it.centerX, it.centerY, it.centerX, it.centerY)) }

    fun choose(root: UiNode, rows: List<Bounds>): ListChoice {
        val all = root.walk().filter { scrollable(it) }.toList()
        val skipped = ArrayList<String>()
        val usable = all.filter { n ->
            if (isHorizontal(n)) { skipped.add("${n.shortClass} ${n.bounds} (waagerecht oder Seitenwechsler)"); false } else true
        }
        val union = rows.reduceOrNull { a, b -> a.union(b) }
        // 1. Knoten mit senkrechter Aktion, der die Zeilen umfasst: der kleinste (naechster Vorfahr), ohne Zeilen der groesste
        val vertical = usable.filter { it.hasVerticalAction && coversRows(it, rows) }
        val v = if (rows.isEmpty()) vertical.maxByOrNull { it.bounds.area } else vertical.minByOrNull { it.bounds.area }
        if (v != null) return ListChoice(v, v.bounds, ListHow.VERTICAL_ACTION, false, skipped)
        // 2. Kein Knoten mit senkrechter Aktion: naechster scrollbarer Vorfahr der Zeilen, sofern nicht waagerecht
        if (rows.isNotEmpty()) {
            val anc = usable.filter { coversRows(it, rows) }.minByOrNull { it.bounds.area }
            if (anc != null) return ListChoice(anc, anc.bounds, ListHow.ROW_ANCESTOR, true, skipped)
            // 3. Nur Zeilen: Wischgeste im Bereich der erkannten Zeilen
            return ListChoice(null, union, ListHow.ROW_GEOMETRY, false, skipped)
        }
        // Ohne Zeilen und ohne senkrechten Knoten: der groesste verbleibende scrollbare Knoten nur als Notbehelf fuer Gesten
        val any = usable.maxByOrNull { it.bounds.area }
        return if (any != null) ListChoice(any, any.bounds, ListHow.ROW_ANCESTOR, false, skipped) else ListChoice(null, null, ListHow.NONE, false, skipped)
    }

    /** Eine Zeile je scrollbarem Knoten: Klasse, ID, Bounds, Aktionen, Elternkette. Fuer das Log bei Misserfolg. */
    fun describeScrollables(root: UiNode): List<String> {
        val out = ArrayList<String>()
        fun rec(n: UiNode, chain: List<String>) {
            if (scrollable(n)) {
                val acts = buildList {
                    if (n.scrollUp) add("UP"); if (n.scrollDown) add("DOWN"); if (n.scrollHoriz) add("LEFT/RIGHT"); if (n.scrollGeneric) add("FORWARD/BACKWARD")
                }
                out.add("Scrollbar: ${n.className} id=${n.viewId ?: "-"} b=${n.bounds} scrollable=${n.scrollable} Aktionen=${acts.ifEmpty { listOf("keine") }.joinToString("/")} Eltern=${chain.takeLast(6).joinToString(" > ").ifEmpty { "-" }}")
            }
            for (c in n.children) rec(c, chain + (n.shortClass + (n.viewId?.substringAfter('/')?.let { ":$it" } ?: "")))
        }
        rec(root, emptyList())
        return out
    }
}
