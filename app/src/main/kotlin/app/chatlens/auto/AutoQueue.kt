package app.chatlens.auto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

enum class ItemStatus { WARTET, LAEUFT, FERTIG, FEHLER, UEBERSPRUNGEN }

/** Kind of run: SETUP = first-time creation of all profiles (from the chat list), AUTO = a list of names, incremental. */
enum class QueueKind { SETUP, AUTO, SELF }

class QueueItem(
    val title: String,
    var status: ItemStatus = ItemStatus.WARTET,
    var error: String = "",
    var finishedAt: Long = 0L,
    /** Short note on the result (for example the number of messages read), for display only. */
    var info: String = "",
)

/** Queue with state that can be saved and resumed after a cancel. */
class AutoQueue(
    val kind: QueueKind,
    val items: MutableList<QueueItem>,
    val targetPerChat: Int,
    val createdAt: Long,
    val fromList: Boolean,
    /** Free-form focus of the self-analysis (the user's text), otherwise empty. */
    var note: String = "",
) {
    fun nextPending(): QueueItem? = items.firstOrNull { it.status == ItemStatus.WARTET }
    fun count(s: ItemStatus) = items.count { it.status == s }
    val total: Int get() = items.size
    val done: Int get() = items.count { it.status == ItemStatus.FERTIG || it.status == ItemStatus.FEHLER || it.status == ItemStatus.UEBERSPRUNGEN }
    val finished: Boolean get() = items.none { it.status == ItemStatus.WARTET || it.status == ItemStatus.LAEUFT }

    /** Set failed entries back to WARTET (for retrying errors). */
    fun retryFailed(): Int {
        var n = 0
        for (i in items) if (i.status == ItemStatus.FEHLER) { i.status = ItemStatus.WARTET; i.error = ""; n++ }
        return n
    }

    fun toJson(): String = JSONObject().apply {
        put("kind", kind.name)
        put("target", targetPerChat)
        put("created", createdAt)
        put("fromList", fromList)
        put("note", note)
        put(
            "items",
            JSONArray(items.map {
                JSONObject().put("t", it.title).put("s", it.status.name).put("e", it.error).put("f", it.finishedAt).put("i", it.info)
            }),
        )
    }.toString()

    companion object {
        fun fromJson(s: String): AutoQueue {
            val o = JSONObject(s)
            val a = o.getJSONArray("items")
            val items = (0 until a.length()).map {
                val x = a.getJSONObject(it)
                // An entry that was running when cancelled becomes "wartet" again on load
                val st = ItemStatus.valueOf(x.getString("s")).let { st -> if (st == ItemStatus.LAEUFT) ItemStatus.WARTET else st }
                QueueItem(x.getString("t"), st, x.optString("e"), x.optLong("f"), x.optString("i"))
            }.toMutableList()
            return AutoQueue(QueueKind.valueOf(o.getString("kind")), items, o.optInt("target", 100), o.optLong("created"), o.optBoolean("fromList"), o.optString("note"))
        }

        fun of(kind: QueueKind, titles: List<String>, target: Int, now: Long, fromList: Boolean): AutoQueue =
            AutoQueue(kind, titles.map { QueueItem(it) }.toMutableList(), target, now, fromList)
    }
}

/**
 * Kind of a queue-item failure. The pause after several failures in a row applies only to the same kind:
 * "Chat nicht gefunden" (name/search) and "Navigationsfehler" (WhatsApp not in front, system UI) have different causes.
 */
interface KindedFailure {
    val kindName: String
}

/** Failure after which the whole queue cannot usefully continue (for example the accessibility service is off). */
class FatalAutoException(message: String) : Exception(message)

/** A lock for all model calls: at most one call runs at a time. */
object LlmGate {
    val mutex = Mutex()
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
}

/** The queue stopped after several failures in a row (not discarded): the rest stays "wartet", resume is possible. */
class PausedAutoException(message: String) : Exception(message)

object AutoQueueRunner {
    /**
     * Works through the queue one item at a time. A failure on one item is recorded, and the next one continues.
     * On cancel (coroutine cancellation) the running item becomes "wartet" again, the state is saved, and the exception is rethrown:
     * a later start then continues exactly there. [onChange] is called after every state change (display, save).
     */
    suspend fun run(
        queue: AutoQueue,
        now: () -> Long,
        onChange: (AutoQueue) -> Unit,
        maxConsecutiveFailures: Int = 2,
        onFailure: (QueueItem, Int) -> Unit = { _, _ -> },
        worker: suspend (QueueItem) -> String,
    ) {
        var consecutive = 0
        var lastKind: String? = null
        while (true) {
            coroutineContext.ensureActive()
            val item = queue.nextPending() ?: break
            item.status = ItemStatus.LAEUFT
            onChange(queue)
            try {
                val info = worker(item)
                item.status = ItemStatus.FERTIG
                item.info = info
                item.error = ""
                item.finishedAt = now()
                consecutive = 0
                lastKind = null
            } catch (e: CancellationException) {
                item.status = ItemStatus.WARTET
                onChange(queue)
                throw e
            } catch (e: FatalAutoException) {
                item.status = ItemStatus.WARTET
                item.error = e.message.orEmpty()
                onChange(queue)
                throw e
            } catch (e: Exception) {
                item.status = ItemStatus.FEHLER
                item.error = e.message ?: e.javaClass.simpleName
                item.finishedAt = now()
                val kind = (e as? KindedFailure)?.kindName ?: "Fehler"
                // Only failures of the same kind in a row count: a different cause starts the count over
                consecutive = if (kind == lastKind) consecutive + 1 else 1
                lastKind = kind
                onFailure(item, consecutive)
                onChange(queue)
                // After several failures in a row, stop instead of rushing on: fix the cause, then resume
                if (maxConsecutiveFailures > 0 && consecutive >= maxConsecutiveFailures && queue.nextPending() != null) {
                    throw PausedAutoException(
                        "Setup pausiert nach $consecutive Fehlern in Folge mit gleicher Ursache ($kind; zuletzt \"${item.title}\": ${item.error.take(300)}). " +
                            "${queue.count(ItemStatus.WARTET)} Chats warten noch. Ursache beheben (WhatsApp auf der Chatliste, Tab Chats), dann im Tab Start Fortsetzen waehlen.",
                    )
                }
            }
            onChange(queue)
        }
    }
}
