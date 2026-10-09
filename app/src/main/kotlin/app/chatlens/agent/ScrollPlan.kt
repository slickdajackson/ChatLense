package app.chatlens.agent

import app.chatlens.core.Bounds

/** Wischweg in Bildschirmkoordinaten. */
data class Swipe(val x: Int, val fromY: Int, val toY: Int) {
    val distance: Int get() = kotlin.math.abs(toY - fromY)
}

/**
 * Plant den Wischweg so, dass die Schrittweite hoechstens [MAX_STEP] der Listenhoehe betraegt.
 * Dadurch ueberlappen aufeinanderfolgende Seiten mindestens um 30 Prozent der Listenhoehe, und eine Nachricht
 * bis zu 30 Prozent der Listenhoehe war garantiert mindestens einmal komplett sichtbar. Hoehere Nachrichten
 * (lange Texte, grosse Bilder) koennen angeschnitten bleiben und werden dann markiert.
 * Hinweis: Der tatsaechliche Scrollweg ist um die Touch-Schwelle des Systems (etwa 8 dp) kleiner als der Wischweg.
 */
object ScrollPlan {
    const val MAX_STEP = 0.70
    const val MIN_STEP = 0.30
    private const val MARGIN = 0.12
    private const val EDGE = 0.06

    /** Groesste befohlene Wischstrecke. Der gemessene Scrollweg wird separat auf hoechstens ca. 70 Prozent geregelt. */
    const val MAX_COMMAND = 0.85

    fun clampStep(fraction: Double): Double = fraction.coerceIn(MIN_STEP, MAX_STEP)

    /** Finger faehrt nach unten: der Inhalt rutscht nach unten, aeltere Nachrichten erscheinen oben. */
    fun older(list: Bounds, fraction: Double): Swipe {
        val h = list.height
        val from = list.t + (h * MARGIN).toInt()
        val to = from + (h * clampStep(fraction)).toInt()
        return Swipe(list.centerX, from, to)
    }

    /** Wischweg mit gewuenschter Strecke in Pixeln; die Strecke wird auf 85 Prozent der Listenhoehe begrenzt, der Weg bleibt in der Liste. */
    fun olderPx(list: Bounds, distancePx: Int): Swipe {
        val h = list.height
        val d = distancePx.coerceIn(1, (h * MAX_COMMAND).toInt())
        val from = list.t + (h * EDGE).toInt()
        return Swipe(list.centerX, from, from + d)
    }

    fun newerPx(list: Bounds, distancePx: Int): Swipe {
        val h = list.height
        val d = distancePx.coerceIn(1, (h * MAX_COMMAND).toInt())
        val from = list.b - (h * EDGE).toInt()
        return Swipe(list.centerX, from, from - d)
    }

    /** Finger faehrt nach oben: neuere Nachrichten erscheinen wieder (Rueckschritt). */
    fun newer(list: Bounds, fraction: Double): Swipe {
        val h = list.height
        val from = list.b - (h * MARGIN).toInt()
        val to = from - (h * clampStep(fraction)).toInt()
        return Swipe(list.centerX, from, to)
    }
}

/**
 * Abbruchregel fuer das Rueckwaertsscrollen.
 * Ein Fehlversuch ist ein Scroll ohne neue Nachrichten. Abgebrochen wird erst nach [failuresNeeded] Fehlversuchen
 * hintereinander UND einem echten Chatanfang-Signal (Verschluesselungshinweis, Scroll nicht mehr moeglich,
 * oder unveraenderter Baum nach langer Wartezeit). Ohne Signal gilt erst nach [hardFailures] Fehlversuchen "festgefahren".
 */
class ScrollStopPolicy(private val failuresNeeded: Int = 3, private val hardFailures: Int = 6) {
    enum class Decision { CONTINUE, CHAT_START, STUCK }

    var failures = 0
        private set

    fun record(newContent: Boolean) {
        if (newContent) failures = 0 else failures++
    }

    /** Ab dem dritten Fehlversuch lohnt eine lange Wartezeit, um "unveraendert" sicher zu belegen. */
    fun wantsLongWait(): Boolean = failures >= failuresNeeded

    fun decide(startSignal: Boolean): Decision = when {
        failures >= failuresNeeded && startSignal -> Decision.CHAT_START
        failures >= hardFailures -> Decision.STUCK
        else -> Decision.CONTINUE
    }
}
