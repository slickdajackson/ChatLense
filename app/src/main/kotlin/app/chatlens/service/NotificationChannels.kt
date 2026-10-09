package app.chatlens.service

import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * Benachrichtigungskanaele von ChatLens. Seit 0.2.9 alle ruhig (IMPORTANCE_LOW): kein Heads-up, kein Ton, keine Vibration.
 * Bis 0.2.8 hatte der Kanal "armed" die Wichtigkeit HIGH; dessen Karte erschien oben ueber WhatsApp, genau dort, wo die Wischgesten des Lesens
 * beginnen. Einstellungen eines bestehenden Kanals lassen sich per App nicht senken, deshalb neue IDs und Loeschen der alten.
 */
object NotificationChannels {
    const val RUN = "run2"
    const val ARMED = "armed2"
    const val DONE = "done2"
    val OLD = listOf("armed", "run", "done")

    class Def(val id: String, val name: String, val importance: Int)

    val ALL = listOf(
        Def(RUN, "Laufender Auftrag", NotificationManager.IMPORTANCE_LOW),
        Def(ARMED, "Bereit zum Lesen und Rueckfragen (leise)", NotificationManager.IMPORTANCE_LOW),
        Def(DONE, "Ergebnis (leise)", NotificationManager.IMPORTANCE_LOW),
    )

    fun create(nm: NotificationManager) {
        OLD.forEach { runCatching { nm.deleteNotificationChannel(it) } }
        for (d in ALL) {
            val ch = NotificationChannel(d.id, d.name, d.importance)
            ch.setSound(null, null)
            ch.enableVibration(false)
            ch.enableLights(false)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }
}
