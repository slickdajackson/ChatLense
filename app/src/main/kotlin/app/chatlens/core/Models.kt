package app.chatlens.core

enum class Direction { IN, OUT, UNKNOWN }

enum class Kind { TEXT, IMAGE, VOICE, DATE, SYSTEM, GAP }

/**
 * Eine extrahierte Chatzeile.
 * [imagePath] und [ocrText] werden erst nach dem Zusammenfuehren der Seiten befuellt.
 */
class ChatMessage(
    var kind: Kind,
    var direction: Direction,
    var sender: String?,
    var text: String,
    var time: String?,
) {
    var imagePath: String? = null
    var ocrText: String? = null
    var imageNote: String? = null

    /** Am oberen oder unteren Listenrand angeschnitten gesehen und noch nicht in vollstaendiger Fassung. */
    var incomplete: Boolean = false

    /** An welchem Rand angeschnitten gesehen (fuer die Vereinigung zweier gegenueberliegender Anschnitte). */
    var clipTop: Boolean = false
    var clipBottom: Boolean = false

    /** Transkript einer Sprachnachricht (Vorbereitung, derzeit nie gefuellt) und Verweis auf die Audioquelle. */
    var transcript: String? = null
    var audioRef: String? = null

    /** In WhatsApp mit "Mehr lesen" gekuerzt (der Text im Baum ist dann evtl. nur der sichtbare Anfang). */
    var truncated: Boolean = false

    /**
     * Uebernimmt Angaben einer anderen Fassung derselben Nachricht. Eine vollstaendige Fassung ersetzt eine angeschnittene.
     * Gibt true zurueck, wenn diese Nachricht dadurch vollstaendig wurde.
     */
    fun absorb(o: ChatMessage): Boolean {
        if (!incomplete) {
            if (o.truncated) truncated = true
            return false
        }
        if (!o.incomplete) {
            kind = o.kind
            direction = o.direction
            sender = o.sender ?: sender
            text = o.text
            time = o.time ?: time
            truncated = o.truncated
            incomplete = false
            clipTop = false
            clipBottom = false
            return true
        }
        // Gegenueberliegende Raender (oben angeschnitten auf der einen, unten auf der anderen Seite) mit gleichem Text: Die beiden Ansichten
        // zeigen zusammen die ganze Nachricht (Seiten sind ausgerichtet, also lueckenlos). Nicht fuer Bilder (Zuschnitt braucht ein Bild am Stueck).
        val opposite = (clipTop && !clipBottom && o.clipBottom && !o.clipTop) || (clipBottom && !clipTop && o.clipTop && !o.clipBottom)
        if (opposite && kind != Kind.IMAGE && normText(text) == normText(o.text)) {
            if (time == null) time = o.time
            if (direction == Direction.UNKNOWN) direction = o.direction
            if (sender == null) sender = o.sender
            truncated = truncated || o.truncated
            incomplete = false
            clipTop = false
            clipBottom = false
            return true
        }
        clipTop = clipTop || o.clipTop
        clipBottom = clipBottom || o.clipBottom
        // beide angeschnitten: laengeren Text und fehlende Angaben behalten
        if (o.text.length > text.length) text = o.text
        if (time == null) time = o.time
        if (direction == Direction.UNKNOWN) direction = o.direction
        if (sender == null) sender = o.sender
        if (o.truncated) truncated = true
        return false
    }

    /** Schluessel fuer strenge Deduplizierung. Bewusst ohne Datum und ohne Bilddaten. */
    val key: String
        get() = "${kind.name}|${direction.name}|${sender.orEmpty()}|$text|${time.orEmpty()}"
}

private fun normText(s: String): String = s.replace(Regex("\\s+"), " ").trim().trimEnd('…', '.', ' ')

/** Ein ausgelesenes Seitenelement inklusive der Bildposition, falls ein Bildknoten gefunden wurde. */
class PageItem(
    val message: ChatMessage,
    val imageBounds: Bounds? = null,
    /** Bildknoten liegt komplett innerhalb der Liste (nur dann wird zugeschnitten). */
    val imageFullyVisible: Boolean = false,
    /** Grenzen der Listenzeile auf dem Bildschirm (zum Messen der Scrolldistanz). */
    val rowBounds: Bounds? = null,
)

class ParsedPage(
    val items: List<PageItem>,
    /** true, wenn eine Nachrichtenliste im Baum gefunden wurde. */
    val listFound: Boolean,
    val listBounds: Bounds?,
    val rowCount: Int,
)

data class ScrollRunConfig(
    val chatTitle: String,
    val chatAlreadyOpen: Boolean,
    val scrollCount: Int,
    val instruction: String,
    /** TARGET: scrollen, bis [targetMessages] erfasst sind oder der Chatanfang erreicht ist. SCROLLS: genau [scrollCount] Schritte. */
    val stopMode: StopMode = StopMode.TARGET,
    val targetMessages: Int = 100,
    /** Chat direkt aus der Chatliste anklicken (Setup), ohne Namenssuche. */
    val fromList: Boolean = false,
    val task: TaskMode = TaskMode.ANALYSE,
    /** Berater: gewuenschtes Ergebnis. Vorschlaege: was die Antwort erreichen soll. */
    val goal: String = "",
    /** Nur Nachrichten seit dem Anker im Gedaechtnis lesen (Auto-Modus). */
    val incremental: Boolean = false,
    /** Gedaechtnis des Chats in den Prompt nehmen (Berater, Vorschlaege). */
    val useMemory: Boolean = true,
    /** Lauf gehoert zu einer Warteschlange (Setup/Auto): Ratenlimit wird dort einmal je Warteschlange geprueft. */
    val inQueue: Boolean = false,
    /** Nach dem Sammeln fragen: "Analysieren wie immer" oder eigener Prompt (nur Aufgabe ANALYSE, Overlay-Knopf). */
    val askPrompt: Boolean = false,
)

/** ANALYSE: Auftrag frei. SUGGEST: 2 bis 3 Antwortentwuerfe. ADVISE: Berater. MEMORY: Gedaechtnis anlegen oder fortschreiben. */
enum class TaskMode { ANALYSE, SUGGEST, ADVISE, MEMORY, SELF }

enum class StopMode { TARGET, SCROLLS }
