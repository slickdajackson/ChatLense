package app.chatlens.ui

import android.content.Context

/** Privacy policy as in-app text (draft in assets/datenschutz.md; with PRIVACY_URL the browser link replaces it). */
object PrivacyPolicy {
    fun load(ctx: Context): String = runCatching {
        ctx.assets.open("datenschutz.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrDefault("Datenschutzerklärung nicht verfügbar.")
}
