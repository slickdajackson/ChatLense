package app.chatlens.core

enum class Direction { IN, OUT, UNKNOWN }

enum class Kind { TEXT, IMAGE, VOICE, DATE, SYSTEM, GAP }

/**
 * One extracted chat row.
 * [imagePath] and [ocrText] are filled only after the pages are merged.
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

    /** Seen cut off at the top or bottom edge of the list, and not yet in a complete version. */
    var incomplete: Boolean = false

    /** Which edge it was seen cut off at (for merging two opposite cutoffs). */
    var clipTop: Boolean = false
    var clipBottom: Boolean = false

    /** Transcript of a voice message (prepared, currently never filled) and a reference to the audio source. */
    var transcript: String? = null
    var audioRef: String? = null

    /** Truncated in WhatsApp with "Mehr lesen" (the text in the tree may then be only the visible start). */
    var truncated: Boolean = false

    /**
     * Takes over fields from another version of the same message. A complete version replaces a cut-off one.
     * Returns true when this message became complete because of that.
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
        // Opposite edges (cut off at the top on one side, at the bottom on the other) with the same text: the two views
        // together show the whole message (pages are aligned, so there is no gap). Not for images (cropping needs the image in one piece).
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
        // both cut off: keep the longer text and any missing fields
        if (o.text.length > text.length) text = o.text
        if (time == null) time = o.time
        if (direction == Direction.UNKNOWN) direction = o.direction
        if (sender == null) sender = o.sender
        if (o.truncated) truncated = true
        return false
    }

    /** Key for strict deduplication. Deliberately without the date and without image data. */
    val key: String
        get() = "${kind.name}|${direction.name}|${sender.orEmpty()}|$text|${time.orEmpty()}"
}

private fun normText(s: String): String = s.replace(Regex("\\s+"), " ").trim().trimEnd('…', '.', ' ')

/** One read page element, including the image position if an image node was found. */
class PageItem(
    val message: ChatMessage,
    val imageBounds: Bounds? = null,
    /** The image node lies fully inside the list (only then is it cropped). */
    val imageFullyVisible: Boolean = false,
    /** Bounds of the list row on screen (for measuring the scroll distance). */
    val rowBounds: Bounds? = null,
)

class ParsedPage(
    val items: List<PageItem>,
    /** True when a message list was found in the tree. */
    val listFound: Boolean,
    val listBounds: Bounds?,
    val rowCount: Int,
)

data class ScrollRunConfig(
    val chatTitle: String,
    val chatAlreadyOpen: Boolean,
    val scrollCount: Int,
    val instruction: String,
    /** TARGET: scroll until [targetMessages] are captured or the start of the chat is reached. SCROLLS: exactly [scrollCount] steps. */
    val stopMode: StopMode = StopMode.TARGET,
    val targetMessages: Int = 100,
    /** Tap the chat directly from the chat list (setup), without a name search. */
    val fromList: Boolean = false,
    val task: TaskMode = TaskMode.ANALYSE,
    /** Advisor: the desired outcome. Suggestions: what the reply should achieve. */
    val goal: String = "",
    /** Read only messages since the anchor in memory (auto mode). */
    val incremental: Boolean = false,
    /** Include the chat's memory in the prompt (advisor, suggestions). */
    val useMemory: Boolean = true,
    /** The run belongs to a queue (setup/auto): the rate limit is checked there once per queue. */
    val inQueue: Boolean = false,
    /** Ask after collection: "Analysieren wie immer" or a custom prompt (task ANALYSE only, overlay button). */
    val askPrompt: Boolean = false,
)

/** ANALYSE: free-form task. SUGGEST: 2 to 3 reply drafts. ADVISE: advisor. MEMORY: create or update memory. */
enum class TaskMode { ANALYSE, SUGGEST, ADVISE, MEMORY, SELF }

enum class StopMode { TARGET, SCROLLS }
