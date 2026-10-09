package app.chatlens.agent

import android.content.Context
import app.chatlens.profile.SelectorProfile
import java.io.File

/** Loads the selector profile: a custom file in app storage takes priority over the bundled asset. */
object ProfileStore {
    private const val ASSET = "profiles/whatsapp.json"

    fun overrideFile(ctx: Context) = File(ctx.filesDir, "profiles/whatsapp.json")

    fun isOverridden(ctx: Context) = overrideFile(ctx).isFile

    fun load(ctx: Context): SelectorProfile {
        val f = overrideFile(ctx)
        if (f.isFile) {
            runCatching { return SelectorProfile.parse(f.readText()) }
        }
        return SelectorProfile.parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() })
    }

    /** Validates the file before it is adopted. Throws if the content is invalid. */
    fun importOverride(ctx: Context, json: String) {
        SelectorProfile.parse(json)
        val f = overrideFile(ctx)
        f.parentFile?.mkdirs()
        f.writeText(json)
    }

    fun resetOverride(ctx: Context) {
        overrideFile(ctx).delete()
    }

    fun bundledJson(ctx: Context): String = ctx.assets.open(ASSET).bufferedReader().use { it.readText() }
}
