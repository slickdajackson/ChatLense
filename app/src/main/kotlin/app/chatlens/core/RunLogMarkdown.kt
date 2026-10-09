package app.chatlens.core

/** Builds the Markdown log of a run: header (version, date, device, mode), settings, result, and the log lines. Pure text logic. */
object RunLogMarkdown {
    fun build(
        header: List<Pair<String, String>>,
        settings: List<Pair<String, String>>,
        outcome: String,
        lines: List<String>,
        sections: List<Pair<String, String>> = emptyList(),
    ): String {
        val sb = StringBuilder()
        sb.append("# ChatLens Lauf-Log\n\n")
        sb.append("| Feld | Wert |\n|---|---|\n")
        header.forEach { (k, v) -> sb.append("| ").append(cell(k)).append(" | ").append(cell(v)).append(" |\n") }
        sb.append("\n## Einstellungen\n\n")
        sb.append("| Einstellung | Wert |\n|---|---|\n")
        settings.forEach { (k, v) -> sb.append("| ").append(cell(k)).append(" | ").append(cell(v)).append(" |\n") }
        sb.append("\n## Ergebnis\n\n").append(outcome.ifBlank { "(Lauf noch nicht beendet oder kein Ergebnis gemeldet)" }).append("\n")
        sections.forEach { (t, body) -> sb.append("\n## ").append(t.replace('\n', ' ')).append("\n\n").append(body).append("\n") }
        sb.append("\n## Log (").append(lines.size).append(" Zeilen, ohne Chatinhalte)\n\n")
        sb.append("```text\n")
        lines.forEach { sb.append(it.replace("```", "'''")).append('\n') }
        sb.append("```\n")
        return sb.toString()
    }

    private fun cell(s: String) = s.replace("|", "/").replace("\n", " ").trim()
}
