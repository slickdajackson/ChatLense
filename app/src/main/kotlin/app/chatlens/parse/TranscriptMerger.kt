package app.chatlens.parse

import app.chatlens.core.ChatMessage
import app.chatlens.core.Direction
import app.chatlens.core.Kind
import app.chatlens.core.PageItem

/**
 * Fuehrt Seiten (jeweils oben nach unten = alt nach neu) beim Rueckwaertsscrollen zusammen.
 * Die erste Seite ist die neueste. Spaetere Seiten ueberlappen mit dem Kopf der gesammelten Liste.
 *
 * Randnachrichten: Eine am Listenrand angeschnittene Nachricht ist als [ChatMessage.incomplete] markiert.
 * Beim Abgleich zweier Seiten gilt eine angeschnittene Fassung als gleich wie die vollstaendige, wenn Art, Richtung,
 * Absender und Uhrzeit nicht widersprechen und der Text gleich ist oder der eine Text im anderen enthalten ist
 * (Praefix, Suffix oder Teiltext). Taucht spaeter die vollstaendige Fassung auf, ersetzt sie die angeschnittene.
 */
class TranscriptMerger {
    private val collected = ArrayList<ChatMessage>()

    val messages: List<ChatMessage> get() = collected

    class Result(
        val added: Int,
        /** Zu jedem PageItem das kanonische Objekt in der Gesamtliste. */
        val canonical: List<ChatMessage>,
        val gapInserted: Boolean,
        /** true, wenn keine belastbare Ausrichtung gefunden wurde und (bei allowGap=false) nichts uebernommen wurde. */
        val noOverlap: Boolean = false,
        /** Anzahl der Seitenzeilen, die einer Zeile im bisherigen Bestand zugeordnet wurden. */
        val overlapRows: Int = 0,
        /** Anzahl angeschnittener Nachrichten, die durch eine vollstaendige Fassung ersetzt wurden. */
        val completed: Int = 0,
        /** Davon in den Bestand eingefuegt, obwohl sie mitten in der Ueberlappung lagen (zuvor nicht erkannt). */
        val inserted: Int = 0,
        /** Seitenzeilen in der Ueberlappung ohne Gegenstueck (Parser-Rauschen). */
        val unmatched: Int = 0,
    )

    /**
     * Fuegt eine Seite ein. Die Seite wird per laengster gemeinsamer Teilfolge am Kopf des Bestands ausgerichtet:
     * einzelne Zeilen ohne Gegenstueck (Rauschen, anders erkannte Randzeilen) verhindern die Ausrichtung nicht mehr.
     * Belastbar ist sie, wenn mindestens eine Nicht-Datums-Zeile passt, aeltere Seitenzeilen nur am Kopf des Bestands
     * (Index 0 oder 1) angefuegt werden muessen und hoechstens so viele Zeilen ungepaart sind wie Anker da sind
     * (bei einem einzigen Anker keine).
     */
    fun add(page: List<PageItem>, allowGap: Boolean = true): Result {
        val msgs = page.map { it.message }
        if (msgs.isEmpty()) return Result(0, emptyList(), false)
        if (collected.isEmpty()) {
            collected.addAll(msgs)
            return Result(msgs.size, msgs, false)
        }

        val window = minOf(collected.size, msgs.size * 3 + 10)
        val matches = FrameAligner.lcs(msgs, collected.subList(0, window))
        val anchors = matches.count { msgs[it.a].kind != Kind.DATE }
        if (anchors > 0) {
            val first = matches.first()
            val matchedA = matches.map { it.a }.toSet()
            val older = first.a
            val unmatchedAfter = (first.a + 1 until msgs.size).count { it !in matchedA && isCountable(msgs[it]) }
            val allowed = if (anchors == 1) 0 else anchors
            val headOk = older == 0 || first.b <= 1
            if (headOk && unmatchedAfter <= allowed) {
                return merge(msgs, matches, unmatchedAfter)
            }
        }

        if (!allowGap) return Result(0, msgs, gapInserted = false, noOverlap = true)

        // Keine Ueberlappung gefunden: komplett vorne anfuegen, Luecke markieren
        val gap = ChatMessage(Kind.GAP, Direction.UNKNOWN, null, "mögliche Lücke: Seiten überlappen nicht", null)
        collected.addAll(0, msgs + gap)
        return Result(msgs.size, msgs, true, true, 0, 0)
    }

