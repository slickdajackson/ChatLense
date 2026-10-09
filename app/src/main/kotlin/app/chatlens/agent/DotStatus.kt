package app.chatlens.agent

/**
 * Zustand des schwebenden Punktes fuer die Startseite: laeuft er, und wenn nicht, warum nicht und was der Nutzer tun kann.
 * Reine Logik, deshalb ohne Android testbar.
 */
enum class DotFix { ENABLE_SWITCH, OPEN_OVERLAY_PERMISSION, OPEN_A11Y }

class DotStatus(val ok: Boolean, val title: String, val lines: List<String>, val fixes: List<DotFix>) {
    companion object {
        fun of(overlayEnabled: Boolean, overlayGranted: Boolean, a11yActive: Boolean): DotStatus {
            val lines = ArrayList<String>()
            val fixes = ArrayList<DotFix>()
            if (!overlayGranted) {
                lines.add("Die Erlaubnis \"Über anderen Apps einblenden\" fehlt. Ohne sie kann der Punkt nicht erscheinen.")
                fixes.add(DotFix.OPEN_OVERLAY_PERMISSION)
            } else if (!overlayEnabled) {
                lines.add("Der Punkt ist ausgeschaltet. Einschalten bringt ihn sofort zurück.")
                fixes.add(DotFix.ENABLE_SWITCH)
            }
            if (!a11yActive) {
                lines.add("Die Bedienungshilfe \"ChatLens Chat-Leser\" ist nicht aktiv. Der Punkt kann dann nichts lesen. Auf HyperOS zuerst \"Eingeschränkte Einstellungen zulassen\".")
                fixes.add(DotFix.OPEN_A11Y)
            }
            val ok = lines.isEmpty()
            return DotStatus(ok, if (ok) "Schwebender Punkt: läuft" else "Schwebender Punkt: läuft nicht vollständig", if (ok) listOf("Punkt über WhatsApp antippen: Analysiere, Schlage vor, Berater, Auto, Selbstanalyse, Einstellungen, Entfernen.") else lines, fixes)
        }
    }
}
