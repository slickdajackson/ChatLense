package app.chatlens.agent

import app.chatlens.asr.VoiceReport

/** Merkt sich den Stand des letzten Sprachnachrichten-Schritts fuer das Markdown-Log. Kein Transkripttext, keine Dateinamen. */
object VoiceState {
    @Volatile private var last: VoiceReport? = null
    @Volatile private var note: String = ""

    fun set(r: VoiceReport?, note: String = "") { last = r; this.note = note }

    fun markdown(): String {
        val r = last
        if (r == null && note.isEmpty()) return ""
        val sb = StringBuilder()
        if (note.isNotEmpty()) sb.append(note).append("\n\n")
        if (r != null) {
            sb.append("- ").append(r.summary()).append("\n")
            if (r.reasons.isNotEmpty()) sb.append("- Gruende ohne Transkript: ").append(r.reasons.entries.joinToString("; ") { "${it.key} x${it.value}" }).append("\n")
        }
        sb.append("\nDie Texte der Sprachnachrichten und die Dateinamen stehen nicht im Log.")
        return sb.toString()
    }
}
