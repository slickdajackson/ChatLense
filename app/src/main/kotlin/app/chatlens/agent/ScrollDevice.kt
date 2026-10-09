package app.chatlens.agent

import app.chatlens.core.Bounds
import app.chatlens.core.UiNode

/**
 * Schnittstelle zum Geraet: Baum lesen und die Liste bewegen. Die echte Umsetzung ist [AndroidScrollDevice];
 * Tests nutzen ein simuliertes Geraet (mit Nachschwung, Ueberschwingen und Seitenspruengen).
 */
interface ScrollDevice {
    fun snapshot(): UiNode?

    /** true: Rueckwaertsscrollen (zu aelteren Nachrichten) noch moeglich. false: nicht mehr. null: unbekannt. */
    fun canScrollBack(list: Bounds?): Boolean?

    fun canScrollForward(list: Bounds?): Boolean?

    /** Wischgeste mit geplantem Weg. [older]=true: Finger nach unten, aeltere Nachrichten erscheinen. Gibt zurueck, ob die Geste ausgefuehrt wurde. */
    suspend fun swipe(list: Bounds, older: Boolean, distancePx: Int, durationMs: Long, holdMs: Long): Boolean

    /** ACTION_SCROLL_BACKWARD (older=true) bzw. ACTION_SCROLL_FORWARD (older=false): eine Seite. */
    suspend fun action(list: Bounds, older: Boolean): Boolean
}
