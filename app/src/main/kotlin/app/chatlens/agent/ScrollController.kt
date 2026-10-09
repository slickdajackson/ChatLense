package app.chatlens.agent

import app.chatlens.core.Bounds
import app.chatlens.core.Kind
import app.chatlens.core.ParsedPage
import app.chatlens.core.UiNode
import app.chatlens.data.ScrollMethod
import app.chatlens.parse.ChatParser
import app.chatlens.parse.FrameAligner
import app.chatlens.parse.TranscriptMerger
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.random.Random

/** One screen that was read and, if aligned, inserted. */
class PageRead(val res: TranscriptMerger.Result, val page: ParsedPage, val snap: UiNode)

/** Settings for scroll control. */
data class ScrollConfig(
    val method: ScrollMethod = ScrollMethod.AUTO,
    /** true: the swipe distance is adjusted from the measured scroll distance. false: fixed distance [fixedStepFraction]. */
    val selfCalibrate: Boolean = true,
    val fixedStepFraction: Double = 0.30,
    /** Desired overlap of two pages as a fraction of the list height (0.30 to 0.50). */
    val targetOverlap: Double = 0.35,
    /** Duration of the swipe motion. Long and steady, so no fling builds up. */
    val swipeMs: Long = 450,
    /** How long the finger is held at the end of the motion. */
    val holdMs: Long = 80,
    val settleMaxMs: Long = 2500,
    val pollMs: Long = 40,
    val longWaitMs: Long = 3000,
    val pauseMinMs: Long = 0,
    val pauseMaxMs: Long = 0,
    /** After this many lost overlaps, AUTO switches to Genau mode. */
    val lossesForGenau: Int = 2,
    /** Fraction of the list height per micro-swipe in Genau mode. */
    val microFraction: Double = 0.15,
    val maxRecoverySteps: Int = 6,
    /** On unchanged content (end of the loaded history), finish cleanly after waiting and retrying. */
    val endOnStatic: Boolean = true,
    /** Wait time when a loading hint is visible. */
    val endWaitMs: Long = 6000,
    /** Wait time when there is no loading hint. */
    val endNoHintWaitMs: Long = 2000,
    val endChunkMs: Long = 400,
    /** This many swipe attempts (each with a wait) on unchanged content, then stop. */
    val staticAttempts: Int = 3,
    /** Hard limit: this many steps in a row with no new rows, then abort. */
    val maxNoNewSteps: Int = 4,
    /** Hard limit: recoveries by counter-step with no net progress. */
    val maxRecoveriesNoProgress: Int = 6,
    /** A shift up to this fraction of the list height counts as "no movement". */
    val staticShiftFraction: Double = 0.03,
)

/** Callbacks to the caller (log, images, foreground check). */
interface ScrollHooks {
    fun log(msg: String)
    fun warn(msg: String)
    suspend fun ensureForeground()

    /** After each page is inserted (capture images, publish counters). */
    suspend fun afterMerge(read: PageRead)
}

/**
 * Controls scrolling backward. Core idea: the alignment of two screens is never guessed, but measured from shared rows
 * (screen shift in pixels). That yields the actual scroll distance, and the next swipe distance is
 * adjusted so the overlap stays near [ScrollConfig.targetOverlap]. If the overlap is lost anyway,
 * the step is not inserted; a counter-step goes back until shared rows are visible again.
 * Only when that fails is a gap marked.
 */
