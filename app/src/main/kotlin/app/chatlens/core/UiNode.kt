package app.chatlens.core

/** Bildschirmkoordinaten in Pixeln (wie Rect, aber ohne Android-Abhaengigkeit, damit JVM-testbar). */
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
 * Unveraenderlicher Schnappschuss eines Accessibility-Knotens.
 * Alle Parser arbeiten nur auf diesem Typ, nie auf AccessibilityNodeInfo.
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
    /** Eingabefokus (isFocused). Die aktive Suche hat Fokus, ein ruhendes Suchfeld der Chatliste nicht. */
    val focused: Boolean = false,
    /** isSelected (z. B. der gewaehlte Tab der unteren Leiste). */
    val selected: Boolean = false,
    /** Gemeldete Scrollaktionen des Knotens (ACTION_SCROLL_UP/DOWN senkrecht, LEFT/RIGHT waagerecht, FORWARD/BACKWARD allgemein). */
    val scrollUp: Boolean = false,
    val scrollDown: Boolean = false,
    val scrollHoriz: Boolean = false,
    val scrollGeneric: Boolean = false,
) {
    val hasVerticalAction: Boolean get() = scrollUp || scrollDown
    val hasAnyScrollAction: Boolean get() = scrollUp || scrollDown || scrollHoriz || scrollGeneric

    val shortClass: String get() = className.substringAfterLast('.')

    /** Tiefensuche in Dokumentreihenfolge, inklusive this. */
    fun walk(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        for (c in children) yieldAll(c.walk())
    }

    fun hasText(): Boolean = !text.isNullOrBlank()
}
