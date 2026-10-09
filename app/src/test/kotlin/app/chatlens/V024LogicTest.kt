package app.chatlens

import app.chatlens.auto.AutoQueue
import app.chatlens.auto.AutoQueueRunner
import app.chatlens.auto.ItemStatus
import app.chatlens.auto.PausedAutoException
import app.chatlens.auto.QueueKind
import app.chatlens.core.RunLogMarkdown
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class V024LogicTest {
    private fun q(vararg t: String) = AutoQueue.of(QueueKind.SETUP, t.toList(), 100, 1L, true)

    @Test fun twoFailuresInARowPauseTheQueueAndKeepTheRest() {
        val queue = q("A", "B", "C", "D", "E")
        val seen = ArrayList<String>()
        val failures = ArrayList<Pair<String, Int>>()
        try {
            runBlocking {
                AutoQueueRunner.run(queue, { 5L }, {}, 2, { it, n -> failures.add(it.title to n) }) { item ->
                    seen.add(item.title)
                    if (item.title == "A") "ok" else throw IllegalStateException("Fenster ${item.title} weg")
                }
            }
            fail("Pause erwartet")
        } catch (e: PausedAutoException) {
            assertTrue(e.message!!, e.message!!.contains("2 Fehlern in Folge"))
            assertTrue(e.message!!.contains("2 Chats warten") || e.message!!.contains("Chats warten"))
            assertTrue(e.message!!.contains("Fortsetzen"))
        }
        assertEquals(listOf("A", "B", "C"), seen)
        assertEquals(listOf(ItemStatus.FERTIG, ItemStatus.FEHLER, ItemStatus.FEHLER, ItemStatus.WARTET, ItemStatus.WARTET), queue.items.map { it.status })
        assertEquals("Fenster B weg", queue.items[1].error)
        assertEquals(listOf("B" to 1, "C" to 2), failures)
    }

    @Test fun aSuccessResetsTheFailureCounter() = runBlocking {
        val queue = q("A", "B", "C", "D", "E")
        AutoQueueRunner.run(queue, { 5L }, {}, 2) { item ->
            if (item.title in listOf("A", "C", "E")) throw IllegalStateException("x") else "ok"
        }
        assertTrue(queue.finished)
        assertEquals(3, queue.count(ItemStatus.FEHLER))
    }

    @Test fun noPauseWhenNothingIsLeft() = runBlocking {
        val queue = q("A", "B")
        AutoQueueRunner.run(queue, { 5L }, {}, 2) { throw IllegalStateException("x") }
        assertTrue(queue.finished)
        assertEquals(2, queue.count(ItemStatus.FEHLER))
    }

    @Test fun resumeAfterPauseContinuesWithPendingItems() {
        val queue = q("A", "B", "C")
        try { runBlocking { AutoQueueRunner.run(queue, { 1L }, {}, 2) { throw IllegalStateException("x") } } } catch (_: PausedAutoException) {}
        assertEquals(1, queue.count(ItemStatus.WARTET))
        runBlocking { AutoQueueRunner.run(queue, { 2L }, {}, 2) { "ok" } }
        assertEquals(listOf(ItemStatus.FEHLER, ItemStatus.FEHLER, ItemStatus.FERTIG), queue.items.map { it.status })
    }

    @Test fun markdownHasHeaderSettingsOutcomeAndEscapesFences() {
        val md = RunLogMarkdown.build(
            listOf("Version" to "0.2.4-mvp (10)", "Datum" to "2026-10-03 19:00", "Geraet" to "Xiaomi | 15 Ultra", "Modus" to "Setup: 10 Chats"),
            listOf("Scrollmethode" to "AUTO", "Modell" to "Gemma"),
            "Phase DONE: fertig",
            listOf("2026-10-03 19:00:01 I Start", "2026-10-03 19:00:02 W ``` Zaun"),
        )
        assertTrue(md.startsWith("# ChatLens Lauf-Log"))
        assertTrue(md.contains("| Version | 0.2.4-mvp (10) |"))
        assertTrue(md.contains("| Geraet | Xiaomi / 15 Ultra |"))
        assertTrue(md.contains("## Einstellungen"))
        assertTrue(md.contains("| Scrollmethode | AUTO |"))
        assertTrue(md.contains("## Ergebnis\n\nPhase DONE: fertig"))
        assertTrue(md.contains("(2 Zeilen"))
        assertEquals(2, Regex("```").findAll(md).count())
        assertFalse(md.contains("\u2013") || md.contains("\u2014"))
    }

    @Test fun markdownWithoutOutcomeStatesIt() {
        val md = RunLogMarkdown.build(emptyList(), emptyList(), "", emptyList())
        assertTrue(md.contains("noch nicht beendet"))
    }

    @Test fun settingsRowsNeverContainTheApiKey() {
        val src = File("src/main/kotlin/app/chatlens/service/RunLogStore.kt").readText()
        val rows = src.substringAfter("fun settingsRows").substringBefore("fun markdown")
        assertFalse(rows.contains("key", ignoreCase = true) && rows.contains("s.apiKey", ignoreCase = true))
        assertFalse(rows.contains("apiKey", ignoreCase = true))
    }
}
