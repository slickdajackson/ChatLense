package app.chatlens.agent

import android.content.Context
import app.chatlens.data.MemoryRepo
import app.chatlens.prompts.PromptBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

sealed class PromptChoice {
    /** "Analysieren wie immer": the standard prompt (the task from settings). */
    object Standard : PromptChoice()
    class Custom(val text: String) : PromptChoice()
    object Cancel : PromptChoice()
}

class PromptChoiceRequest(val id: Long, val chatTitle: String, val messageCount: Int)

/**
 * Choice after collection: "Analysieren wie immer" or "Eigener Prompt zur Laufzeit". Like [ConfirmBroker], nothing is ever chosen
 * silently: with no answer within [timeoutMs], cancel applies (no model call). The card appears in the overlay panel and in the app.
 */
object PromptChoiceBroker {
    private val _pending = MutableStateFlow<PromptChoiceRequest?>(null)
    val pending: StateFlow<PromptChoiceRequest?> = _pending

    @Volatile private var waiter: CompletableDeferred<PromptChoice>? = null
    private var counter = 0L

    suspend fun ask(chatTitle: String, messageCount: Int, timeoutMs: Long = 600_000): PromptChoice {
        val d = CompletableDeferred<PromptChoice>()
        val req: PromptChoiceRequest
        synchronized(this) {
            waiter = d
            req = PromptChoiceRequest(++counter, chatTitle, messageCount)
            _pending.value = req
        }
        return try {
            withTimeoutOrNull(timeoutMs) { d.await() } ?: PromptChoice.Cancel
        } finally {
            synchronized(this) {
                if (_pending.value?.id == req.id) _pending.value = null
                if (waiter === d) waiter = null
            }
        }
    }

    fun answer(id: Long, choice: PromptChoice) {
        synchronized(this) {
            if (_pending.value?.id != id) return
            waiter?.complete(choice)
            _pending.value = null
        }
    }
}

/** The prompt book in memory (for the UI) and encrypted on the device. */
object PromptBookState {
    private val _book = MutableStateFlow(PromptBook())
    val book: StateFlow<PromptBook> = _book

    @Volatile private var loaded = false

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        _book.value = runCatching { MemoryRepo.get(ctx).loadPromptBook() }.getOrNull() ?: PromptBook()
        loaded = true
    }

    /** Updates the book and saves it encrypted. A save error loses only the change on disk, not the one in memory. */
    @Synchronized
    fun update(ctx: Context, f: (PromptBook) -> PromptBook) {
        ensureLoaded(ctx)
        val nb = f(_book.value)
        _book.value = nb
        runCatching { MemoryRepo.get(ctx).savePromptBook(nb) }
    }

    fun reset() { _book.value = PromptBook(); loaded = true }
}