class ScrollController(
    private val dev: ScrollDevice,
    private val parser: ChatParser,
    private val merger: TranscriptMerger,
    private val startNotice: () -> Boolean,
    private val cfg: ScrollConfig,
    private val hooks: ScrollHooks,
    initial: PageRead,
) {
    enum class Mode { ADAPTIV, GENAU, ACTION }

    /** Why the run ends. */
    enum class EndKind { CHAT_START, LOADED_END, STUCK, NO_PROGRESS }

    class Stats {
        var attempts = 0
        var effective = 0
        var losses = 0
        var recoveredByCounterStep = 0
        var gapsAccepted = 0
        var counterSteps = 0
        var switchesToGenau = 0
        var switchesToAction = 0
        var staticSteps = 0
        var endWaits = 0
        var endWaitMs = 0L
        var measuredSum = 0L
        var measuredCount = 0
        var commandedSum = 0L
    }

    var cur: PageRead = initial
        private set
    var endKind: EndKind? = null
        private set
    var endReason: String = ""
        private set

    /** Start of the chat, or the end of the history loaded in WhatsApp, has been reached. */
    val atStart: Boolean get() = endKind == EndKind.CHAT_START || endKind == EndKind.LOADED_END
    val stuck: Boolean get() = endKind == EndKind.STUCK || endKind == EndKind.NO_PROGRESS
    var mode: Mode = if (cfg.method == ScrollMethod.ACTION) Mode.ACTION else Mode.ADAPTIV
        private set
    val stats = Stats()
    val policy = ScrollStopPolicy(failuresNeeded = 3, hardFailures = cfg.maxNoNewSteps)

    private var gain: Double? = null
    private var cmdPx: Int = 0
    private var ineffectiveSwipes = 0
    private var staticCount = 0
    private var recoveriesNoProgress = 0

    private class Settled(val snapshot: UiNode?, val changed: Boolean, val polls: Int, val waitedMs: Long)

    private class Move(
        val performed: Boolean,
        val snap: UiNode?,
        val page: ParsedPage?,
        val commanded: Int,
        /** In Genau: sum of the micro-shifts in pixels. */
        val chainShift: Int?,
        val polls: Int,
        val waitedMs: Long,
        val identicalAfterLongWait: Boolean,
    ) {
        val changed: Boolean get() = snap != null && page != null
    }

    private class Eval(val aligned: Boolean, val shiftPx: Int?, val pairs: Int)

    // ---- Helpers ------------------------------------------------------------------------------------

    private suspend fun settle(prev: ChatParser.PageSig?, maxMs: Long): Settled {
        val start = System.currentTimeMillis()
        var changedSeen = false
        var last: ChatParser.PageSig? = null
        var latest: UiNode? = null
        var polls = 0
        while (true) {
            coroutineContext.ensureActive()
            val snap = dev.snapshot()
            polls++
            if (snap != null) {
                latest = snap
                val sig = parser.signature(snap)
                if (sig != null) {
                    if (prev == null || sig.layout != prev.layout) changedSeen = true
                    if (changedSeen && last != null && sig.layout == last.layout) {
                        return Settled(snap, true, polls, System.currentTimeMillis() - start)
                    }
                    last = sig
                }
            }
            val used = System.currentTimeMillis() - start
            if (used >= maxMs) return Settled(latest, changedSeen, polls, used)
            delay(cfg.pollMs.coerceAtLeast(5))
        }
    }

    private class Smart(val s: Settled, val identical: Boolean)

    private suspend fun settleSmart(prev: ChatParser.PageSig?, canBack: Boolean?): Smart {
        val maxMs = if (canBack == false) 400L else cfg.settleMaxMs
        return Smart(settle(prev, maxMs), false)
    }

    /** On unchanged content, waits for more history to load. Returns the changed snapshot, or null. Counts the wait time in the stats. */
    private suspend fun endWait(): UiNode? {
        val start = System.currentTimeMillis()
        val base = dev.snapshot()
        val baseSig = base?.let { parser.signature(it) }
        var limit = cfg.endNoHintWaitMs
        var sawHint = false
        stats.endWaits++
        var result: UiNode? = null
        while (true) {
            coroutineContext.ensureActive()
            val st = settle(baseSig, cfg.endChunkMs)
            if (st.changed && st.snapshot != null) {
                result = st.snapshot
                break
            }
            if (!sawHint && st.snapshot?.let { parser.hasLoadingHint(it) } == true) {
                sawHint = true
                limit = cfg.endWaitMs
            }
            if (System.currentTimeMillis() - start >= limit) break
        }
        val used = System.currentTimeMillis() - start
        stats.endWaitMs += used
        hooks.log("Warten auf Nachladen: $used ms, Ladehinweis ${if (sawHint) "gesehen" else "nicht gesehen"}, ${if (result != null) "Inhalt hat sich geaendert" else "Inhalt unveraendert"}.")
        return result
    }
    private fun parseFrame(snap: UiNode?): ParsedPage? {
        if (snap == null) return null
        val p = parser.parse(snap)
        return if (p.listFound && p.items.isNotEmpty()) p else null
    }

    private fun evaluate(from: ParsedPage, to: ParsedPage, maxShiftFrac: Double): Eval {
        val x = from.items.map { it.message }
        val y = to.items.map { it.message }
        val m = FrameAligner.lcs(x, y)
        if (!FrameAligner.hasAnchor(x, m)) return Eval(false, null, m.size)
        val sh = FrameAligner.shift(from.items, to.items, m)
        val h = from.listBounds?.height ?: 0
        val ok = sh == null || h <= 0 || abs(sh) <= h * maxShiftFrac
        return Eval(ok, sh, m.size)
    }

    private suspend fun extraPause() {
        val lo = cfg.pauseMinMs
        val hi = cfg.pauseMaxMs
        if (hi <= 0L) return
        delay(if (hi > lo) Random.nextLong(lo, hi + 1) else lo)
    }

    // ---- Motions ------------------------------------------------------------------------------------

    private suspend fun moveAdaptive(list: Bounds, prevSig: ChatParser.PageSig?, canBack: Boolean?): Move {
        val h = list.height
        val cmd = if (cfg.selfCalibrate) cmdPx else (cfg.fixedStepFraction * h).toInt()
        val ok = dev.swipe(list, true, cmd, cfg.swipeMs, cfg.holdMs)
        if (!ok) return Move(false, null, null, cmd, null, 0, 0, false)
        val sm = settleSmart(prevSig, canBack)
        extraPause()
        val page = if (sm.s.changed) parseFrame(sm.s.snapshot) else null
        return Move(true, if (page != null) sm.s.snapshot else null, page, cmd, null, sm.s.polls, sm.s.waitedMs, sm.identical)
    }

    private suspend fun moveAction(list: Bounds, prevSig: ChatParser.PageSig?, canBack: Boolean?): Move {
        val ok = dev.action(list, true)
        if (!ok) return Move(false, null, null, 0, null, 0, 0, false)
        val sm = settleSmart(prevSig, canBack)
        extraPause()
        val page = if (sm.s.changed) parseFrame(sm.s.snapshot) else null
        return Move(true, if (page != null) sm.s.snapshot else null, page, 0, null, sm.s.polls, sm.s.waitedMs, sm.identical)
    }

    /** Genau mode: a chain of small swipes; after each one the screen is read and compared with the previous frame. */
    private suspend fun moveGenau(list: Bounds, prevSig0: ChatParser.PageSig?, canBack: Boolean?): Move {
        val h = list.height
        val micro = (cfg.microFraction * h).toInt().coerceAtLeast(20)
        val target = ((1.0 - cfg.targetOverlap) * h).toInt()
        val dur = maxOf(cfg.swipeMs, 450L)
        var total = 0
        var commanded = 0
        var prevPage = cur.page
        var prevSig = prevSig0
        var last: Pair<UiNode, ParsedPage>? = null
        var polls = 0
        var waited = 0L
        var identical = false
        for (k in 1..MAX_MICRO) {
            val ok = dev.swipe(list, true, micro, dur, cfg.holdMs)
            if (!ok) {
                if (k == 1) return Move(false, null, null, 0, null, 0, 0, false)
                break
            }
            commanded += micro
            val sm = settleSmart(prevSig, canBack)
            polls += sm.s.polls
            waited += sm.s.waitedMs
            identical = sm.identical
            val snap = sm.s.snapshot
            val pg = if (sm.s.changed) parseFrame(snap) else null
            if (snap == null || pg == null) break
            val ev = evaluate(prevPage, pg, 0.9)
            last = snap to pg
            if (!ev.aligned) break // loss in the middle of the chain: the caller checks against the last inserted page and recovers
            total += ev.shiftPx ?: micro
            prevPage = pg
            prevSig = parser.signature(snap)
            if (total >= target) break
        }
        extraPause()
        return Move(true, last?.first, last?.second, commanded, if (total > 0) total else null, polls, waited, identical)
    }

    private class Rec(val read: PageRead?, val static: Boolean)

    private fun isStaticShift(sh: Int?, h: Int): Boolean = sh != null && abs(sh) <= h * cfg.staticShiftFraction

    /**
     * Counter-step: go back toward newer messages until the screen again shows rows shared with the last
     * inserted page. If it then shows the same page (shift 0), the content did not move at all: result "static",
     * not a success. If it is newer than the last inserted page (negative shift), continue with a swipe toward older messages.
     */
    private suspend fun recover(list: Bounds, lostSnap: UiNode?): Rec {
        val h = list.height
        var sig = lostSnap?.let { parser.signature(it) }
        var tooNew = false
        for (i in 1..cfg.maxRecoverySteps) {
            coroutineContext.ensureActive()
            stats.counterSteps++
            val moved = if (tooNew) {
                dev.swipe(list, true, (0.4 * h).toInt(), maxOf(cfg.swipeMs, 450L), cfg.holdMs)
            } else if (dev.canScrollForward(list) != false && dev.action(list, false)) {
                true
            } else {
                dev.swipe(list, false, (0.5 * h).toInt(), maxOf(cfg.swipeMs, 450L), cfg.holdMs)
            }
            if (!moved) {
                hooks.warn("SCROLL: Gegenschritt $i nicht ausgefuehrt.")
                break
            }
            val st = settle(sig, cfg.settleMaxMs)
            val pg = if (st.changed) parseFrame(st.snapshot) else null
            if (pg == null || st.snapshot == null) {
                hooks.log("Gegenschritt $i: Bildschirm unveraendert oder nicht lesbar.")
                continue
            }
            val ev = evaluate(cur.page, pg, 1.15)
            hooks.log("Gegenschritt $i: ${if (ev.aligned) "gemeinsame Zeilen ${ev.pairs}, Verschiebung ${ev.shiftPx ?: -1} px" else "noch keine gemeinsamen Zeilen"}.")
            sig = parser.signature(st.snapshot)
            if (!ev.aligned) continue
            if (isStaticShift(ev.shiftPx, h)) return Rec(null, true)
            if (ev.shiftPx != null && ev.shiftPx < 0) {
                tooNew = true
                continue
            }
            tooNew = false
            val res = merger.add(pg.items, allowGap = false)
            if (res.noOverlap) continue
            if (ev.shiftPx == null && res.added == 0 && res.inserted == 0) return Rec(null, true)
            return Rec(PageRead(res, pg, st.snapshot), false)
        }
        return Rec(null, false)
    }

    // ---- One step -----------------------------------------------------------------------------------

    private fun clampCmd(v: Double, h: Int): Int = v.coerceIn(h * 0.05, h * ScrollPlan.MAX_COMMAND).toInt()

    private fun finish(kind: EndKind, reason: String): Boolean {
        endKind = kind
        endReason = reason
        hooks.log("Scrollen beendet: $reason")
        return false
    }

    /** One scroll attempt. false: the run should end (see [endKind] and [endReason]). */
    suspend fun scrollOnce(): Boolean {
        coroutineContext.ensureActive()
        stats.attempts++
        hooks.ensureForeground()
        val list = cur.page.listBounds ?: throw AgentException("Keine Listenposition fuer den Scroll bekannt.")
        val h = list.height
        if (cmdPx <= 0) cmdPx = clampCmd((if (cfg.selfCalibrate) INITIAL_FRACTION else cfg.fixedStepFraction) * h, h)
        val prevSig = parser.signature(cur.snap)
        val canBack = dev.canScrollBack(list)
        val modeNow = mode

        val mv = when (modeNow) {
            Mode.ACTION -> moveAction(list, prevSig, canBack)
            Mode.GENAU -> moveGenau(list, prevSig, canBack)
            Mode.ADAPTIV -> moveAdaptive(list, prevSig, canBack)
        }

        if (!mv.performed) {
            hooks.warn("SCROLL: $modeNow wurde nicht ausgefuehrt.")
            if (cfg.method == ScrollMethod.AUTO && modeNow != Mode.ACTION) {
                mode = Mode.ACTION
                stats.switchesToAction++
                hooks.log("Wischgeste nicht ausgefuehrt, wechsle zu ACTION_SCROLL_BACKWARD (Ueberlappung wird gemessen, aber nicht gesteuert).")
                return true
            }
        }

        var read: PageRead? = null
        var lost = false
        var ev: Eval? = null
        var recoveredByCounter = false
        var staticStep = false

        fun isNoNews(r: TranscriptMerger.Result, e: Eval?): Boolean =
            r.added == 0 && r.inserted == 0 && r.completed == 0 && (e == null || e.shiftPx == null || isStaticShift(e.shiftPx, h))

        if (mv.changed) {
            val page = mv.page!!
            ev = evaluate(cur.page, page, if (modeNow == Mode.ACTION) 1.15 else 1.05)
            if (ev.aligned) {
                val res = merger.add(page.items, allowGap = false)
                if (res.noOverlap) {
                    lost = true
                } else {
                    read = PageRead(res, page, mv.snap!!)
                    if (isNoNews(res, ev)) staticStep = true
                }
            } else {
                lost = true
            }
        } else if (mv.performed) {
            staticStep = true // tree unchanged
        }

        if (lost) {
            hooks.warn("SCROLL: keine Ueberlappung ($modeNow, befohlen ${mv.commanded} px, Verschiebung ${ev?.shiftPx ?: -1} px, gemeinsame Zeilen ${ev?.pairs ?: 0}).")
            // Read once more first: the screen may still have been moving on the first read.
            delay(250)
            val again = dev.snapshot()?.let { parseFrame(it) to it }
            if (again?.first != null) {
                val e2 = evaluate(cur.page, again.first!!, if (modeNow == Mode.ACTION) 1.15 else 1.05)
                if (e2.aligned) {
                    val res = merger.add(again.first!!.items, allowGap = false)
                    if (!res.noOverlap) {
                        read = PageRead(res, again.first!!, again.second)
                        ev = e2
                        lost = false
                        if (isNoNews(res, e2)) staticStep = true
                        hooks.log("Zweites Lesen hat die Ausrichtung ergeben (Bildschirm war noch in Bewegung).")
                    }
                }
            }
        }

        if (lost) {
            if (recoveriesNoProgress >= cfg.maxRecoveriesNoProgress) {
                return finish(
                    EndKind.NO_PROGRESS,
                    "Abbruch: ${recoveriesNoProgress} Wiederherstellungen per Gegenschritt ohne Nettofortschritt. Der Chatanfang ist NICHT bestaetigt.",
                )
            }
            val rec = recover(list, mv.snap)
            if (rec.static) {
                lost = false
                staticStep = true
                read = null
                hooks.log("Gegenschritt stellt dieselbe Seite wieder her (Verschiebung 0): Inhalt unveraendert, kein Ueberlappungsverlust, kein Moduswechsel.")
            } else if (rec.read != null) {
                read = rec.read
                lost = false
                recoveredByCounter = true
                stats.losses++
                stats.recoveredByCounterStep++
                recoveriesNoProgress++
                hooks.log("Ueberlappung durch Gegenschritt wiederhergestellt, Lauf setzt dort neu an.")
            } else {
                stats.losses++
                recoveriesNoProgress++
                // Last resort: insert the current screen with a gap
                val now = dev.snapshot()?.let { parseFrame(it)?.let { p -> p to it } }
                if (now != null) {
                    val res = merger.add(now.first.items, allowGap = true)
                    read = PageRead(res, now.first, now.second)
                    if (res.gapInserted) {
                        stats.gapsAccepted++
                        hooks.log("Ueberlappung nicht wiederherstellbar: Luecke wird markiert.")
                    }
                }
            }
            if (rec.read != null || (!rec.static)) {
                // Consequences of a real loss (the content moved, but is not aligned): smaller step, mode switch
                gain = null
                cmdPx = clampCmd(cmdPx * 0.5, h)
                if (cfg.method == ScrollMethod.AUTO) {
                    if (modeNow == Mode.ADAPTIV && stats.losses >= cfg.lossesForGenau) {
                        mode = Mode.GENAU
                        stats.switchesToGenau++
                        hooks.log("Wiederholter Ueberlappungsverlust (${stats.losses}): wechsle zum Modus Genau (Mikro-Wischer, lueckenlos).")
                    } else if (modeNow == Mode.GENAU && stats.losses >= cfg.lossesForGenau + 3) {
                        mode = Mode.ACTION
                        stats.switchesToAction++
                        hooks.log("Auch Genau verliert die Ueberlappung: wechsle zu ACTION_SCROLL_BACKWARD.")
                    }
                }
            }
        }

        // Unchanged content: end of the loaded history, or more is loading. No loss, no mode switch.
        var endStatic: Boolean? = null
        if (staticStep) {
            stats.staticSteps++
            staticCount++
            val signal = startNotice() || canBack == false
            val neverMoved = stats.measuredCount == 0 && modeNow != Mode.ACTION && mv.performed && canBack == true
            hooks.log(
                "Inhalt unveraendert (Versuch ${stats.attempts}, $modeNow, ${staticCount}. Mal in Folge, Anfangssignal ${if (signal) "ja" else "nein"}, " +
                    "Bestand ${merger.messages.size} Zeilen).",
            )
            if (cfg.endOnStatic) {
                if (signal) {
                    endStatic = true
                } else if (!neverMoved) {
                    val snap = endWait()
                    if (snap != null) {
                        val pg = parseFrame(snap)
                        val e3 = if (pg != null) evaluate(cur.page, pg, 1.05) else null
                        if (pg != null && e3 != null && e3.aligned) {
                            val res = merger.add(pg.items, allowGap = false)
                            if (!res.noOverlap) {
                                val r2 = PageRead(res, pg, snap)
                                if (!isNoNews(res, e3)) {
                                    read = r2
                                    staticStep = false
                                    staticCount = 0
                                    hooks.log("Nachgeladen: ${res.added} neue Zeilen.")
                                } else {
                                    read = r2
                                }
                            }
                        } else {
                            hooks.log("Inhalt hat sich geaendert, ist aber nicht mit der letzten Seite ausgerichtet; naechster Schritt prueft erneut.")
                            staticStep = false
                        }
                    }
                    if (staticStep && staticCount >= cfg.staticAttempts) endStatic = false
                }
            }
            // A swipe that was too weak should be larger on the next attempt (at the real end this does no harm)
            if (staticStep && modeNow == Mode.ADAPTIV && cfg.selfCalibrate && mv.performed) cmdPx = clampCmd(cmdPx * 1.3, h)
            // Treat the swipe as ineffective only when no swipe has ever moved anything yet
            if (staticStep && neverMoved) {
                ineffectiveSwipes++
                if (ineffectiveSwipes >= 2 && cfg.method == ScrollMethod.AUTO) {
                    mode = Mode.ACTION
                    staticCount = 0
                    endStatic = null
                    stats.switchesToAction++
                    hooks.log("Wischgeste wirkungslos, obwohl die Liste noch scrollbar ist und noch nie etwas bewegt hat: wechsle zu ACTION_SCROLL_BACKWARD.")
                }
            }
        } else {
            staticCount = 0
        }

        // Adjustment from the measurement
        val measured: Int? = if (read != null && !lost && !recoveredByCounter && !staticStep) (ev?.shiftPx ?: mv.chainShift) else null
        if (measured != null) {
            stats.measuredSum += abs(measured)
            stats.measuredCount++
            stats.commandedSum += mv.commanded
        }
        if (modeNow == Mode.ADAPTIV && cfg.selfCalibrate && !lost && !recoveredByCounter && !staticStep && mv.performed) {
            val moved = measured ?: 0
            if (moved > h * 0.02 && mv.commanded > 0) {
                val g = moved.toDouble() / mv.commanded
                gain = gain?.let { 0.5 * it + 0.5 * g } ?: g
                val target = (1.0 - cfg.targetOverlap) * h
                cmdPx = clampCmd(target / gain!!, h)
            } else if (!mv.changed || moved <= h * 0.02) {
                cmdPx = clampCmd(cmdPx * 1.5, h)
            }
        }

        val added = read?.res?.added ?: 0
        val gotNew = read != null && (read.res.added > 0 || read.res.inserted > 0)
        if ((mv.changed && !staticStep) || recoveredByCounter) {
            stats.effective++
        }
        if (!staticStep && gotNew) {
            ineffectiveSwipes = 0
            recoveriesNoProgress = 0
        }
        // Every step with no new rows counts as a failed attempt, including after a counter-step
        policy.record(added > 0 || (read?.res?.inserted ?: 0) > 0)
        if (read != null) cur = read

        val overlapPct = measured?.let { ((1.0 - abs(it).toDouble() / h) * 100).toInt().coerceIn(-100, 100) }
        val rows = mv.page?.rowCount ?: read?.page?.rowCount ?: 0
        if (read != null && !staticStep) {
            hooks.log(
                "Schritt ${stats.effective} (Versuch ${stats.attempts}, $modeNow): befohlen ${mv.commanded} px, gemessen ${measured ?: -1} px" +
                    (gain?.let { ", Faktor ${"%.2f".format(it)}" } ?: "") +
                    ", Ueberlappung ${overlapPct ?: -1} Prozent von $h px, Zeilen $rows, neue ${read.res.added}, zugeordnet ${read.res.overlapRows}, " +
                    "ergaenzt ${read.res.completed}, eingefuegt ${read.res.inserted}, angeschnitten offen ${merger.incompleteCount()}, " +
                    "${mv.polls} Abfragen in ${mv.waitedMs} ms.",
            )
            hooks.afterMerge(read)
        } else if (read == null && !staticStep) {
            hooks.log("Versuch ${stats.attempts} ($modeNow): kein verwertbarer Bildschirm (${policy.failures} Fehlversuche in Folge).")
        }

        if (endStatic == true) {
            return finish(EndKind.CHAT_START, "Chatanfang erreicht (${if (startNotice()) "Verschluesselungshinweis gesehen" else "Scrollen rueckwaerts laut Bedienungshilfe nicht mehr moeglich"}, Inhalt unveraendert).")
        }
        if (endStatic == false) {
            return finish(EndKind.LOADED_END, "Anfang des geladenen Verlaufs erreicht: Inhalt blieb nach $staticCount Wischversuchen mit Wartezeit unveraendert (WhatsApp laedt nichts mehr nach, Chatanfang nicht bestaetigt).")
        }
        val signalNow = startNotice() || canBack == false
        return when (policy.decide(signalNow)) {
            ScrollStopPolicy.Decision.CHAT_START -> finish(
                EndKind.CHAT_START,
                "Chatanfang erreicht nach ${policy.failures} Schritten ohne neue Zeilen (${if (startNotice()) "Verschluesselungshinweis gesehen" else "Scrollen rueckwaerts nicht mehr moeglich"}).",
            )
            ScrollStopPolicy.Decision.STUCK -> finish(
                EndKind.STUCK,
                "Abbruch: ${policy.failures} Schritte in Folge ohne neue Zeilen, kein sicheres Anfangssignal. Der Chatanfang ist NICHT bestaetigt.",
            )
            ScrollStopPolicy.Decision.CONTINUE -> true
        }
    }

    /** One line with the summary of the adjustment, for the log and the status display. */
    fun summary(): String {
        val avgMeasured = if (stats.measuredCount > 0) stats.measuredSum / stats.measuredCount else -1
        return "Modus am Ende $mode, Versuche ${stats.attempts}, wirksame Schritte ${stats.effective}, Ueberlappungsverluste ${stats.losses}, " +
            "durch Gegenschritt behoben ${stats.recoveredByCounterStep}, Gegenschritte ${stats.counterSteps}, Luecken ${stats.gapsAccepted}, " +
            "Wechsel zu Genau ${stats.switchesToGenau}, zu ACTION ${stats.switchesToAction}, unveraenderte Seiten ${stats.staticSteps}, Nachlade-Wartezeiten ${stats.endWaits} (${stats.endWaitMs} ms), mittlerer gemessener Weg $avgMeasured px" +
            (gain?.let { ", letzter Faktor ${"%.2f".format(it)}" } ?: "")
    }

    private companion object {
        const val INITIAL_FRACTION = 0.30
        const val MAX_MICRO = 8
    }
}
