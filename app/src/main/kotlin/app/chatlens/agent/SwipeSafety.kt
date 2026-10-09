package app.chatlens.agent

import app.chatlens.core.Bounds

/** Bildschirmmasse fuer die Gestenplanung (Pixel). [statusBar] Hoehe der Statusleiste, [gestureBottom] Hoehe der unteren Systemgestenzone. */
class ScreenInsets(val width: Int, val height: Int, val statusBar: Int, val gestureBottom: Int)

/**
 * Sicherheitsfenster fuer Wischgesten. Eine Geste darf nie nahe an Systemzonen beginnen oder enden, sonst reagiert das System statt WhatsApp:
 * oben die Benachrichtigungsleiste und das Kontrollzentrum, unten die Gestennavigation (Home, Zuletzt benutzt), seitlich die Zurueck-Geste.
 *
 * Regeln: Abstand zur Oberkante mindestens max(12 Prozent der Bildschirmhoehe, Statusleiste + 150 px), zur Unterkante mindestens
 * max(12 Prozent, Gestenzone + 250 px), seitlich mindestens 15 Prozent der Breite. Zusaetzlich bleibt jede Geste innerhalb der Listenbounds
 * (mit 4 Prozent Rand). Reicht die Strecke nicht, wird sie in mehrere Wischer gleicher Laenge geteilt; ein Wischer ist hoechstens
 * [MAX_SEGMENT] des nutzbaren Fensters lang.
 */
object SwipeSafety {
    const val MIN_EDGE_FRACTION = 0.12

    /** Wischstart nie in den oberen 15 Prozent des Bildschirms (Benachrichtigungszone, Heads-up-Karten). Seit 0.2.9. */
    const val TOP_START_FRACTION = 0.15

    /** Wisch zu aelteren Nachrichten (Finger nach unten) startet erst ab diesem Anteil der Fensterhoehe von oben, nie am oberen Fensterrand. Seit 0.2.9. */
    const val OLDER_START_OFFSET = 0.25
    const val STATUS_EXTRA_PX = 150
    const val GESTURE_EXTRA_PX = 250
    const val SIDE_FRACTION = 0.15
    const val LIST_MARGIN = 0.04
    const val MAX_SEGMENT = 0.70

    /** Erlaubter y-Bereich (oben, unten) fuer Gesten dieser Liste. Kann leer sein (oben >= unten). */
    fun window(list: Bounds, ins: ScreenInsets): Pair<Int, Int> {
        val topLimit = maxOf((ins.height * TOP_START_FRACTION).toInt(), ins.statusBar + STATUS_EXTRA_PX)
        val bottomLimit = ins.height - maxOf((ins.height * MIN_EDGE_FRACTION).toInt(), ins.gestureBottom + GESTURE_EXTRA_PX)
        val top = maxOf(list.t + (list.height * LIST_MARGIN).toInt(), topLimit)
        val bottom = minOf(list.b - (list.height * LIST_MARGIN).toInt(), bottomLimit)
        return top to bottom
    }

    /** x der Geste: Listenmitte, aber nie naeher als 15 Prozent der Breite am Rand. */
    fun x(list: Bounds, ins: ScreenInsets): Int =
        list.centerX.coerceIn((ins.width * SIDE_FRACTION).toInt(), (ins.width * (1 - SIDE_FRACTION)).toInt())

    /** Liegt ein Punkt (z. B. ein Tippziel) im erlaubten Bereich? */
    fun pointSafe(xv: Int, yv: Int, ins: ScreenInsets, list: Bounds? = null): Boolean {
        val topLimit = maxOf((ins.height * MIN_EDGE_FRACTION).toInt(), ins.statusBar + STATUS_EXTRA_PX)
        val bottomLimit = ins.height - maxOf((ins.height * MIN_EDGE_FRACTION).toInt(), ins.gestureBottom + GESTURE_EXTRA_PX)
        if (yv < topLimit || yv > bottomLimit) return false
        if (xv < (ins.width * 0.05).toInt() || xv > (ins.width * 0.95).toInt()) return false
        if (list != null && (yv < list.t || yv > list.b)) return false
        return true
    }

    /** Teilstrecken: gesamt [distancePx] (wird auf das Fenster begrenzt), aeltere Nachrichten = Finger nach unten ([older]). */
    fun plan(list: Bounds, ins: ScreenInsets, older: Boolean, distancePx: Int, maxSegment: Double = MAX_SEGMENT): List<Swipe> {
        val (top, bottom) = window(list, ins)
        val usable = bottom - top
        if (usable < 100) return emptyList()
        val x = x(list, ins)
        // Aeltere Nachrichten: Start erst bei 25 Prozent der Fensterhoehe, damit der Finger nie knapp unter der Benachrichtigungszone aufsetzt.
        // Der Weg nach unten ist dann kuerzer; bei Bedarf teilt der Plan in zwei Wischer.
        val startOffset = if (older) (usable * OLDER_START_OFFSET).toInt() else 0
        val room = usable - startOffset
        val segMax = minOf((usable * maxSegment).toInt(), room).coerceAtLeast(50)
        val total = distancePx.coerceIn(1, minOf((usable * 1.9).toInt(), 2 * segMax)) // hoechstens zwei Wischer
        val n = (total + segMax - 1) / segMax
        val seg = (total / n).coerceAtLeast(1)
        return List(n) { if (older) Swipe(x, top + startOffset, top + startOffset + seg) else Swipe(x, bottom, bottom - seg) }
    }
}
