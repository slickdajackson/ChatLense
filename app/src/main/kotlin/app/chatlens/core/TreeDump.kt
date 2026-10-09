package app.chatlens.core

import app.chatlens.profile.SelectorProfile

/** Text-Export des Accessibility-Baums zum Kalibrieren der Selektoren. */
object TreeDump {

    /**
     * Maskiert Text: Buchstaben werden zu x/X, Ziffern zu 9, Satzzeichen bleiben.
     * Uhrzeiten, Datumsangaben und Abschnittsueberschriften der Suche (laut Profil) bleiben im Klartext, weil sie fuer die Kalibrierung noetig sind.
     */
    fun mask(s: String?, profile: SelectorProfile?): String? {
        if (s == null) return null
        val t = s.trim()
        if (profile != null && (profile.isTimeText(t) || profile.isDateLabel(t) || profile.sectionKindOf(t) != null)) return s
        return buildString(s.length) {
            for (c in s) append(
                when {
                    c.isUpperCase() -> 'X'
                    c.isLetter() -> 'x'
                    c.isDigit() -> '9'
                    else -> c
                },
            )
        }
    }

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    fun dump(
        root: UiNode,
        header: List<String>,
        maskText: Boolean,
        profile: SelectorProfile?,
        maxNodes: Int = 5000,
    ): String {
        val sb = StringBuilder()
        header.forEach { sb.append("# ").append(it).append('\n') }
        sb.append("# Text maskiert: ").append(if (maskText) "ja (Buchstaben x/X, Ziffern 9; Uhrzeit, Datum und Suchabschnitts-Ueberschriften im Klartext)" else "nein").append('\n')
        sb.append("# Format: Einrueckung = Tiefe. Flags: c=clickable s=scrollable e=editable v=sichtbar\n\n")

        var count = 0
        fun rec(n: UiNode, depth: Int) {
            if (count++ >= maxNodes) return
            val flags = buildString {
                if (n.clickable) append('c')
                if (n.scrollable) append('s')
                if (n.editable) append('e')
                if (n.visible) append('v')
            }
            sb.append("  ".repeat(depth)).append(n.className)
            if (n.viewId != null) sb.append(" id=").append(n.viewId)
            sb.append(" b=").append(n.bounds)
            if (flags.isNotEmpty()) sb.append(" f=").append(flags)
            val t = if (maskText) mask(n.text, profile) else n.text
            val d = if (maskText) mask(n.desc, profile) else n.desc
            if (!t.isNullOrEmpty()) sb.append(" text=\"").append(esc(t)).append('"')
            if (!d.isNullOrEmpty()) sb.append(" desc=\"").append(esc(d)).append('"')
            sb.append('\n')
            n.children.forEach { rec(it, depth + 1) }
        }
        rec(root, 0)
        if (count >= maxNodes) sb.append("# ... abgeschnitten bei $maxNodes Knoten\n")

        sb.append("\n# Zusammenfassung\n")
        val all = root.walk().toList()
        sb.append("# Knoten gesamt: ").append(all.size).append('\n')
        all.filter { it.scrollable }.forEach {
            sb.append("# scrollbar: ").append(it.className).append(" id=").append(it.viewId).append(" b=").append(it.bounds)
                .append(" kinder=").append(it.children.size).append('\n')
        }
        all.filter { it.editable }.forEach {
            sb.append("# editierbar: ").append(it.className).append(" id=").append(it.viewId).append(" b=").append(it.bounds).append('\n')
        }
        if (profile != null) {
            val ids = all.mapNotNull { it.viewId }.filter { it.startsWith(profile.packageName + ":") }.distinct().sorted()
            sb.append("# Eindeutige View-IDs der App (").append(ids.size).append("):\n")
            ids.forEach { sb.append("#   ").append(it).append('\n') }
            val known = (profile.searchButtonIds + profile.searchFieldIds + profile.chatListRowNameIds + profile.chatListRowContainerIds +
                profile.contactPickerNameIds + profile.messageTextIds + profile.messageInputIds + profile.sendButtonIds).distinct()
            sb.append("# Profil-IDs in diesem Baum vorhanden: ")
                .append(known.filter { k -> ids.contains(k) }.ifEmpty { listOf("keine") }.joinToString(", ")).append('\n')
        }
        return sb.toString()
    }
}
