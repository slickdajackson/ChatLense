package app.chatlens.core

/** Screen coordinates in pixels (like Rect, but without an Android dependency, so JVM-testable). */
data class Bounds(val l: Int, val t: Int, val r: Int, val b: Int) {
    val width: Int get() = r - l
    val height: Int get() = b - t
    val centerX: Int get() = (l + r) / 2
    val centerY: Int get() = (t + b) / 2
    val area: Long get() = width.toLong().coerceAtLeast(0) * height.toLong().coerceAtLeast(0)

    fun contains(o: Bounds): Boolean = o.l >= l && o.t >= t && o.r <= r && o.b <= b

    fun union(o: Bounds): Bounds = Bounds(minOf(l, o.l), minOf(t, o.t), maxOf(r, o.r), maxOf(b, o.b))

    override fun toString(): String = "[$l,$t][$r,$b]"
}

/**
 * Immutable snapshot of an accessibility node.
 * All parsers work only on this type, never on AccessibilityNodeInfo.
 */
data class UiNode(
    val className: String,
    val viewId: String?,
    val text: String?,
    val desc: String?,
    val bounds: Bounds,
    val clickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val visible: Boolean = true,
    val children: List<UiNode> = emptyList(),
    /** Input focus (isFocused). The active search has focus, a resting search field of the chat list does not. */
    val focused: Boolean = false,
    /** isSelected (for example the selected tab of the bottom bar). */
    val selected: Boolean = false,
    /** Reported scroll actions of the node (ACTION_SCROLL_UP/DOWN vertical, LEFT/RIGHT horizontal, FORWARD/BACKWARD generic). */
    val scrollUp: Boolean = false,
    val scrollDown: Boolean = false,
    val scrollHoriz: Boolean = false,
    val scrollGeneric: Boolean = false,
) {
    val hasVerticalAction: Boolean get() = scrollUp || scrollDown
    val hasAnyScrollAction: Boolean get() = scrollUp || scrollDown || scrollHoriz || scrollGeneric

    val shortClass: String get() = className.substringAfterLast('.')

    /** Depth-first walk in document order, including this. */
    fun walk(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        for (c in children) yieldAll(c.walk())
    }

    fun hasText(): Boolean = !text.isNullOrBlank()
}
