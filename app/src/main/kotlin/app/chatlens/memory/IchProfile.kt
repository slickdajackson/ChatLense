package app.chatlens.memory

import app.chatlens.match.NameMatcher
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Categories of the self profile: only traits of the user that hold across chats, never facts from one chat. */
enum class IchCat(val key: String, val label: String) {
    STYLE("STIL", "Schreibstil"),
    TONE("TON", "Ton"),
    PHRASES("FORMULIERUNGEN", "Typische Formulierungen"),
    HUMOR("HUMOR", "Humor"),
    LANGS("SPRACHEN", "Sprachen"),
    VALUES("WERTE", "Werte"),
    INTERESTS("INTERESSEN", "Interessen"),
    WORK("ARBEIT", "Arbeitsweise"),
    DECIDE("ENTSCHEIDUNG", "Entscheidungsstil"),
}

/** [pinned]: pinned by the user or entered by the user. A user correction wins: pinned entries are never displaced or overwritten. */
data class IchEntry(val cat: IchCat, val text: String, val pinned: Boolean = false, val addedAt: Long = 0L)

/**
 * The user's self profile, stored separately from chat memory and encrypted. [suppressed] are entries the user deleted (normalized),
 * which are not taken in again. [chatHashes] counts how many chats it came from, without storing chat names.
 */
data class IchProfile(
    val entries: List<IchEntry> = emptyList(),
    val disc: DiscProfile? = null,
    val suppressed: Set<String> = emptySet(),
    val chatHashes: Set<String> = emptySet(),
    val updatedAt: Long = 0L,
    val runs: Int = 0,
) {
    val chatCount: Int get() = chatHashes.size
    val length: Int get() = entries.sumOf { it.text.length }
    fun isEmpty() = entries.isEmpty() && (disc == null || disc.insufficient)

    fun toPromptBlock(maxChars: Int = IchLogic.PROMPT_MAX): String {
        val sb = StringBuilder()
        for (cat in IchCat.entries) {
            val items = entries.filter { it.cat == cat }.sortedByDescending { it.pinned }
            if (items.isEmpty()) continue
            val line = cat.label + ": " + items.joinToString("; ") { it.text } + "\n"
            if (sb.length + line.length > maxChars) {
                val room = maxChars - sb.length - cat.label.length - 5
                if (room > 40) sb.append(cat.label).append(": ").append(line.drop(cat.label.length + 2).take(room).substringBeforeLast(';', line.take(room)).trimEnd()).append("\n")
                break
            }
            sb.append(line)
        }
        if (disc != null && !disc.insufficient && sb.length + 60 < maxChars) sb.append(disc.line()).append('\n')
        return sb.toString().trim()
    }
}

object IchLogic {
    const val MAX_CHARS = 3000
    const val PROMPT_MAX = 700
    const val MAX_ENTRY = 160

    fun norm(s: String) = NameMatcher.normalize(s).trim()

    fun hashOf(chatKey: String): String =
        MessageDigest.getInstance("SHA-256").digest(("ichsalt|" + chatKey).toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    private val digitsRe = Regex("""\d""")
    private val urlRe = Regex("""https?://|www\.|@|\.(com|de|org|net)\b""", RegexOption.IGNORE_CASE)
    private val tokenRe = Regex("""[\p{L}]{5,}""")

    /**
     * Checks a candidate for leakage from a chat. Returns the cleaned text, or null (rejected). Rejected when the text
     *  - contains a digit, address, or email address (appointments, numbers, ids, links),
     *  - contains a name from [blockedNames] (contacts, chat names, memory names; comparison ignores case, including name parts of at least three letters),
     *  - shares at least two distinctive words (five letters or more) with the chat-specific texts [chatTexts] (facts, topics, open items from this chat),
     *  - is empty or longer than [MAX_ENTRY] characters.
     */
    fun clean(raw: String, blockedNames: Set<String>, chatTexts: List<String>): String? {
        val t = raw.replace(Regex("\\s+"), " ").trim().trim('-', '*', ' ', '.').trim()
        if (t.isEmpty() || t.length > MAX_ENTRY) return null
        if (digitsRe.containsMatchIn(t) || urlRe.containsMatchIn(t)) return null
        val words = t.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }.map { it.lowercase() }
        val nameParts = blockedNames.flatMap { n -> n.split(Regex("[^\\p{L}]+")).filter { it.length >= 3 }.map { it.lowercase() } }.toSet()
        if (words.any { it in nameParts }) return null
        val mine = tokenRe.findAll(t).map { it.value.lowercase() }.toSet()
        if (mine.isNotEmpty()) {
            val chat = chatTexts.flatMap { c -> tokenRe.findAll(c).map { it.value.lowercase() }.toList() }.toSet()
            if (mine.count { it in chat } >= 2) return null
        }
        return t
    }

    private fun dup(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(b)
        return x == y || (x.length > 12 && y.contains(x)) || (y.length > 12 && x.contains(y))
    }

