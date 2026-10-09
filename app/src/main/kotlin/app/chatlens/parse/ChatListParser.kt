package app.chatlens.parse

import app.chatlens.core.Bounds
import app.chatlens.core.UiNode
import app.chatlens.match.ChatListEntry
import app.chatlens.match.ChatListSelector
import app.chatlens.match.ChatTimeRank
import java.time.LocalDateTime

/**
 * Reads the visible rows of the WhatsApp chat list from a tree.
 *
 * First path (from 0.2.3): via node ids. Rows are the nodes with a container id (contact_row_container). If those are missing, the
 * row is the highest ancestor of a node with a name id (conversations_row_contact_name) that contains exactly one such name and
 * is no taller than one row. The title is the text of the name node.
 * Fallback without ids: the largest scrollable list, and in it rows with at least two text nodes (also one level deeper, if the list
 * has only a wrapper node). Time/date is recognized by format, the preview is the longest remainder. Not checked on a device (PLAN.md).
 */
object ChatListParser {
    /** [diag]: raw notes on the name choice (filled only for short names), for the log. */
    class Row(val entry: ChatListEntry, val bounds: Bounds, val diag: String = "")

    const val SHORT_NAME = 3

    private val pinWords = Regex("(?i)angeheftet|angepinnt|fixiert|pinned")
    private val groupPreview = Regex("""^(?!(?:Du|You)\s*:)[^:\n]{1,40}:\s+\S""")

    /** Number of visible nodes with one of the marker ids (name or container id). */
    fun markerCount(root: UiNode, markerIds: Collection<String>): Int =
        if (markerIds.isEmpty()) 0 else root.walk().count { it.viewId in markerIds && it.visible && it.bounds.area > 0 }

    fun parse(
        root: UiNode,
        now: LocalDateTime = LocalDateTime.now(),
        titleIds: Collection<String> = emptyList(),
        containerIds: Collection<String> = emptyList(),
        unreadIds: Collection<String> = emptyList(),
    ): List<Row> {
        if (titleIds.isNotEmpty() || containerIds.isNotEmpty()) {
            val byId = parseById(root, now, titleIds, containerIds, unreadIds)
            if (byId.isNotEmpty()) return byId
        }
        return parseHeuristic(root, now, unreadIds)
    }

    private fun parseById(root: UiNode, now: LocalDateTime, titleIds: Collection<String>, containerIds: Collection<String>, unreadIds: Collection<String>): List<Row> {
        val all = root.walk().toList()
        val rowNodes: List<UiNode> = run {
            val containers = all.filter { it.viewId in containerIds && it.visible && it.bounds.area > 0 }
            if (containers.isNotEmpty()) return@run containers
            val titles = all.filter { it.viewId in titleIds && it.visible && it.bounds.area > 0 && it.hasText() }
            if (titles.isEmpty()) return@run emptyList()
            val maxH = (root.bounds.height * 0.2).toInt().coerceAtLeast(1)
            titles.map { rowAround(root, it, titleIds, maxH) }.distinctBy { System.identityHashCode(it) }
        }
        val out = ArrayList<Row>()
        var order = 0
        for (r in rowNodes.sortedBy { it.bounds.t }) {
            // Several name nodes in one row: the topmost left one counts as the name
            val titleNode = r.walk().filter { it.viewId in titleIds && it.hasText() && it.visible }
                .sortedWith(compareBy({ it.bounds.t / 20 }, { it.bounds.l })).firstOrNull()
            val row = rowFrom(r, titleNode, now, order, minTexts = 1, unreadIds = unreadIds) ?: continue
            out.add(row)
            order++
        }
        return out
    }

    /** Highest ancestor of [title] that contains exactly one name node, is not scrollable, and is no taller than [maxH]. */
    private fun rowAround(root: UiNode, title: UiNode, titleIds: Collection<String>, maxH: Int): UiNode {
        val path = ArrayList<UiNode>()
        fun find(n: UiNode): Boolean {
            path.add(n)
            if (n === title) return true
            for (c in n.children) if (find(c)) return true
            path.removeAt(path.size - 1)
            return false
        }
        if (!find(root)) return title
        var best = title
        for (i in path.size - 2 downTo 0) {
            val a = path[i]
            if (a.scrollable || a.bounds.height > maxH) break
            if (a.walk().count { it.viewId in titleIds } != 1) break
            best = a
        }
        return best
    }

