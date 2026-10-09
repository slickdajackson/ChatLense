package app.chatlens.memory

import org.json.JSONArray
import org.json.JSONObject

/** Abschnitte des Steckbriefs. Gewicht = Anteil am Zeichenbudget, Prioritaet = Reihenfolge, wenn der Prompt nur Teile aufnehmen kann. */
enum class MemSection(val key: String, val label: String, val weight: Int) {
    PROFILE("STECKBRIEF", "Steckbrief", 12),
    RELATIONSHIP("BEZIEHUNG", "Beziehung", 8),
    TOPICS("THEMEN", "Themen", 14),
    TONE("TON", "Ton", 8),
    OPEN("OFFEN", "Offene Punkte", 14),
    FACTS("FAKTEN", "Wichtige Fakten und Termine", 18),
    PREFS("VORLIEBEN", "Vorlieben", 10),
    MOOD("STIMMUNG", "Aktuelle Stimmung", 4),
    MOOD_HISTORY("VERLAUF", "Verlauf der Stimmung", 12),
}

/** Wofuer der Block gebraucht wird: Antworten und Beraten (Beziehung, Ton, Offenes zuerst) oder Fortschreiben (alles, Reihenfolge wie im Steckbrief). */
enum class MemFocus(val order: List<MemSection>) {
    REPLY(listOf(MemSection.OPEN, MemSection.RELATIONSHIP, MemSection.TONE, MemSection.FACTS, MemSection.PREFS, MemSection.PROFILE, MemSection.MOOD, MemSection.TOPICS, MemSection.MOOD_HISTORY)),
    UPDATE(MemSection.entries.toList()),
}

/**
 * Gedaechtnis zu einem Chat. Alle generierten Abschnitte zusammen hoechstens das einstellbare Budget ([DEFAULT_MAX], einstellbar 1500 bis 12000 Zeichen),
 * damit ein Modell spaeter ohne den ganzen Verlauf arbeiten kann. [userNote] schreibt nur der Nutzer. [disc] ist die vorsichtige DISC-Einschaetzung des Gegenuebers.
 */