    private fun merge(msgs: List<ChatMessage>, matches: List<FrameAligner.Match>, unmatched: Int): Result {
        val byA = matches.associateBy { it.a }
        val first = matches.first()
        var completed = 0
        val canon = ArrayList<ChatMessage>(msgs.size)
        val insertAfter = HashMap<Int, MutableList<ChatMessage>>()
        var lastB = -1
        var inserted = 0
        for (i in msgs.indices) {
            val mt = byA[i]
            if (mt != null) {
                val target = collected[mt.b]
                if (target.absorb(msgs[i])) completed++
                canon.add(target)
                lastB = mt.b
            } else if (i > first.a && lastB >= 0 && (isCountable(msgs[i]) || msgs[i].kind == Kind.DATE)) {
                // mitten in der Ueberlappung ohne Gegenstueck: nach der zuletzt zugeordneten Zeile einfuegen
                insertAfter.getOrPut(lastB) { ArrayList() }.add(msgs[i])
                canon.add(msgs[i])
                inserted++
            } else {
                canon.add(msgs[i])
            }
        }
        val older = msgs.subList(0, first.a).toList()
        val rebuilt = ArrayList<ChatMessage>(collected.size + older.size + inserted)
        rebuilt.addAll(older)
        for (k in collected.indices) {
            rebuilt.add(collected[k])
            insertAfter[k]?.let { rebuilt.addAll(it) }
        }
        collected.clear()
        collected.addAll(rebuilt)
        return Result(older.size + inserted, canon, false, false, matches.size, completed, inserted, unmatched)
    }

    /** Anzahl noch angeschnittener Nachrichten (nie in vollstaendiger Fassung gesehen). */
    fun incompleteCount(): Int = collected.count { it.incomplete }

    /**
     * Entfernt angeschnittene Nachrichten am aeltesten Ende, solange danach noch mindestens [minKeep]
     * Nachrichten (Text, Bild, Sprache) uebrig sind. Gibt die Anzahl entfernter Eintraege zurueck.
     */
    fun trimIncompleteHead(minKeep: Int): Int {
        var removed = 0
        while (true) {
            val idx = collected.indexOfFirst { it.kind != Kind.DATE }
            if (idx < 0) break
            val m = collected[idx]
            if (!m.incomplete) break
            if (isCountable(m) && collected.count { isCountable(it) } - 1 < minKeep) break
            collected.subList(0, idx + 1).clear()
            removed += idx + 1
        }
        return removed
    }

    companion object {
        fun isCountable(m: ChatMessage): Boolean = m.kind == Kind.TEXT || m.kind == Kind.IMAGE || m.kind == Kind.VOICE

        private fun norm(s: String): String = s.replace(Regex("\\s+"), " ").trim().trimEnd('…', '.', ' ')

        /** true, wenn beide Zeilen dieselbe Nachricht sein koennen. Streng, solange keine Seite angeschnitten oder gekuerzt ist. */
        fun compatible(a: ChatMessage, b: ChatMessage): Boolean {
            if (a.kind == Kind.GAP || b.kind == Kind.GAP) return false
            val loose = a.incomplete || b.incomplete || a.truncated || b.truncated
            val kindOk = a.kind == b.kind ||
                (loose && a.kind in TEXT_LIKE && b.kind in TEXT_LIKE)
            if (!kindOk) return false
            if (a.direction != b.direction && !(loose && (a.direction == Direction.UNKNOWN || b.direction == Direction.UNKNOWN))) return false
            if (a.sender.orEmpty() != b.sender.orEmpty() && !(loose && (a.sender == null || b.sender == null))) return false
            if (a.time.orEmpty() != b.time.orEmpty() && !(loose && (a.time == null || b.time == null))) return false
            val ta = norm(a.text)
            val tb = norm(b.text)
            if (ta == tb) return true
            if (!loose) return false
            val (short, long) = if (ta.length <= tb.length) ta to tb else tb to ta
            return short.isNotEmpty() && long.contains(short)
        }

        private val TEXT_LIKE = setOf(Kind.TEXT, Kind.SYSTEM)
    }
}
