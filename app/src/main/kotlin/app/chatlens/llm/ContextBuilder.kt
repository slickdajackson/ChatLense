package app.chatlens.llm

import app.chatlens.core.ChatMessage
import app.chatlens.core.Direction
import app.chatlens.core.Kind

class BuiltContext(
    val transcript: String,
    /** Images in the order they are mentioned ("Bild 1" is images[0]). */
    val images: List<ChatMessage>,
    val messageCount: Int,
    val droppedOldest: Int,
    val notes: List<String>,
)

object ContextBuilder {

    private fun who(m: ChatMessage, chatTitle: String): String = when (m.direction) {
        Direction.OUT -> "Ich"
        Direction.IN -> m.sender?.takeIf { it.isNotBlank() } ?: chatTitle.ifBlank { "Gegenüber" }
        Direction.UNKNOWN -> "?"
    }

    /** Marker for truncated messages or messages cut off at the edge (empty when complete). */
    fun flags(m: ChatMessage): String = buildString {
        if (m.truncated) append(" [gekürzt: in WhatsApp mit \"Mehr lesen\" abgeschnitten, Text nur teilweise]")
        if (m.incomplete) append(" [angeschnitten: am Bildschirmrand gesehen, evtl. unvollständig]")
    }

    fun line(m: ChatMessage, chatTitle: String, imageNo: Int?): String = lineCore(m, chatTitle, imageNo) +
        if (m.kind == Kind.TEXT || m.kind == Kind.IMAGE || m.kind == Kind.VOICE || m.kind == Kind.SYSTEM) flags(m) else ""

    private fun lineCore(m: ChatMessage, chatTitle: String, imageNo: Int?): String = when (m.kind) {
        Kind.DATE -> "--- ${m.text} ---"
        Kind.GAP -> "[${m.text}]"
        Kind.SYSTEM -> "[Hinweis] ${m.text}"
        Kind.VOICE -> "[${m.time ?: "?"}] ${who(m, chatTitle)}: [Sprachnachricht${if (m.text.isNotBlank()) " ${m.text}" else ""}" +
            (if (!m.transcript.isNullOrBlank()) "; Transkript: ${m.transcript}" else "; nicht transkribiert") + "]"
        Kind.IMAGE -> {
            val label = if (imageNo != null) "Bild $imageNo" else "Bild (nicht erfasst${m.imageNote?.let { ": $it" } ?: ""})"
            val parts = buildList {
                add(label)
                if (m.text.isNotBlank()) add("Beschriftung: ${m.text}")
                if (!m.ocrText.isNullOrBlank()) add("Text im Bild (OCR): ${m.ocrText!!.replace('\n', ' ')}")
            }
            "[${m.time ?: "?"}] ${who(m, chatTitle)}: [${parts.joinToString("; ")}]"
        }
        Kind.TEXT -> "[${m.time ?: "?"}] ${who(m, chatTitle)}: ${m.text}"
    }

    /**
     * Builds the chat text. If it exceeds [maxChars], the oldest lines are dropped
     * (the chat always ends with the newest messages).
     * [includeImages] decides whether image files are numbered as "Bild n" (API with vision only).
     */
    fun build(
        messages: List<ChatMessage>,
        chatTitle: String,
        maxChars: Int,
        includeImages: Boolean,
        maxImages: Int,
    ): BuiltContext {
        val lines = ArrayList<Pair<ChatMessage, String>>()
        val imgs = ArrayList<ChatMessage>()
        // First without numbers, then trim, then assign numbers (numbers refer only to kept lines)
        var start = 0
        var total = 0
        val raw = messages.map { it to line(it, chatTitle, null) }
        // fill from the back (newest)
        var idx = raw.size
        while (idx > 0) {
            val len = raw[idx - 1].second.length + 1
            if (total + len > maxChars && idx < raw.size) break
            total += len
            idx--
        }
        start = idx
        val kept = raw.subList(start, raw.size)
        for ((m, _) in kept) {
            val no = if (includeImages && m.kind == Kind.IMAGE && m.imagePath != null && imgs.size < maxImages) {
                imgs.add(m)
                imgs.size
            } else {
                null
            }
            lines.add(m to line(m, chatTitle, no))
        }
        val notes = ArrayList<String>()
        if (start > 0) notes.add("$start älteste Zeilen wegen Kontextlimit ($maxChars Zeichen) weggelassen.")
        val nTrunc = messages.count { it.truncated }
        if (nTrunc > 0) notes.add("$nTrunc Nachricht(en) sind mit [gekürzt] markiert (in WhatsApp mit \"Mehr lesen\" abgeschnitten, der Rest war nicht lesbar).")
        val nInc = messages.count { it.incomplete }
        if (nInc > 0) notes.add("$nInc Nachricht(en) sind mit [angeschnitten] markiert (nie vollständig sichtbar gesehen, Text evtl. unvollständig).")
        if (messages.any { it.kind == Kind.GAP }) notes.add("Der Verlauf enthält mindestens eine mögliche Lücke.")
        if (messages.any { it.direction == Direction.UNKNOWN && (it.kind == Kind.TEXT || it.kind == Kind.IMAGE) }) {
            notes.add("Bei manchen Nachrichten ließ sich die Richtung (Ich oder Gegenüber) nicht bestimmen; sie sind mit ? markiert.")
        }
        return BuiltContext(
            transcript = lines.joinToString("\n") { it.second },
            images = imgs,
            messageCount = kept.count { it.first.kind in setOf(Kind.TEXT, Kind.IMAGE, Kind.VOICE) },
            droppedOldest = start,
            notes = notes,
        )
    }
}
