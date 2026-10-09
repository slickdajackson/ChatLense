package app.chatlens.parse

import app.chatlens.core.Bounds
import app.chatlens.core.ChatMessage
import app.chatlens.core.Direction
import app.chatlens.core.Kind
import app.chatlens.core.PageItem
import app.chatlens.core.ParsedPage
import app.chatlens.core.UiNode
import app.chatlens.profile.SelectorProfile

/**
 * Liest die sichtbaren Nachrichten aus einem Accessibility-Baum-Schnappschuss.
 * Alle Annahmen ueber den WhatsApp-Aufbau stehen im Profil bzw. sind hier als Heuristik markiert
 * und muessen mit dem Debug-Baum-Export auf dem Geraet kalibriert werden.
 */
class ChatParser(
    private val p: SelectorProfile,
    private val density: Float,
    private val groupChat: Boolean,
) {

    /** Heuristik: groesster scrollbarer Knoten mit Zeit-Texten darin, sonst groesster scrollbarer Knoten. */
    fun findMessageList(root: UiNode): UiNode? {
        val scrollables = root.walk().filter { it.scrollable && it.bounds.area > 0 }.toList()
        if (scrollables.isEmpty()) return null
        val withTimes = scrollables.filter { n -> n.walk().any { it.hasText() && p.isTimeText(it.text!!) } }
        val pool = withTimes.ifEmpty { scrollables }
        // Klassenhinweise nur als Tiebreaker (gleiche Flaeche)
        return pool.maxWithOrNull(
            compareBy<UiNode>({ it.bounds.area }, { n -> if (p.messageListClassHints.any { n.className.contains(it) }) 1 else 0 }),
        )
    }

    fun parse(root: UiNode): ParsedPage {
        val list = findMessageList(root) ?: return ParsedPage(emptyList(), false, null, 0)
        val rows = list.children
            .filter { it.visible && it.bounds.area > 0 }
            .sortedWith(compareBy({ it.bounds.t }, { it.bounds.l }))
        val items = rows.mapNotNull { parseRow(it, list) }
        return ParsedPage(items, true, list.bounds, rows.size)
    }

    private fun clean(s: String): String =
        s.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "").trim()

    /** Pruefsummen des sichtbaren Listeninhalts, um Aenderungen nach einem Scroll zu erkennen. */
    data class PageSig(val content: Int, val layout: Int, val rows: Int)

    fun signature(root: UiNode): PageSig? {
        val list = findMessageList(root) ?: return null
        val rows = list.children.filter { it.visible && it.bounds.area > 0 }.sortedBy { it.bounds.t }
        var c = 17
        var l = 17
        for (r in rows) {
            for (n in r.walk()) if (n.visible && n.hasText()) c = c * 31 + n.text.hashCode()
            l = l * 31 + r.bounds.t
            l = l * 31 + r.bounds.b
        }
        l = l * 31 + c
        return PageSig(c, l, rows.size)
    }

    /** true, wenn im Baum ein Ladehinweis (Text oder Fortschrittsanzeige) sichtbar ist. */
    fun hasLoadingHint(root: UiNode): Boolean = root.walk().any { n ->
        n.visible && n.bounds.area > 0 &&
            (n.className.contains("ProgressBar") ||
                (n.text?.let { p.isLoadingHint(it) } == true) || (n.desc?.let { p.isLoadingHint(it) } == true))
    }

    private fun parseRow(row: UiNode, list: UiNode): PageItem? {
        val tol = (2 * density).toInt().coerceAtLeast(2)
        val lt = list.bounds.t
        val lb = list.bounds.b
        val allNodes = row.walk().toList()
        val visibleNodes = allNodes.filter { it.visible || it === row }

        // Randpruefung: Beruehrt die Zeile den oberen oder unteren Listenrand und reicht Inhalt (oder ausgeblendeter Text) an den Rand,
        // gilt sie als angeschnitten. Der Text eines angeschnittenen Knotens ist meist vollstaendig, wird aber nur vorlaeufig genutzt.
        val rowTouchTop = row.bounds.t <= lt + tol
        val rowTouchBottom = row.bounds.b >= lb - tol
        val contentVisible = visibleNodes.filter { it !== row && it.bounds.area > 0 && (it.hasText() || isImageNode(it)) }
        val contentTouchTop = contentVisible.any { it.bounds.t <= lt + tol }
        val contentTouchBottom = contentVisible.any { it.bounds.b >= lb - tol }
        val hiddenText = allNodes.any { !it.visible && it.hasText() && clean(it.text!!).isNotEmpty() }
        val clipTop = rowTouchTop && (contentTouchTop || hiddenText)
        val clipBottom = rowTouchBottom && (contentTouchBottom || hiddenText)
        val clipped = clipTop || clipBottom

        // Bei angeschnittenen Zeilen auch ausgeblendete Textknoten lesen (Uhrzeit liegt oft im verdeckten Teil)
        val nodes = if (clipped) allNodes else visibleNodes
        val allText = nodes.filter { it.hasText() && clean(it.text!!).isNotEmpty() }
        val readMoreNodes = allText.filter { p.isReadMore(clean(it.text!!)) }
        var truncated = readMoreNodes.isNotEmpty()
        val textNodes = allText.filter { n -> readMoreNodes.none { it === n } }

        val minImgW = (list.bounds.width * p.imageMinWidthFractionOfList).toInt()
        val minImgH = (p.imageMinHeightDp * density).toInt()

        val imageNode = nodes
            .filter { n ->
                isImageNode(n) &&
                    n.desc?.let { d -> p.statusIconDescriptions.none { s -> d.contains(s, ignoreCase = true) } } != false &&
                    n.bounds.width >= minImgW && n.bounds.height >= minImgH
            }
            .maxByOrNull { it.bounds.area }
        val voiceNode = nodes.firstOrNull { n -> p.voiceClassHints.any { n.className.contains(it) } }

        val timeNode = textNodes
            .filter { p.isTimeText(clean(it.text!!)) }
            .maxWithOrNull(compareBy({ it.bounds.b }, { it.bounds.r }))

        // Datumstrenner: genau ein Text, passt auf ein Datumsmuster, keine Uhrzeit, kein Bild
        if (textNodes.size == 1 && timeNode == null && imageNode == null &&
            p.isDateLabel(clean(textNodes[0].text!!))
        ) {
            return PageItem(ChatMessage(Kind.DATE, Direction.UNKNOWN, null, clean(textNodes[0].text!!), null), rowBounds = row.bounds)
        }

        val idBody = if (p.useKnownIds && p.messageTextIds.isNotEmpty()) {
            textNodes.filter { it.viewId != null && it.viewId in p.messageTextIds }
        } else {
            emptyList()
        }
        val bodyPool = (if (idBody.isNotEmpty()) idBody else textNodes.filter { it !== timeNode })
        // Sichtbare Zeilen nach Position ordnen; bei angeschnittenen Zeilen (evtl. Knoten ohne brauchbare Bounds) Baumreihenfolge
        var bodyNodes = if (clipped) bodyPool else bodyPool.sortedWith(compareBy({ it.bounds.t }, { it.bounds.l }))

        val boxNodes = (bodyNodes + listOfNotNull(timeNode, imageNode, voiceNode)).filter { it.bounds.area > 0 }
        val direction = if (boxNodes.isEmpty()) {
            Direction.UNKNOWN
        } else {
            val box = boxNodes.map { it.bounds }.reduce(Bounds::union)
            val leftGap = box.l - list.bounds.l
            val rightGap = list.bounds.r - box.r
            val tolX = (p.directionToleranceDp * density).toInt()
            when {
                rightGap + tolX < leftGap -> Direction.OUT
                leftGap + tolX < rightGap -> Direction.IN
                else -> Direction.UNKNOWN
            }
        }

        var sender: String? = null
        if (groupChat && direction == Direction.IN && bodyNodes.size >= 2 && imageNode == null) {
            sender = clean(bodyNodes.first().text!!)
            bodyNodes = bodyNodes.drop(1)
        }
        var body = bodyNodes.joinToString("\n") { clean(it.text!!) }
        p.stripReadMoreSuffix(body)?.let { body = it; truncated = true }
        val time = timeNode?.let { clean(it.text!!) }

        fun finish(m: ChatMessage, imageOutside: Boolean = false, imgTop: Boolean = false, imgBottom: Boolean = false): ChatMessage {
            m.truncated = truncated
            m.incomplete = clipped || imageOutside
            m.clipTop = clipTop || imgTop
            m.clipBottom = clipBottom || imgBottom
            return m
        }

        return when {
            imageNode != null -> {
                val inside = list.bounds.contains(imageNode.bounds)
                val msg = finish(
                    ChatMessage(Kind.IMAGE, direction, sender, body, time), imageOutside = !inside,
                    imgTop = !inside && imageNode.bounds.t <= lt + tol, imgBottom = !inside && imageNode.bounds.b >= lb - tol,
                )
                PageItem(msg, imageNode.bounds, inside, row.bounds)
            }
            voiceNode != null -> PageItem(finish(ChatMessage(Kind.VOICE, direction, sender, body, time)), rowBounds = row.bounds)
            body.isEmpty() -> null
            time == null -> PageItem(finish(ChatMessage(Kind.SYSTEM, Direction.UNKNOWN, null, body, null)), rowBounds = row.bounds)
            else -> PageItem(finish(ChatMessage(Kind.TEXT, direction, sender, body, time)), rowBounds = row.bounds)
        }
    }

    private fun isImageNode(n: UiNode): Boolean = p.imageClassHints.any { n.className.contains(it) }
}