data class ChatMemory(
    val chatKey: String,
    val displayName: String,
    val profile: String = "",
    val relationship: String = "",
    val openTopics: String = "",
    val mood: String = "",
    val userNote: String = "",
    val updatedAt: Long = 0L,
    /** Fingerabdruecke der zuletzt gelesenen Nachrichten (aelteste zuerst, hoechstens 3). */
    val anchor: List<String> = emptyList(),
    /** Zeitangabe der letzten gelesenen Nachricht, nur zur Anzeige. */
    val anchorTime: String = "",
    val messagesSeen: Int = 0,
    val version: Int = 2,
    val topics: String = "",
    val tone: String = "",
    val facts: String = "",
    val preferences: String = "",
    val moodHistory: String = "",
    val disc: DiscProfile? = null,
) {
    val generatedLength: Int get() = MemSection.entries.sumOf { get(it).length }

    fun get(s: MemSection): String = when (s) {
        MemSection.PROFILE -> profile
        MemSection.RELATIONSHIP -> relationship
        MemSection.TOPICS -> topics
        MemSection.TONE -> tone
        MemSection.OPEN -> openTopics
        MemSection.FACTS -> facts
        MemSection.PREFS -> preferences
        MemSection.MOOD -> mood
        MemSection.MOOD_HISTORY -> moodHistory
    }

    fun with(s: MemSection, v: String): ChatMemory = when (s) {
        MemSection.PROFILE -> copy(profile = v)
        MemSection.RELATIONSHIP -> copy(relationship = v)
        MemSection.TOPICS -> copy(topics = v)
        MemSection.TONE -> copy(tone = v)
        MemSection.OPEN -> copy(openTopics = v)
        MemSection.FACTS -> copy(facts = v)
        MemSection.PREFS -> copy(preferences = v)
        MemSection.MOOD -> copy(mood = v)
        MemSection.MOOD_HISTORY -> copy(moodHistory = v)
    }

    fun isEmpty(): Boolean = generatedLength == 0

    /**
     * Block fuer Prompts. Ohne [budget] komplett. Mit Budget kommen nur so viele Abschnitte hinein, wie passen, in der Reihenfolge von [focus]
     * (ein zu langer Abschnitt wird am Wortende gekuerzt). Die Zusatzinfo des Nutzers steht immer zuerst, die DISC-Zeile (kurz) danach.
     * Die Reihenfolge im Text bleibt die des Steckbriefs.
     */
    fun toPromptBlock(budget: Int = Int.MAX_VALUE, focus: MemFocus = MemFocus.REPLY, includeDisc: Boolean = true): String {
        val head = StringBuilder()
        if (userNote.isNotBlank()) head.append("Zusatzinfo vom Nutzer: ").append(userNote).append('\n')
        if (includeDisc && disc != null && !disc.insufficient) head.append(disc.line()).append(" (vorsichtige Einschätzung, keine Diagnose)\n")
        var left = if (budget == Int.MAX_VALUE) Int.MAX_VALUE else (budget - head.length).coerceAtLeast(0)
        val chosen = HashMap<MemSection, String>()
        for (sec in focus.order) {
            val v = get(sec)
            if (v.isBlank()) continue
            val line = sec.label + ": " + v + "\n"
            if (line.length <= left) { chosen[sec] = v; left -= line.length }
            else if (left > sec.label.length + 40) {
                val room = left - sec.label.length - 5
                chosen[sec] = v.take(room).substringBeforeLast(' ', v.take(room)).trimEnd() + "..."
                left = 0
            }
        }
        val body = StringBuilder()
        for (sec in MemSection.entries) chosen[sec]?.let { body.append(sec.label).append(": ").append(it).append('\n') }
        return (head.toString() + body).trim()
    }

    companion object {
        /** Standard-Obergrenze aller generierten Abschnitte (vorher 1500). Einstellbar in den Einstellungen. */
        const val DEFAULT_MAX = 6000
        const val MIN_MAX = 1500
        const val MAX_MAX = 12000
        const val MAX_GENERATED = DEFAULT_MAX
        const val MAX_NOTE = 800
    }
}

object MemoryCodec {
    fun toJson(m: ChatMemory): String = JSONObject().apply {
        put("v", m.version)
        put("key", m.chatKey)
        put("name", m.displayName)
        put("profile", m.profile)
        put("relationship", m.relationship)
        put("open", m.openTopics)
        put("mood", m.mood)
        put("note", m.userNote)
        put("updated", m.updatedAt)
        put("anchor", JSONArray(m.anchor))
        put("anchorTime", m.anchorTime)
        put("seen", m.messagesSeen)
        put("topics", m.topics)
        put("tone", m.tone)
        put("facts", m.facts)
        put("prefs", m.preferences)
        put("moodHistory", m.moodHistory)
        Disc.toJson(m.disc)?.let { put("disc", it) }
    }.toString()

    fun fromJson(s: String): ChatMemory {
        val o = JSONObject(s)
        val a = o.optJSONArray("anchor")
        return ChatMemory(
            chatKey = o.getString("key"),
            displayName = o.optString("name"),
            profile = o.optString("profile"),
            relationship = o.optString("relationship"),
            openTopics = o.optString("open"),
            mood = o.optString("mood"),
            userNote = o.optString("note"),
            updatedAt = o.optLong("updated"),
            anchor = (0 until (a?.length() ?: 0)).map { a!!.getString(it) },
            anchorTime = o.optString("anchorTime"),
            messagesSeen = o.optInt("seen"),
            version = o.optInt("v", 1),
            topics = o.optString("topics"),
            tone = o.optString("tone"),
            facts = o.optString("facts"),
            preferences = o.optString("prefs"),
            moodHistory = o.optString("moodHistory"),
            disc = Disc.fromJson(o.optJSONObject("disc")),
        )
    }
}
