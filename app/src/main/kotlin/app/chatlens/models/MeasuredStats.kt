package app.chatlens.models

import android.content.Context

/**
 * Runtime of a local model measured on this device (separate from our estimate). Per model file: number of runs, mean in seconds
 * per 1000 characters of input, and the last run. The time excludes loading the model. Only numbers are stored, no contents.
 */
data class Measured(val runs: Int, val avgSecPer1k: Double, val lastSeconds: Double, val lastChars: Int)

object MeasuredStats {
    fun update(old: Measured?, seconds: Double, chars: Int): Measured? {
        if (seconds <= 0.0 || chars <= 0) return old
        val per1k = seconds / (chars / 1000.0)
        val n = (old?.runs ?: 0) + 1
        val avg = if (old == null) per1k else (old.avgSecPer1k * old.runs + per1k) / n
        return Measured(n, avg, seconds, chars)
    }

    fun encode(m: Measured) = "${m.runs}|${m.avgSecPer1k}|${m.lastSeconds}|${m.lastChars}"

    fun decode(s: String?): Measured? = runCatching {
        val p = s!!.split("|")
        Measured(p[0].toInt(), p[1].toDouble(), p[2].toDouble(), p[3].toInt())
    }.getOrNull()

    fun describe(m: Measured): String = String.format(
        java.util.Locale.GERMANY, "Auf diesem Gerät gemessen: %d Lauf/Läufe, im Mittel %.1f s je 1000 Zeichen Eingabe, letzter Lauf %.0f s für %d Zeichen.",
        m.runs, m.avgSecPer1k, m.lastSeconds, m.lastChars,
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("measured", Context.MODE_PRIVATE)

    fun load(ctx: Context, fileName: String): Measured? = decode(prefs(ctx).getString(fileName, null))

    fun record(ctx: Context, fileName: String, seconds: Double, chars: Int) {
        val n = update(load(ctx, fileName), seconds, chars) ?: return
        prefs(ctx).edit().putString(fileName, encode(n)).apply()
    }
}
