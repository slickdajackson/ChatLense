package app.chatlens.assist

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import app.chatlens.data.AppLog
import app.chatlens.profile.SelectorProfile
import app.chatlens.service.ChatAccessibilityService

enum class InsertOutcome { OK, NO_INPUT_FIELD, REFUSED, FAILED }

/**
 * Traegt einen gewaehlten Antwortentwurf in das WhatsApp-Eingabefeld ein (ACTION_SET_TEXT). Es wird NICHT gesendet:
 * diese Klasse kennt keinen Klick und keinen Senden-Knopf. Das Absenden macht der Nutzer selbst in WhatsApp.
 */
class ReplyInserter(private val svc: ChatAccessibilityService, private val profile: SelectorProfile) {

    private fun findInput(): AccessibilityNodeInfo? {
        val root = svc.liveRoot() ?: return null
        if (root.packageName?.toString() != profile.packageName) return null
        val h = svc.boundsOf(root).b.coerceAtLeast(1)
        val byId = svc.findLive(root) { n -> n.isEditable && n.isVisibleToUser && n.viewIdResourceName in profile.messageInputIds }
        if (byId != null) return byId
        return svc.findAllLive(root) { n -> n.isEditable && n.isVisibleToUser && svc.boundsOf(n).centerY > h * profile.messageInputBottomFraction }
            .maxByOrNull { svc.boundsOf(it).centerY }
    }

    fun insert(text: String): InsertOutcome {
        if (text.isBlank()) return InsertOutcome.REFUSED
        val field = findInput() ?: return InsertOutcome.NO_INPUT_FIELD
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        AppLog.i("VORSCHLAG: Text eingetragen (${text.length} Zeichen), Ergebnis=$ok. Es wurde nichts gesendet.")
        return if (ok) InsertOutcome.OK else InsertOutcome.FAILED
    }
}

/**
 * EXPERIMENTELL, standardmaessig deaktiviert. Kann gegen die WhatsApp-Nutzungsbedingungen verstossen.
 * Sendet nur, wenn [SendPolicy] es erlaubt: Schalter in den Einstellungen an UND der Nutzer hat genau diesen Text im
 * Bestaetigungsdialog bestaetigt. Es gibt keinen automatischen Aufruf; nur die Bedienoberflaeche ruft dies nach dem Dialog auf.
 */
class ExperimentalSender(private val svc: ChatAccessibilityService, private val profile: SelectorProfile) {
    fun send(text: String, confirmedText: String?, experimentalEnabled: Boolean): Boolean {
        if (!SendPolicy.maySend(experimentalEnabled, confirmedText, text)) {
            AppLog.w("SENDEN abgelehnt: Schalter aus oder Text nicht bestaetigt.")
            return false
        }
        val root = svc.liveRoot() ?: return false
        if (root.packageName?.toString() != profile.packageName) return false
        val node = svc.findLive(root) { n ->
            n.isVisibleToUser && n.isClickable && (
                n.viewIdResourceName in profile.sendButtonIds ||
                    (n.contentDescription?.toString()?.let { d -> profile.sendButtonDescriptions.any { d.equals(it, ignoreCase = true) } } == true)
                )
        } ?: return false
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        AppLog.w("SENDEN (experimentell, vom Nutzer bestaetigt): Klick=$ok")
        return ok
    }
}
