package app.chatlens.ui

import android.content.Context

/** Datenschutzerklaerung als Text in der App (Entwurf in assets/datenschutz.md; mit PRIVACY_URL ersetzt der Browserlink sie). */
object PrivacyPolicy {
    fun load(ctx: Context): String = runCatching {
        ctx.assets.open("datenschutz.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrDefault("Datenschutzerklärung nicht verfügbar.")
}
