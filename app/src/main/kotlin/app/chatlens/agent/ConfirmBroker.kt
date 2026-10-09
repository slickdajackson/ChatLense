package app.chatlens.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

class ConfirmRequest(val id: Long, val title: String, val body: String)

/**
 * Yes/no questions to the user during a run (for example "Diesen Chat nehmen?").
 * Nothing is ever confirmed silently: with no answer within [timeoutMs], the answer is no. The question is shown as a dialog in the app,
 * as an overlay card (when the permission is granted), and as a notification with yes/no; each of those places calls [answer].
 */
object ConfirmBroker {
    private val _pending = MutableStateFlow<ConfirmRequest?>(null)
    val pending: StateFlow<ConfirmRequest?> = _pending

    @Volatile
    private var waiter: CompletableDeferred<Boolean>? = null
    private var counter = 0L

    suspend fun ask(title: String, body: String, timeoutMs: Long = 120_000): Boolean {
        val d = CompletableDeferred<Boolean>()
        val req: ConfirmRequest
        synchronized(this) {
            waiter = d
            req = ConfirmRequest(++counter, title, body)
            _pending.value = req
        }
        return try {
            withTimeoutOrNull(timeoutMs) { d.await() } ?: false
        } finally {
            synchronized(this) {
                if (_pending.value?.id == req.id) _pending.value = null
                if (waiter === d) waiter = null
            }
        }
    }

    fun answer(id: Long, yes: Boolean) {
        synchronized(this) {
            if (_pending.value?.id != id) return
            waiter?.complete(yes)
            _pending.value = null
        }
    }

    /** For notification buttons that only know the current question. */
    fun answerCurrent(yes: Boolean) {
        val id = _pending.value?.id ?: return
        answer(id, yes)
    }
}
