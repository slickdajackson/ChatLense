package app.chatlens.messenger

/**
 * Description of a messenger (from 0.3.0). The interface separates what differs per messenger from the shared mechanics
 * (tapping, scrolling, safety, memory). WhatsApp is the only unlocked adapter. Signal and Telegram are
 * scaffolding with status [AdapterStatus.PREPARED] and cannot be activated until they are calibrated on the device.
 * See PLAN.md sections 26 and 27.
 */
enum class AdapterStatus {
    /** Built, tested, in use. */
    ACTIVE,

    /** Profile and description exist, but it is not calibrated on the device. Cannot be activated. */
    PREPARED,
}

/** How chat rows and messages are read from the accessibility tree. */
enum class ReadStrategy {
    /** Via resource IDs (WhatsApp, Signal). */
    BY_IDS,

    /** Via composed description texts (Telegram: no resource IDs, localized text). */
    BY_DESCRIPTION,
}

enum class VoiceSourceKind {
    /** Folder grant (SAF) for the voice message files. */
    FOLDER_SAF,

    /** No file is accessible. Voice messages appear as placeholders with a duration. */
    NONE,
}

interface MessengerAdapter {
    /** Stable id, also the key for settings and the memory folder. */
    val id: String
    val displayName: String

    /** Main package and known forks. Only packages the user explicitly allows are read. */
    val packages: List<String>
    val launchPackage: String
    val status: AdapterStatus
    val readStrategy: ReadStrategy

    /** Asset file of the selector profile. */
    val profileAsset: String
    val voiceSource: VoiceSourceKind

    /** May text go to an API server? For Signal and secret chats, no (local processing only). */
    val apiModeAllowed: Boolean

    /** Hint for the UI explaining why the adapter is unavailable (empty when ACTIVE). */
    val unavailableReason: String

    /** Views that must never be read (for example Telegram: "Geheimer Chat"). Labels used for detection, case does not matter. */
    val protectedViewHints: List<String> get() = emptyList()

    /** Memory folder name. WhatsApp keeps the existing folder "memory" (no migration needed). */
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
 * Signal: resource IDs come from the open source (v8.30.1), not from a tree export. Profile `profiles/signal.json` is
 * a template. Voice messages are available through the UI only. FLAG_SECURE blocks screenshots, not the tree (section 26.5).
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

/** Telegram: no resource IDs, text comes from descriptions (section 26.4). Secret chats are hidden from accessibility services. */
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

/** Registry of adapters and rules for which packages may be read. Changes to the set belong in a test. */
object MessengerRegistry {
    val all: List<MessengerAdapter> = listOf(WhatsAppAdapter, SignalAdapter, TelegramAdapter)

    fun byId(id: String): MessengerAdapter? = all.firstOrNull { it.id == id }

    fun byPackage(pkg: String): MessengerAdapter? = all.firstOrNull { pkg in it.packages }

    /** Ids from the setting. Unknown ones and ones that cannot be activated (PREPARED) are dropped. WhatsApp is the fallback. */
    fun enabledIds(setting: String): Set<String> {
        val ids = setting.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .filter { id -> byId(id)?.status == AdapterStatus.ACTIVE }.toSet()
        return ids.ifEmpty { setOf(WhatsAppAdapter.ID) }
    }

    /** Packages that may be read. */
    fun enabledPackages(setting: String): Set<String> = enabledIds(setting).flatMap { byId(it)?.packages.orEmpty() }.toSet()

    fun isPackageAllowed(pkg: String?, setting: String): Boolean = pkg != null && pkg in enabledPackages(setting)

    /** New setting after a toggle. PREPARED adapters cannot be turned on, and WhatsApp can never be turned off (last active one). */
    fun toggled(setting: String, id: String, on: Boolean): String {
        val a = byId(id) ?: return setting
        val cur = enabledIds(setting).toMutableSet()
        if (on) { if (a.status == AdapterStatus.ACTIVE) cur += id } else if (cur.size > 1) cur -= id
        return cur.sorted().joinToString(",")
    }
}
