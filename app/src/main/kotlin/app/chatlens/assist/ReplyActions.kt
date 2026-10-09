package app.chatlens.assist

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import app.chatlens.data.AppLog
import app.chatlens.profile.SelectorProfile
import app.chatlens.service.ChatAccessibilityService

enum class InsertOutcome { OK, NO_INPUT_FIELD, REFUSED, FAILED }

/**
 * Puts a chosen reply draft into the WhatsApp input field (ACTION_SET_TEXT). It does NOT send:
 * this class knows no click and no send button. The user sends it in WhatsApp.
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
 * EXPERIMENTAL, off by default. Can violate the WhatsApp terms of use.
 * Sends only when [SendPolicy] allows it: the switch in the settings is on AND the user has confirmed exactly this text in the
 * confirmation dialog. There is no automatic call. Only the UI calls this after the dialog.
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
