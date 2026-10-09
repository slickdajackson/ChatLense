package app.chatlens.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

class ConfirmRequest(val id: Long, val title: String, val body: String)

/**
 * Ja/Nein-Rueckfragen an den Nutzer waehrend eines Laufs (z. B. "Diesen Chat nehmen?").
 * Es wird nie stillschweigend bejaht: ohne Antwort in [timeoutMs] gilt Nein. Angezeigt wird die Frage als Dialog in der App,
 * als Overlay-Karte (wenn die Berechtigung da ist) und als Benachrichtigung mit Ja/Nein; jede dieser Stellen ruft [answer].
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

    /** Fuer Benachrichtigungsknoepfe, die nur "die aktuelle Frage" kennen. */
    fun answerCurrent(yes: Boolean) {
        val id = _pending.value?.id ?: return
        answer(id, yes)
    }
}