    /**
     * Merge after a run. [found] is the model's candidates (category to texts). Each candidate goes through [clean].
     * Deleted entries do not come back. Duplicates are folded together (pinned wins). Above the cap, the oldest
     * unpinned entries go first. [chatKey] enters the "how many chats" counter only as a hash.
     */
    fun merge(
        old: IchProfile, found: Map<IchCat, List<String>>, ownDisc: DiscProfile?, chatKey: String,
        blockedNames: Set<String>, chatTexts: List<String>, now: Long, maxChars: Int = MAX_CHARS,
    ): IchProfile {
        val entries = old.entries.toMutableList()
        var added = 0
        for ((cat, list) in found) for (raw in list) {
            val t = clean(raw, blockedNames, chatTexts) ?: continue
            if (old.suppressed.contains(norm(t))) continue
            val ex = entries.indexOfFirst { it.cat == cat && dup(it.text, t) }
            if (ex >= 0) {
                // Existing entry stays (pinned unchanged). Only the timestamp moves forward, so it is not the first to be dropped.
                entries[ex] = entries[ex].copy(addedAt = now)
            } else { entries.add(IchEntry(cat, t, false, now)); added++ }
        }
        // Cap: oldest unpinned first
        var total = entries.sumOf { it.text.length }
        if (total > maxChars) {
            val victims = entries.filter { !it.pinned }.sortedBy { it.addedAt }
            for (v in victims) { if (total <= maxChars) break; entries.remove(v); total -= v.text.length }
        }
        val hashes = if (chatKey.isBlank()) old.chatHashes else old.chatHashes + hashOf(chatKey)
        return old.copy(
            entries = entries, disc = if (ownDisc != null) Disc.merge(old.disc, ownDisc) else old.disc,
            chatHashes = hashes, updatedAt = now, runs = old.runs + 1,
        )
    }

    /** Apply the self-analysis proposal after confirmation: like a run, but without chat names. The proposal's chats still count. */
    fun adopt(old: IchProfile, proposal: IchProfile, blockedNames: Set<String>, now: Long, maxChars: Int = MAX_CHARS): IchProfile {
        val found = proposal.entries.groupBy({ it.cat }, { it.text })
        val m = merge(old, found, proposal.disc, "", blockedNames, emptyList(), now, maxChars)
        return m.copy(chatHashes = old.chatHashes + proposal.chatHashes)
    }

    fun pin(p: IchProfile, e: IchEntry, pinned: Boolean): IchProfile = p.copy(entries = p.entries.map { if (it == e) it.copy(pinned = pinned) else it })

    /** Deletion by the user: the entry is remembered and not taken in again. */
    fun delete(p: IchProfile, e: IchEntry): IchProfile = p.copy(entries = p.entries - e, suppressed = p.suppressed + norm(e.text))

    /** The user's own entry: always pinned, and it lifts an earlier deletion of this text. The same check applies to length only. */
    fun addByUser(p: IchProfile, cat: IchCat, text: String, now: Long): IchProfile {
        val t = text.replace(Regex("\\s+"), " ").trim().take(MAX_ENTRY)
        if (t.isEmpty() || p.entries.any { it.cat == cat && norm(it.text) == norm(t) }) return p
        return p.copy(entries = p.entries + IchEntry(cat, t, true, now), suppressed = p.suppressed - norm(t), updatedAt = now)
    }

    /** Names that must never appear in the self profile: chat names, contacts from the checkup list, and names from memory. */
    fun blockedFrom(vararg lists: Collection<String>): Set<String> = lists.flatMap { it }.map { it.trim() }.filter { it.length >= 3 }.toSet()

    /** Message-free text of the chat sections, checked for leakage. */
    fun chatTextsOf(m: ChatMemory?): List<String> = if (m == null) emptyList() else listOf(m.profile, m.topics, m.facts, m.openTopics, m.relationship, m.preferences, m.moodHistory, m.userNote)

    /**
     * Candidates from the model reply: lines "ICH-STIL: a; b", "ICH-TON: ...", and so on. Also returns the DISC line "ICH-DISC: ...".
     */
    fun parseOutput(output: String): Pair<Map<IchCat, List<String>>, String?> {
        val out = LinkedHashMap<IchCat, MutableList<String>>()
        var disc: String? = null
        for (line in output.lines()) {
            val m = Regex("""^\s*[*#\-\s]*ICH[- ]([A-ZÄÖÜ]+)\s*[:*]+\s*(.*)$""", RegexOption.IGNORE_CASE).matchEntire(line) ?: continue
            val key = m.groupValues[1].uppercase()
            val v = m.groupValues[2].trim('*', ' ')
            if (key == "DISC") { disc = v; continue }
            val cat = IchCat.entries.firstOrNull { it.key == key } ?: continue
            v.split(';').map { it.trim() }.filter { it.isNotEmpty() && !it.equals("keine", true) && !it.equals("unklar", true) }.forEach { out.getOrPut(cat) { ArrayList() }.add(it) }
        }
        return out to disc
    }
}

object IchCodec {
    fun toJson(p: IchProfile): String = JSONObject().apply {
        put("v", 1)
        put("entries", JSONArray(p.entries.map { JSONObject().put("c", it.cat.name).put("t", it.text).put("p", it.pinned).put("a", it.addedAt) }))
        Disc.toJson(p.disc)?.let { put("disc", it) }
        put("sup", JSONArray(p.suppressed.toList()))
        put("hash", JSONArray(p.chatHashes.toList()))
        put("at", p.updatedAt)
        put("runs", p.runs)
    }.toString()

    fun fromJson(s: String): IchProfile {
        val o = JSONObject(s)
        val ea = o.optJSONArray("entries")
        val entries = (0 until (ea?.length() ?: 0)).mapNotNull { i ->
            val e = ea!!.getJSONObject(i)
            val cat = runCatching { IchCat.valueOf(e.optString("c")) }.getOrNull() ?: return@mapNotNull null
            IchEntry(cat, e.optString("t"), e.optBoolean("p"), e.optLong("a"))
        }
        fun set(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
        return IchProfile(entries, Disc.fromJson(o.optJSONObject("disc")), set("sup"), set("hash"), o.optLong("at"), o.optInt("runs"))
    }
}