    private fun parseHeuristic(root: UiNode, now: LocalDateTime, unreadIds: Collection<String>): List<Row> {
        val lists = root.walk().filter { it.scrollable && it.visible && it.bounds.area > 0 }.toList()
        val list = lists.maxByOrNull { it.bounds.area } ?: return emptyList()
        var rows = list.children.filter { it.visible && it.bounds.area > 0 }
        // Only a wrapper node? Then look one level deeper.
        if (rows.size == 1 && rows[0].children.size > 1) rows = rows[0].children.filter { it.visible && it.bounds.area > 0 }
        val out = ArrayList<Row>()
        var order = 0
        for (r in rows.sortedBy { it.bounds.t }) {
            val row = rowFrom(r, null, now, order, minTexts = 2, unreadIds = unreadIds) ?: continue
            out.add(row)
            order++
        }
        return out
    }

    private fun rowFrom(r: UiNode, titleHint: UiNode?, now: LocalDateTime, order: Int, minTexts: Int, unreadIds: Collection<String> = emptyList()): Row? {
        val texts = r.walk().filter { it.visible && it.hasText() && !it.editable && it.bounds.area > 0 }.toList()
        if (texts.size < minTexts) return null
        val timeNode = texts.firstOrNull { it !== titleHint && ChatTimeRank.rank(it.text!!.trim(), now) != ChatTimeRank.UNKNOWN }
        val rest = texts.filter { it !== timeNode }.sortedWith(compareBy({ it.bounds.t / 20 }, { it.bounds.l }))
        val titleNode = titleHint ?: rest.firstOrNull() ?: return null
        var title = titleNode.text!!.trim()
        if (title.isBlank()) return null
        var diag = ""
        if (title.length <= SHORT_NAME) {
            // Short name: record the raw text and the surroundings. If the row description contains a longer name that starts with the short text
            // (for example "Familie, ..." when the display shows "Fa"), that one applies (a hypothesis against cut-off names).
            val descName = r.desc?.substringBefore(',')?.trim()?.takeIf { it.length > title.length && it.startsWith(title, ignoreCase = true) && it.length <= 60 }
            diag = "Rohtext=\"${titleNode.text}\" id=${titleNode.viewId} b=${titleNode.bounds} Zeilenknoten=${r.walk().count()} Textknoten=${texts.size}" +
                " Textlaengen=${texts.map { it.text!!.length }} Beschreibungsname=${descName ?: "keiner"}"
            if (descName != null) title = descName
        }
        val preview = rest.filter { it !== titleNode }.filter { it.text!!.trim().length > 1 }.maxByOrNull { it.text!!.length }?.text?.trim().orEmpty()
        val pinned = r.walk().any { n -> (n.desc?.let { pinWords.containsMatchIn(it) } == true) } ||
            (r.desc?.let { pinWords.containsMatchIn(it) } == true)
        val archive = ChatListSelector.isArchiveRow(title)
        val group = preview.isNotEmpty() && groupPreview.containsMatchIn(preview)
        val (unread, unreadCount) = detectUnread(r, timeNode, unreadIds)
        return Row(ChatListEntry(title, preview, timeNode?.text?.trim().orEmpty(), pinned, archive, group, order, unread, unreadCount), r.bounds, diag)
    }

    private val unreadDesc = Regex("(?i)(\\d+)\\s+(ungelesene?|unread)|ungelesene?\\s+nachricht|unread\\s+message")

    /**
     * Unread hint of a row, in three stages: (1) a node with a badge id, (2) a description "N ungelesene Nachrichten",
     * (3) fallback: a small number text (at most 4 digits) in the right part of the row that is not the time. All of these are hints,
     * not checked on a device. false means only "no hint recognized".
     */
    internal fun detectUnread(r: UiNode, timeNode: UiNode?, unreadIds: Collection<String>): Pair<Boolean, Int> {
        val nodes = r.walk().filter { it.visible }.toList()
        nodes.firstOrNull { it.viewId != null && it.viewId in unreadIds }?.let { n ->
            val c = n.text?.trim()?.toIntOrNull() ?: unreadDesc.find(n.desc.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return true to c
        }
        for (n in nodes) {
            val m = unreadDesc.find(n.desc.orEmpty()) ?: continue
            return true to (m.groupValues[1].toIntOrNull() ?: 0)
        }
        val minX = r.bounds.l + (r.bounds.width * 0.7).toInt()
        val badge = nodes.firstOrNull { n ->
            n !== timeNode && n.hasText() && n.text!!.trim().let { it.length in 1..4 && it.all { ch -> ch.isDigit() } } &&
                n.bounds.centerX >= minX && n.bounds.area > 0
        }
        if (badge != null) return true to (badge.text!!.trim().toIntOrNull() ?: 0)
        return false to 0
    }
}
