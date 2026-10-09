package app.chatlens.messenger

/**
 * Beschreibung eines Messengers (ab 0.3.0). Die Schnittstelle trennt, was je Messenger anders ist, von der gemeinsamen Mechanik
 * (Tippen, Scrollen, Sicherheit, Gedaechtnis). WhatsApp ist der einzige freigeschaltete Adapter; Signal und Telegram sind
 * Geruest mit Status [AdapterStatus.PREPARED] und lassen sich nicht aktivieren, solange sie nicht am Geraet kalibriert sind.
 * Siehe PLAN.md Abschnitt 26 und 27.
 */
enum class AdapterStatus {
    /** Gebaut, getestet, im Einsatz. */
    ACTIVE,

    /** Profil und Beschreibung liegen vor, aber nicht am Geraet kalibriert. Nicht aktivierbar. */
    PREPARED,
}

/** Wie Chatzeilen und Nachrichten aus dem Barrierefreiheitsbaum gelesen werden. */
enum class ReadStrategy {
    /** ueber Resource-IDs (WhatsApp, Signal). */
    BY_IDS,

    /** ueber zusammengesetzte Beschreibungstexte (Telegram: keine Resource-IDs, lokalisierter Text). */
    BY_DESCRIPTION,
}

enum class VoiceSourceKind {
    /** Ordnerfreigabe (SAF) auf die Sprachnachrichten-Dateien. */
    FOLDER_SAF,

    /** Keine Datei zugaenglich; Sprachnachrichten erscheinen als Platzhalter mit Dauer. */
    NONE,
}

interface MessengerAdapter {
    /** Stabile Kennung, auch Schluessel fuer Einstellungen und Gedaechtnisordner. */
    val id: String
    val displayName: String

    /** Hauptpaket und bekannte Forks. Nur Pakete, die der Nutzer ausdruecklich freigibt, werden gelesen. */
    val packages: List<String>
    val launchPackage: String
    val status: AdapterStatus
    val readStrategy: ReadStrategy

    /** Asset-Datei des Selektorprofils. */
    val profileAsset: String
    val voiceSource: VoiceSourceKind

    /** Darf Text an einen API-Server gehen? Fuer Signal und geheime Chats nein (nur lokale Verarbeitung). */
    val apiModeAllowed: Boolean

    /** Hinweis fuer die Oberflaeche, warum der Adapter nicht verfuegbar ist (leer bei ACTIVE). */
    val unavailableReason: String

    /** Ansichten, die nie gelesen werden duerfen (z. B. Telegram: Geheimer Chat). Beschriftungen zum Erkennen, Kleinschreibung egal. */
    val protectedViewHints: List<String> get() = emptyList()

    /** Ordnername des Gedaechtnisses. WhatsApp behaelt den bisherigen Ordner "memory" (keine Migration noetig). */
    val memoryDirName: String get() = if (id == WhatsAppAdapter.ID) "memory" else "memory-$id"
}

object WhatsAppAdapter : MessengerAdapter {
    const val ID = "whatsapp"
    override val id = ID
    override val displayName = "WhatsApp"
    override val packages = listOf("com.whatsapp")
    override val launchPackage = "com.whatsapp"
    override val status = AdapterStatus.ACTIVE
    override val readStrategy = ReadStrategy.BY_IDS
    override val profileAsset = "profiles/whatsapp.json"
    override val voiceSource = VoiceSourceKind.FOLDER_SAF
    override val apiModeAllowed = true
    override val unavailableReason = ""
}

/**
 * Signal: Resource-IDs stammen aus dem offenen Quelltext (v8.30.1), nicht aus einem Baumexport. Profil `profiles/signal.json` ist
 * eine Vorlage. Sprachnachrichten nur ueber die Oberflaeche. FLAG_SECURE blockiert Screenshots, nicht den Baum (Abschnitt 26.5).
 */
object SignalAdapter : MessengerAdapter {
    override val id = "signal"
    override val displayName = "Signal"
    override val packages = listOf("org.thoughtcrime.securesms")
    override val launchPackage = "org.thoughtcrime.securesms"
    override val status = AdapterStatus.PREPARED
    override val readStrategy = ReadStrategy.BY_IDS
    override val profileAsset = "profiles/signal.json"
    override val voiceSource = VoiceSourceKind.NONE
    override val apiModeAllowed = false
    override val unavailableReason = "In Vorbereitung: Das Profil ist nicht am Gerät kalibriert."
}

/** Telegram: keine Resource-IDs, Text aus Beschreibungen (Abschnitt 26.4). Geheime Chats sind fuer Bedienungshilfen ausgeblendet. */
object TelegramAdapter : MessengerAdapter {
    override val id = "telegram"
    override val displayName = "Telegram"
    override val packages = listOf("org.telegram.messenger", "org.telegram.messenger.web")
    override val launchPackage = "org.telegram.messenger"
    override val status = AdapterStatus.PREPARED
    override val readStrategy = ReadStrategy.BY_DESCRIPTION
    override val profileAsset = "profiles/telegram.json"
    override val voiceSource = VoiceSourceKind.NONE
    override val apiModeAllowed = false
    override val unavailableReason = "In Vorbereitung: Der Text kommt aus Beschreibungen und braucht einen Baumexport vom Gerät."
    override val protectedViewHints = listOf("geheimer chat", "secret chat")
}

/** Verzeichnis der Adapter und Regeln, welche Pakete gelesen werden duerfen. Aenderungen am Bestand gehoeren in einen Test. */
object MessengerRegistry {
    val all: List<MessengerAdapter> = listOf(WhatsAppAdapter, SignalAdapter, TelegramAdapter)

    fun byId(id: String): MessengerAdapter? = all.firstOrNull { it.id == id }

    fun byPackage(pkg: String): MessengerAdapter? = all.firstOrNull { pkg in it.packages }

    /** Kennungen aus der Einstellung; unbekannte und nicht aktivierbare (PREPARED) fallen weg. WhatsApp ist die Rueckfallebene. */
    fun enabledIds(setting: String): Set<String> {
        val ids = setting.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .filter { id -> byId(id)?.status == AdapterStatus.ACTIVE }.toSet()
        return ids.ifEmpty { setOf(WhatsAppAdapter.ID) }
    }

    /** Pakete, die gelesen werden duerfen. */
    fun enabledPackages(setting: String): Set<String> = enabledIds(setting).flatMap { byId(it)?.packages.orEmpty() }.toSet()

    fun isPackageAllowed(pkg: String?, setting: String): Boolean = pkg != null && pkg in enabledPackages(setting)

    /** Neue Einstellung beim Umschalten; PREPARED-Adapter lassen sich nicht einschalten, WhatsApp nie ausschalten (letzter aktiver). */
    fun toggled(setting: String, id: String, on: Boolean): String {
        val a = byId(id) ?: return setting
        val cur = enabledIds(setting).toMutableSet()
        if (on) { if (a.status == AdapterStatus.ACTIVE) cur += id } else if (cur.size > 1) cur -= id
        return cur.sorted().joinToString(",")
    }
}
