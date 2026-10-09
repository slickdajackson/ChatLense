package app.chatlens.prompts

import org.json.JSONArray
import org.json.JSONObject

enum class PromptMode { STANDARD, CUSTOM }

class SavedPrompt(val name: String, val text: String) {
    override fun equals(other: Any?) = other is SavedPrompt && other.name == name && other.text == text
    override fun hashCode() = name.hashCode() * 31 + text.hashCode()
}

/**
 * Prompt book for "Analysieren": last chosen kind, recently used custom prompts, and custom templates.
 * Stored encrypted like memory (MemoryRepo). Immutable: every change returns a new book.
 */
class PromptBook(
    val lastMode: PromptMode = PromptMode.STANDARD,
    val lastText: String = "",
    val recent: List<String> = emptyList(),
    val saved: List<SavedPrompt> = emptyList(),
) {
    /** After a choice: remember the kind, and for a custom prompt add it to the recently used list (newest first, no duplicates). */
    fun withUsed(mode: PromptMode, text: String): PromptBook {
        if (mode == PromptMode.STANDARD) return PromptBook(PromptMode.STANDARD, lastText, recent, saved)
        val t = PromptLimits.clean(text)
        if (t.isEmpty()) return this
        val list = (listOf(t) + recent.filter { it != t }).take(PromptLimits.MAX_RECENT)
        return PromptBook(PromptMode.CUSTOM, t, list, saved)
    }

    /** Save a template or replace one with the same name (name comparison ignores case). Empty name or text: no change. */
    fun withSaved(name: String, text: String): PromptBook {
        val n = name.trim().take(PromptLimits.MAX_NAME)
        val t = PromptLimits.clean(text)
        if (n.isEmpty() || t.isEmpty()) return this
        val rest = saved.filter { !it.name.equals(n, ignoreCase = true) }
        return PromptBook(lastMode, lastText, recent, (listOf(SavedPrompt(n, t)) + rest).take(PromptLimits.MAX_SAVED))
    }

    fun withoutSaved(name: String) = PromptBook(lastMode, lastText, recent, saved.filter { !it.name.equals(name, ignoreCase = true) })

    fun withoutRecent(text: String) = PromptBook(lastMode, lastText, recent.filter { it != text }, saved)
}

object PromptLimits {
    const val MAX_LEN = 4000
    const val MAX_RECENT = 10
    const val MAX_SAVED = 20
    const val MAX_NAME = 40

    fun clean(s: String): String = s.replace("\r", "").trim().take(MAX_LEN)
}

/** Quick templates, built in (not stored). They are task descriptions that receive the chat transcript as data. */
object QuickPrompts {
    val all: List<SavedPrompt> = listOf(
        SavedPrompt("Kurz zusammenfassen", "Fasse den Chat in höchstens fünf Stichpunkten zusammen und nenne danach, was zuletzt offen geblieben ist."),
        SavedPrompt("Offene Fragen und Aufgaben", "Liste alle offenen Fragen, Bitten und Aufgaben auf. Gib an, wer sie gestellt hat und ob sie schon beantwortet wurden."),
        SavedPrompt("Termine und Fristen", "Suche alle Termine, Uhrzeiten, Orte und Fristen heraus. Belege jeden Punkt mit einem kurzen Zitat."),
        SavedPrompt("Stimmung und Ton", "Beschreibe Stimmung und Ton des Gegenübers und wie sie sich im Verlauf ändern. Trenne Beobachtung von Einschätzung."),
        SavedPrompt("Was erwartet das Gegenüber", "Was erwartet das Gegenüber von mir? Nenne Erwartungen, Wünsche und mögliche Absichten mit Belegen."),
    )
}

object PromptCodec {
    fun toJson(b: PromptBook): String = JSONObject().apply {
        put("v", 1)
        put("lastMode", b.lastMode.name)
        put("lastText", b.lastText)
        put("recent", JSONArray(b.recent))
        put("saved", JSONArray(b.saved.map { JSONObject().put("name", it.name).put("text", it.text) }))
    }.toString()

    fun fromJson(json: String): PromptBook {
        val o = JSONObject(json)
        val recent = o.optJSONArray("recent")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
        val saved = o.optJSONArray("saved")?.let { a -> List(a.length()) { a.getJSONObject(it).let { x -> SavedPrompt(x.getString("name"), x.getString("text")) } } }.orEmpty()
        val mode = runCatching { PromptMode.valueOf(o.optString("lastMode", "STANDARD")) }.getOrDefault(PromptMode.STANDARD)
        return PromptBook(mode, o.optString("lastText"), recent.take(PromptLimits.MAX_RECENT), saved.take(PromptLimits.MAX_SAVED))
    }
}
