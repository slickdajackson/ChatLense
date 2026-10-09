package app.chatlens.profile

import org.json.JSONArray
import org.json.JSONObject

/** Austauschbares Selektor-Profil (assets/profiles/whatsapp.json oder Datei im App-Speicher). */
enum class SectionKind { CHATS, DENIED, OTHER }

class SelectorProfile(
    val packageName: String,
    val launchPackage: String,
    val calibrationStatus: String,
    val knownIdsStatus: String,
    val searchButtonIds: List<String>,
    val searchFieldIds: List<String>,
    val chatListRowNameIds: List<String>,
    val contactPickerNameIds: List<String>,
    val messageTextIds: List<String>,
    val messageInputIds: List<String>,
    val sendButtonIds: List<String>,
    val useKnownIds: Boolean,
    val messageListClassHints: List<String>,
    val searchButtonDescriptions: List<String>,
    val searchFieldTopFraction: Double,
    val messageInputBottomFraction: Double,
    val headerTopFraction: Double,
    val timeRegex: Regex,
    val dateLabelRegexes: List<Regex>,
    val statusIconDescriptions: List<String>,
    val voiceClassHints: List<String>,
    val imageClassHints: List<String>,
    val imageMinWidthFractionOfList: Double,
    val imageMinHeightDp: Int,
    val directionToleranceDp: Int,
    val denyClickIdSubstrings: List<String>,
    /** Ueberschriften der Suchtreffer-Abschnitte (Kleinschreibung egal). Nur Abschnitt "chats" darf gewaehlt werden. */
    val searchSectionsChats: List<String> = DEFAULT_CHATS,
    val searchSectionsDenied: List<String> = DEFAULT_DENIED,
    val searchSectionsOther: List<String> = DEFAULT_OTHER,
    /** Wenn im Baum gar keine bekannte Abschnittsueberschrift steht, trotzdem exakten Titeltreffer erlauben. */
    val searchFallbackWithoutHeaders: Boolean = true,
    /** Texte des "Mehr lesen"-Links langer Nachrichten (Kleinschreibung egal, fuehrende Auslassungspunkte egal). */
    val readMoreTexts: List<String> = DEFAULT_READ_MORE,
    /** Regex-Muster fuer den Systemhinweis am Chatanfang (Verschluesselungshinweis). */
    val chatStartPatterns: List<String> = DEFAULT_CHAT_START,
    /** Regex-Muster fuer Ladehinweise ("Aeltere Nachrichten werden geladen" o. ae.). Nur kurze Texte werden geprueft. */
    val loadingHintPatterns: List<String> = DEFAULT_LOADING,
    /** Beschreibungen des Senden-Knopfs (nur fuer den experimentellen, standardmaessig deaktivierten Sender). */
    val sendButtonDescriptions: List<String> = listOf("Senden", "Send"),
    /** IDs des Zeilen-Containers der Chatliste (zusammen mit [chatListRowNameIds] das Erkennungsmerkmal "Das ist die Chatliste"). */
    val chatListRowContainerIds: List<String> = DEFAULT_ROW_CONTAINERS,
    /** Beschriftung des Tabs Chats in der unteren Leiste und der anderen Tabs. Nur Knoten im unteren Bildschirmbereich zaehlen. */
    val tabLabelsChats: List<String> = DEFAULT_TAB_CHATS,
    val tabLabelsOther: List<String> = DEFAULT_TAB_OTHER,
    val tabBarTopFraction: Double = 0.75,
    /** IDs des Badges "ungelesene Nachrichten" in einer Chatzeile (nur fuer den Checkup, Hinweis, nicht belegt). */
    val chatListUnreadIds: List<String> = DEFAULT_UNREAD_IDS,
) {
    /** Alle IDs, an denen man die Chatliste erkennt. */
    val chatListMarkerIds: Set<String> = (chatListRowNameIds + chatListRowContainerIds).toSet()

    private val loadingRegexes = loadingHintPatterns.map { Regex(it) }

    /** true, wenn der Text wie ein Ladehinweis aussieht (kurzer Text, damit lange Chattexte nicht zaehlen). */
    fun isLoadingHint(text: String): Boolean = text.length <= 80 && loadingRegexes.any { it.containsMatchIn(text) }

    private val readMoreSet = readMoreTexts.map { it.trim().lowercase() }.toSet()
    private val chatStartRegexes = chatStartPatterns.map { Regex(it) }

    /** true, wenn der Text nur aus dem "Mehr lesen"-Link besteht. */
    fun isReadMore(text: String): Boolean = stripEllipsis(text.trim().lowercase()) in readMoreSet

    /** Liefert den Text ohne angehaengten "Mehr lesen"-Link, oder null, wenn keiner am Ende steht. */
    fun stripReadMoreSuffix(text: String): String? {
        val t = text.trimEnd()
        val low = t.lowercase()
        for (tok in readMoreSet) {
            if (low.endsWith(tok) && t.length > tok.length + 3) {
                return t.substring(0, t.length - tok.length).trimEnd().trimEnd('…', '.', ' ')
            }
        }
        return null
    }

    fun isChatStartNotice(text: String): Boolean = chatStartRegexes.any { it.containsMatchIn(text) }

    private fun stripEllipsis(s: String) = s.trimStart('…', '.', ' ')

    private fun norm(s: String) = s.trim().lowercase()
    private val chatsSet = searchSectionsChats.map(::norm).toSet()
    private val deniedSet = searchSectionsDenied.map(::norm).toSet()
    private val otherSet = searchSectionsOther.map(::norm).toSet()

    /** Art der Abschnittsueberschrift oder null, wenn der Text keine bekannte Ueberschrift ist. */
    fun sectionKindOf(text: String?): SectionKind? {
        if (text == null) return null
        val t = norm(text)
        return when {
            t in chatsSet -> SectionKind.CHATS
            t in deniedSet -> SectionKind.DENIED
            t in otherSet -> SectionKind.OTHER
            else -> null
        }
    }

    fun isTimeText(s: String): Boolean = timeRegex.matches(s.trim())
    fun isDateLabel(s: String): Boolean = dateLabelRegexes.any { it.matches(s.trim()) }

    companion object {
        val DEFAULT_TAB_CHATS = listOf("Chats")
        val DEFAULT_TAB_OTHER = listOf("Aktuelles", "Updates", "Status", "Communities", "Community", "Anrufe", "Calls")
        val DEFAULT_UNREAD_IDS = listOf("com.whatsapp:id/conversations_row_message_count")
        val DEFAULT_ROW_CONTAINERS = listOf("com.whatsapp:id/contact_row_container")
        val DEFAULT_READ_MORE = listOf("Mehr lesen", "Read more")
        val DEFAULT_CHAT_START = listOf("(?i)ende-zu-ende", "(?i)end-to-end", "(?i)verschl[uü]sselt", "(?i)encrypted")
        val DEFAULT_CHATS = listOf("Chats")
        val DEFAULT_LOADING = listOf(
            "(?i)(ä|ae)ltere nachrichten", "(?i)nachrichten werden geladen", "(?i)wird geladen", "(?i)werden geladen", "(?i)\\blade[nt]?\\b",
            "(?i)older messages", "(?i)loading", "(?i)\\bsyncing\\b", "(?i)synchronis",
        )
        val DEFAULT_DENIED = listOf("Gemeinsame Gruppen", "Gruppen", "Groups in common", "Common groups", "Groups")
        val DEFAULT_OTHER = listOf("Kontakte", "Contacts", "Nachrichten", "Messages", "Weitere Kontakte", "Kanäle", "Channels", "Communities")

        fun parse(json: String): SelectorProfile {
            val o = JSONObject(json)
            val ids = o.optJSONObject("knownIds") ?: JSONObject()
            val h = o.getJSONObject("heuristics")
            return SelectorProfile(
                packageName = o.getString("packageName"),
                launchPackage = o.optString("launchPackage", o.getString("packageName")),
                calibrationStatus = o.optJSONObject("calibration")?.optString("status") ?: "UNKNOWN",
                knownIdsStatus = ids.optString("status", "NONE"),
                searchButtonIds = ids.strList("searchButton"),
                searchFieldIds = ids.strList("searchField"),
                chatListRowNameIds = ids.strList("chatListRowName"),
                contactPickerNameIds = ids.strList("contactPickerName"),
                messageTextIds = ids.strList("messageText"),
                messageInputIds = ids.strList("messageInput"),
                sendButtonIds = ids.strList("sendButton"),
                useKnownIds = h.optBoolean("useKnownIds", true),
                messageListClassHints = h.strList("messageListClassHints"),
                searchButtonDescriptions = h.strList("searchButtonDescriptions"),
                searchFieldTopFraction = h.optDouble("searchFieldTopFraction", 0.25),
                messageInputBottomFraction = h.optDouble("messageInputBottomFraction", 0.65),
                headerTopFraction = h.optDouble("headerTopFraction", 0.2),
                timeRegex = Regex(h.getString("timeRegex")),
                dateLabelRegexes = h.strList("dateLabelRegexes").map { Regex(it) },
                statusIconDescriptions = h.strList("statusIconDescriptions"),
                voiceClassHints = h.strList("voiceClassHints"),
                imageClassHints = h.strList("imageClassHints"),
                imageMinWidthFractionOfList = h.optDouble("imageMinWidthFractionOfList", 0.25),
                imageMinHeightDp = h.optInt("imageMinHeightDp", 90),
                directionToleranceDp = h.optInt("directionToleranceDp", 8),
                denyClickIdSubstrings = h.strList("denyClickIdSubstrings"),
                searchSectionsChats = h.strListOr("searchSectionsChats", DEFAULT_CHATS),
                searchSectionsDenied = h.strListOr("searchSectionsDenied", DEFAULT_DENIED),
                searchSectionsOther = h.strListOr("searchSectionsOther", DEFAULT_OTHER),
                searchFallbackWithoutHeaders = h.optBoolean("searchFallbackWithoutHeaders", true),
                readMoreTexts = h.strListOr("readMoreTexts", DEFAULT_READ_MORE),
                chatStartPatterns = h.strListOr("chatStartPatterns", DEFAULT_CHAT_START),
                loadingHintPatterns = h.strListOr("loadingHintPatterns", DEFAULT_LOADING),
                sendButtonDescriptions = h.strListOr("sendButtonDescriptions", listOf("Senden", "Send")),
                tabLabelsChats = h.strListOr("tabLabelsChats", DEFAULT_TAB_CHATS),
                tabLabelsOther = h.strListOr("tabLabelsOther", DEFAULT_TAB_OTHER),
                tabBarTopFraction = h.optDouble("tabBarTopFraction", 0.75),
                chatListUnreadIds = if (ids.has("chatListUnread")) ids.strList("chatListUnread") else DEFAULT_UNREAD_IDS,
                chatListRowContainerIds = if (ids.has("chatListRowContainer")) ids.strList("chatListRowContainer") else DEFAULT_ROW_CONTAINERS,
            )
        }

        private fun JSONObject.strListOr(name: String, d: List<String>): List<String> =
            if (has(name)) strList(name) else d

        private fun JSONObject.strList(name: String): List<String> {
            val a: JSONArray = optJSONArray(name) ?: return emptyList()
            return List(a.length()) { a.getString(it) }
        }
    }
}
