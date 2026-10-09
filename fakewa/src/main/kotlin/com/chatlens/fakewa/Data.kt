package com.chatlens.fakewa

import java.util.Random

/** Test data for the stand-in: deterministic (fixed seed), with umlauts, groups, duplicates, and pinned chats. */
class FakeChat(val title: String, val preview: String, val time: String, val group: Boolean, val pinned: Boolean, val unread: Int, val archived: Boolean)

object Data {
    private val first = listOf("Anna", "Bärbel", "Jürgen", "Özlem", "Åsa", "Zoë", "Müller", "Max", "Lena", "Jonas", "Sören", "Hélène", "Tim", "Clara", "Dennis", "Eva", "Fritz", "Gül", "Hannah", "Ingo")
    private val last = listOf("Schmidt", "Müller", "Çelik", "Größer", "Weiß", "Meier", "Kožušník", "Öztürk", "Schäfer", "Böhm", "Fischer", "Wagner")
    private val groups = listOf("Familie", "Arbeit Team", "Fußball Freunde", "Nachbarn Hausflur", "Urlaub Südtirol", "Eltern Kita", "Lauftreff Süd", "Bücherclub")
    private val lines = listOf("Bis gleich", "Ja klar, machen wir", "Hast du das gesehen?", "Danke dir", "Ich rufe später an", "Passt für mich", "Lass uns morgen sprechen", "Super Idee")

    fun chats(n: Int = 200): List<FakeChat> {
        val r = Random(42)
        val out = ArrayList<FakeChat>()
        for (i in 0 until n) {
            val g = i % 9 == 4
            val title = when {
                i == 0 -> "Anna Schmidt"
                i == 7 -> "Anna Schmidt" // duplicate
                i == 3 -> "Jürgen Müller"
                i == 5 -> "Özlem Çelik"
                i == 11 -> "Bärbel Größer"
                g -> groups[(i / 9) % groups.size] + if (i >= 80) " ${i / 9}" else ""
                else -> first[r.nextInt(first.size)] + " " + last[r.nextInt(last.size)] + if (i > 60) " $i" else ""
            }
            val prev = if (g) first[r.nextInt(first.size)] + ": " + lines[r.nextInt(lines.size)] else lines[r.nextInt(lines.size)]
            val unread = if (i % 6 == 2) 1 + r.nextInt(5) else 0
            out.add(FakeChat(title, prev, "%02d:%02d".format(8 + (i / 30) % 12, (i * 7) % 60), g, i in 1..2, unread, false))
        }
        return out
    }

    fun archived() = (1..8).map { FakeChat("Archiv Kontakt $it", "alt", "gestern", false, false, 0, true) }

    class Msg(val kind: Int, val text: String, val time: String, val out: Boolean) // 0 text, 1 date, 2 voice, 3 notice

    /** History of a chat: encryption notice, date separators, text and voice messages, about [n] rows. */
    fun messages(title: String, n: Int = 420): List<Msg> {
        val r = Random(title.hashCode().toLong())
        val out = ArrayList<Msg>()
        out.add(Msg(3, "Nachrichten und Anrufe sind Ende-zu-Ende verschlüsselt. Niemand außerhalb dieses Chats kann sie lesen.", "", false))
        var day = 1
        var i = 0
        while (out.size < n) {
            if (i % 12 == 0) { out.add(Msg(1, "%02d.%02d.2026".format(day % 28 + 1, 3 + day / 28), "", false)); day++ }
            val mine = r.nextBoolean()
            val t = "%02d:%02d".format(8 + (i / 3) % 14, (i * 11) % 60)
            if (i % 15 == 7) out.add(Msg(2, "0:%02d".format(5 + r.nextInt(50)), t, mine))
            else out.add(Msg(0, "Nachricht $i von $title: " + lines[r.nextInt(lines.size)] + " " + "Grüße aus München.".repeat(r.nextInt(3)), t, mine))
            i++
        }
        return out
    }
}
