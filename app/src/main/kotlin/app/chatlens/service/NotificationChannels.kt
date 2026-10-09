package app.chatlens.service

import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * ChatLens notification channels. Since 0.2.9 all of them are quiet (IMPORTANCE_LOW): no heads-up, no sound, no vibration.
 * Until 0.2.8 the "armed" channel had importance HIGH; its card appeared at the top over WhatsApp, exactly where the reading swipes
 * start. An app cannot lower the settings of an existing channel, so new IDs are used and the old ones are deleted.
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
