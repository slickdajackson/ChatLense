package app.chatlens.agent

import app.chatlens.core.Bounds
import app.chatlens.core.UiNode

/** How the scrollable chat list was found. */
enum class ListHow(val text: String) {
    VERTICAL_ACTION("Knoten mit senkrechter Scrollaktion"),
    ROW_ANCESTOR("naechster scrollbarer Vorfahr der Chatzeilen (keine senkrechte Aktion gemeldet)"),
    ROW_GEOMETRY("nur Zeilengeometrie (kein scrollbarer Vorfahr, Wischgeste ohne Listenknoten)"),
    NONE("keine Liste und keine Zeilen"),
}

/**
 * Result of choosing a list. [node] is the node for node actions (null for ROW_GEOMETRY), [bounds] is the area for the swipe gesture.
 * [allowGeneric]: ACTION_SCROLL_FORWARD/BACKWARD may be used on the node (only on the nearest ancestor of the rows, never on page switchers).
 */
class ListChoice(val node: UiNode?, val bounds: Bounds?, val how: ListHow, val allowGeneric: Boolean, val skipped: List<String>)

/**
 * Chooses the vertically scrollable chat list from a snapshot. Pure logic (JVM-testable).
 * Page switchers (ViewPager, the tabs Chats, Aktuelles, Communities, Anrufe) and horizontal lists are never chosen as the list node:
 * a forward action on them would page to the next tab. A page switcher that is the nearest scrollable ancestor of the rows
 * yields only the row geometry for the swipe gesture.
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
        // 1. Node with a vertical action that covers the rows: the smallest (nearest ancestor), or the largest when there are no rows
        val vertical = usable.filter { it.hasVerticalAction && coversRows(it, rows) }
        val v = if (rows.isEmpty()) vertical.maxByOrNull { it.bounds.area } else vertical.minByOrNull { it.bounds.area }
        if (v != null) return ListChoice(v, v.bounds, ListHow.VERTICAL_ACTION, false, skipped)
        // 2. No node with a vertical action: nearest scrollable ancestor of the rows, unless it is horizontal
        if (rows.isNotEmpty()) {
            val anc = usable.filter { coversRows(it, rows) }.minByOrNull { it.bounds.area }
            if (anc != null) return ListChoice(anc, anc.bounds, ListHow.ROW_ANCESTOR, true, skipped)
            // 3. Rows only: swipe gesture in the area of the recognized rows
            return ListChoice(null, union, ListHow.ROW_GEOMETRY, false, skipped)
        }
        // No rows and no vertical node: the largest remaining scrollable node, only as a fallback for gestures
        val any = usable.maxByOrNull { it.bounds.area }
        return if (any != null) ListChoice(any, any.bounds, ListHow.ROW_ANCESTOR, false, skipped) else ListChoice(null, null, ListHow.NONE, false, skipped)
    }

    /** One line per scrollable node: class, id, bounds, actions, parent chain. For the log on failure. */
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
