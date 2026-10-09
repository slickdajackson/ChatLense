package app.chatlens.agent

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import app.chatlens.checkup.CheckupDevice
import app.chatlens.checkup.CheckupScan
import app.chatlens.checkup.CheckupScanner
import app.chatlens.checkup.ScrollTry
import app.chatlens.core.Bounds
import app.chatlens.core.UiNode
import app.chatlens.match.ChatListEntry
import app.chatlens.match.NameMatcher
import app.chatlens.parse.ChatListParser
import app.chatlens.data.AppLog
import app.chatlens.parse.ChatParser
import app.chatlens.profile.SelectorProfile
import app.chatlens.service.ChatAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

open class AgentException(message: String) : Exception(message)

/** Der Chat wurde in Suche und sichtbarer Liste nicht (eindeutig) gefunden. Ursache liegt beim Namen, nicht bei der Navigation. */
class ChatNotFoundException(message: String) : AgentException(message), app.chatlens.auto.KindedFailure {
    override val kindName: String get() = "Chat nicht gefunden"
}

/** WhatsApp war nicht vorn oder liess sich nicht lesen (Systemoberflaeche, Launcher, eigene App). Ursache liegt bei der Navigation. */
class NavigationException(message: String) : AgentException(message), app.chatlens.auto.KindedFailure {
    override val kindName: String get() = "Navigationsfehler (WhatsApp nicht vorn oder nicht lesbar)"
}

enum class ScreenState { CHAT, SEARCH_ACTIVE, OTHER, NOT_WHATSAPP, LIST }

/**
 * Oeffnet einen Chat ueber die WhatsApp-Suche. Alle Annahmen stehen im Selektor-Profil und sind
 * unkalibriert (siehe PLAN.md). Die Klasse schreibt nie in das Nachrichtenfeld: Text wird nur in ein
 * Feld im oberen Bildschirmbereich (Suchfeld) gesetzt.
 */
class WhatsAppNavigator(
    private val svc: ChatAccessibilityService,
    private val profile: SelectorProfile,
    private val parser: ChatParser,
    private val searchWaitMs: Long = 4_000,
    private val diagDump: () -> String? = { null },
    /** Vordergrundwaechter. Auf dem Geraet mit dem Dienst als Messpunkt, in Tests ein Fake. */
    val guard: ForegroundGuard = ForegroundGuard(ServiceForegroundProbe(svc, profile.launchPackage), profile.packageName),
) {
    init {
        // Nur Fenster dieses Pakets werden gelesen oder bedient (siehe ChatAccessibilityService.liveRoot).
        svc.expectedPackage = profile.packageName
    }

    private val titleIds get() = profile.chatListRowNameIds
    private val containerIds get() = profile.chatListRowContainerIds

    /** Tippt den Tab Chats in der unteren Leiste (Knoten mit der Beschriftung im unteren Bereich, per ACTION_CLICK auf den klickbaren Vorfahren). */
    private fun clickChatsTab(): Boolean {
        val root = svc.liveRoot() ?: return false
        val h = svc.boundsOf(root).b.coerceAtLeast(1)
        val node = svc.findLive(root) { n ->
            if (!n.isVisibleToUser || svc.boundsOf(n).centerY <= h * profile.tabBarTopFraction) return@findLive false
            listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).any { l ->
                val t = l.trim()
                profile.tabLabelsChats.any { c -> t.equals(c, true) || ((t.startsWith("$c,", true) || t.startsWith("$c ", true)) && t.length <= c.length + 30) }
            }
        } ?: return false
        return clickWithAncestors(node, h)
    }

    /** Zeilen der Chatliste, bevorzugt ueber Knoten-IDs. */
    private fun listRows(snap: UiNode) = ChatListParser.parse(snap, titleIds = titleIds, containerIds = containerIds, unreadIds = profile.chatListUnreadIds)

    /** Zustand fuer das reine Listenlesen, siehe [ListScreen.classify]. */
    fun classifyList(root: UiNode?, imeVisible: Boolean): ScreenState = ListScreen.classify(root, profile, imeVisible)

    fun classify(root: UiNode?): ScreenState {
        if (root == null) return ScreenState.NOT_WHATSAPP
        val h = root.bounds.b.coerceAtLeast(1)
        val editables = root.walk().filter { it.editable && it.visible }.toList()
        val bottom = editables.any { it.bounds.centerY > h * profile.messageInputBottomFraction }
        if (bottom) return ScreenState.CHAT
        val top = editables.any { it.bounds.centerY < h * profile.searchFieldTopFraction }
        if (top) return ScreenState.SEARCH_ACTIVE
        return ScreenState.OTHER
    }

    suspend fun waitForWhatsApp(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            coroutineContext.ensureActive()
            if (guard.isForeground()) return true
            delay(300)
        }
        return false
    }

    private fun nav(msg: String) = AppLog.i("NAV: $msg")

    /** Tipp-Geste nur, wenn WhatsApp wirklich vorn ist. Sonst wird nichts angetippt. */
    private suspend fun safeTap(x: Int, y: Int): Boolean {
        if (!guard.isForeground()) {
            nav("Tipp-Geste verweigert: Vordergrund ist nicht ${profile.packageName} (${guard.describe()}).")
            return false
        }
        return svc.tap(x, y)
    }

    /** Zurueck nur, wenn WhatsApp wirklich vorn ist (Zurueck in einem fremden Fenster koennte dort etwas schliessen). */
    private fun guardedBack(): Boolean {
        if (!guard.isForeground()) {
            nav("Zurueck verweigert: Vordergrund ist nicht ${profile.packageName} (${guard.describe()}).")
            return false
        }
        nav("Zurueck gedrueckt (${guard.describe()}).")
        return svc.goBack()
    }

    /** Speichert (falls moeglich) einen maskierten Diagnose-Baum und bricht dann mit [msg] ab. */
    private fun fail(msg: String): Nothing {
        val name = runCatching { diagDump() }.getOrNull()
        nav("Abbruch: $msg" + if (name != null) " (Diagnose-Baum: $name)" else "")
        throw AgentException(msg + if (name != null) " Diagnose-Baum (maskiert) gespeichert: $name (Tab Debug)." else "")
    }

    /** Zeitstempel fuer Phasenzeilen im Log: keine stillen Luecken, jede Phase meldet Beginn, Ende und Dauer. */
    private var phaseStart = 0L
    private fun lap(msg: String, log: ((String) -> Unit)? = null) {
        val now = System.currentTimeMillis()
        if (phaseStart == 0L) phaseStart = now
        nav("SUCHE: $msg (+${now - phaseStart} ms)")
        log?.invoke(msg)
    }

    /**
     * Liest den Baum von WhatsApp. Ist er nicht lesbar (anderes Fenster vorn), wird bis zu [TREE_RETRIES] Mal kurz gewartet und neu gelesen,
     * danach greift der Vordergrundwaechter (holt WhatsApp zurueck oder bricht mit Navigationsfehler ab).
     */
    private suspend fun readTree(what: String): UiNode? {
        for (i in 1..TREE_RETRIES) {
            coroutineContext.ensureActive()
            svc.snapshot()?.let { return it }
            nav("Baum nicht lesbar ($what), Versuch $i von $TREE_RETRIES: ${guard.describe()}; Fenster: ${svc.windowsSummary()}. Warte ${TREE_RETRY_WAIT_MS} ms.")
            delay(TREE_RETRY_WAIT_MS)
        }
        guard.ensure(true, what)
        return svc.snapshot()
    }

    /** "Chat nicht gefunden": Debug-Baum der Suchseite wird automatisch gespeichert (Debug-Verzeichnis), dann Abbruch mit Grund. */
    private fun failNotFound(msg: String): Nothing {
        val name = runCatching { diagDump() }.getOrNull()
        nav("Abbruch (Chat nicht gefunden): $msg" + if (name != null) " (Diagnose-Baum der Suchseite: $name)" else " (Diagnose-Baum nicht gespeichert)")
        throw ChatNotFoundException(msg + if (name != null) " Debug-Baum der Suchseite (maskiert) gespeichert: $name (Tab Debug)." else "")
    }

    suspend fun openChat(titleIn: String, log: (String) -> Unit, confirm: (suspend (String, Int) -> Boolean)? = null) {
        var title = titleIn
        if (title.isBlank()) throw AgentException("Chat-Titel fehlt.")
        phaseStart = System.currentTimeMillis()
        nav("SUCHE: Start der Navigation. Titel hat ${title.trim().length} Zeichen. Suchtreffer-Wartezeit ${searchWaitMs} ms.")
        guard.ensure(true, "Namenssuche")
        delay(800)

        // 1) Zum Zustand "Suche aktiv" kommen
        var attempts = 0
        while (true) {
            coroutineContext.ensureActive()
            if (++attempts > 8 || System.currentTimeMillis() - phaseStart > PHASE_OPEN_SEARCH_MS) fail("Suchfeld nicht erreichbar (Versuch $attempts, Zeitlimit ${PHASE_OPEN_SEARCH_MS / 1000} s). Debug-Baum der Chatliste exportieren und Profil kalibrieren.")
            guard.ensure(true, "Namenssuche")
            val snap = readTree("Namenssuche")
            val state = classify(snap)
            nav("Schritt 1 (Versuch $attempts): Bildschirmzustand $state")
            when (state) {
                ScreenState.NOT_WHATSAPP -> {
                    guard.ensure(true, "Namenssuche")
                    delay(500)
                }
                ScreenState.CHAT -> {
                    log("In einem Chat: zurueck zur Chatliste.")
                    guardedBack()
                    delay(900)
                }
                ScreenState.SEARCH_ACTIVE -> { lap("Suche offen", log); break }
                ScreenState.OTHER, ScreenState.LIST -> {
                    if (snap == null) { delay(500); continue }
                    if (!clickSearchButton(snap)) {
                        fail("Suchsymbol nicht gefunden (kein Treffer per ID oder Beschreibung). Profil kalibrieren.")
                    }
                    lap("Suchsymbol angetippt", log)
                    delay(900)
                }
            }
        }

        // 2) Suchtext eintragen
        val field = findSearchField() ?: fail("Suchfeld nicht gefunden.")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, title)
        }
        val setOk = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        nav("Schritt 2: ACTION_SET_TEXT im Suchfeld (id=${field.viewIdResourceName}) Ergebnis=$setOk")
        if (!setOk) fail("Suchtext konnte nicht gesetzt werden.")
        lap("Suchtext gesetzt (${title.trim().length} Zeichen)", log)
        delay(900)

        // 3) Treffer im Abschnitt "Chats" waehlen: warten, bis Ergebnisse da sind, notfalls Liste scrollen
        lap("Suche den Treffer im Abschnitt Chats", log)
        val deadline = System.currentTimeMillis() + searchWaitMs
        var scrolls = 0
        var hit: SearchHit? = null
        var lastSummary = ""
        var polls = 0
        var lastSnap: UiNode? = null
        while (true) {
            coroutineContext.ensureActive()
            guard.ensure(true, "Namenssuche")
            val snap = readTree("Trefferliste")
            if (snap != null) {
                lastSnap = snap
                polls++
                val res = SearchResultPicker.pick(snap, title, profile)
                val sum = res.summary()
                if (sum != lastSummary) {
                    nav("Schritt 3 (Abfrage $polls, gescrollt $scrolls): $sum")
                    lastSummary = sum
                }
                if (res.hit != null) {
                    hit = res.hit
                    lap("Treffer ${res.candidates.size} gefunden (${res.reason})", null)
                    break
                }
                if (res.ambiguous > 1) failNotFound("Mehrdeutig: ${res.ambiguous} verschiedene Chats heissen gleich (Name hat ${title.trim().length} Zeichen). Aus Sicherheit wird keiner gewaehlt. $lastSummary")
            }
            if (System.currentTimeMillis() < deadline) {
                delay(500)
                continue
            }
            if (scrolls < MAX_RESULT_SCROLLS && scrollResultsForward()) {
                scrolls++
                nav("Schritt 3: Ergebnisliste vorwaerts gescrollt ($scrolls von $MAX_RESULT_SCROLLS).")
                delay(800)
                continue
            }
            break
        }
        if (hit == null) {
            // Kein exakter Treffer: aehnlichsten Chat suchen und den Nutzer fragen (nie stillschweigend nehmen)
            var fz = lastSnap?.let { SearchResultPicker.pickFuzzy(it, title, profile) }
            if (fz == null) {
                val shorter = NameMatcher.shorterQuery(title)
                val field2 = if (shorter != null && shorter != title.trim()) findSearchField() else null
                if (field2 != null) {
                    val a2 = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, shorter) }
                    val ok2 = field2.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a2)
                    nav("Schritt 3b: kein exakter Treffer, zweite Suche mit kuerzerem Text (${shorter!!.length} Zeichen) Ergebnis=$ok2")
                    log("Kein exakter Treffer, suche mit kuerzerem Text ...")
                    val end2 = System.currentTimeMillis() + 3_000
                    delay(900)
                    while (fz == null && System.currentTimeMillis() < end2) {
                        coroutineContext.ensureActive()
                        val sn = svc.snapshot()
                        if (sn != null) fz = SearchResultPicker.pickFuzzy(sn, title, profile)
                        if (fz == null) delay(400)
                    }
                }
            }
            if (fz != null && confirm != null) {
                nav("Schritt 3b: aehnlichster Treffer mit ${fz.percent} Prozent (Name hat ${fz.name.length} Zeichen), frage den Nutzer.")
                log("Aehnlichster Treffer ${fz.percent} Prozent, frage nach.")
                val yes = confirm(fz.name, fz.percent)
                nav("Schritt 3b: Antwort des Nutzers: ${if (yes) "ja" else "nein oder keine Antwort"}")
                if (!yes) fail("Der ähnlichste Treffer (${fz.percent} Prozent) wurde vom Nutzer abgelehnt oder nicht bestätigt. Es wurde kein Chat geöffnet.")
                hit = fz.hit
                title = fz.name
            } else if (fz != null) {
                fail("Kein exakter Treffer; ähnlichster wäre \"${fz.name}\" (${fz.percent} Prozent), aber es gibt hier keine Nachfrage. Abbruch.")
            }
        }
        if (hit == null) {
            failNotFound("Im Suchergebnis wurde kein Textknoten mit diesem Titel (Namensabgleich ohne Umlaute und Gross/Klein) im Abschnitt Chats erkannt (Gruppen werden nie gewaehlt). Das heisst nicht, dass er nicht sichtbar ist: moeglicherweise stellt WhatsApp ihn anders im Baum dar. Befund: $lastSummary")
        }
        val h = hit
        nav(
            "Schritt 3: Gewaehlter Treffer: Textknoten ${h.title.shortClass} id=${h.title.viewId} b=${h.title.bounds}, " +
                "Abschnitt=${h.section ?: "unbekannt"}, Rueckfall=${h.viaFallback}, " +
                "Klickziel=" + (h.clickTarget?.let { "${it.shortClass} id=${it.viewId} b=${it.bounds}" } ?: "keines"),
        )

        // 4a) ACTION_CLICK auf den klickbaren Elternknoten
        val live = svc.findLive { n ->
            NameMatcher.normalize(n.text?.toString().orEmpty()) == NameMatcher.normalize(h.title.text.orEmpty()) && svc.boundsOf(n) == h.title.bounds
        }
        val screenH = svc.liveRoot()?.let { svc.boundsOf(it).b } ?: h.title.bounds.b
        val clicked = if (live != null) clickWithAncestors(live, screenH) else false
        nav("Schritt 4a: Live-Knoten gefunden=${live != null}, ACTION_CLICK ausgefuehrt=$clicked")
        val clickInfo = when {
            live == null -> "Live-Knoten des Treffers war nicht mehr auffindbar"
            clicked -> "ACTION_CLICK wurde ausgefuehrt, der Bildschirm wechselte aber nicht"
            else -> "kein klickbarer Elternknoten nahm ACTION_CLICK an"
        }
        val clickAt = System.currentTimeMillis()
        lap(if (clicked) "Klick auf den Treffer ausgefuehrt" else "Klick auf den Treffer nicht moeglich", log)
        var state = if (clicked) awaitChat(2_800) else ScreenState.SEARCH_ACTIVE
        if (state != ScreenState.CHAT && conversationSince(clickAt)) {
            // Kein zweiter Tipp, wenn die Unterhaltung gerade aufgeht
            nav("Schritt 4a: Aktivitaet ${guard.activity()} meldet die Unterhaltung, warte weiter statt zu tippen.")
            state = awaitOpened(clickAt, 4_000)
        }
        nav("Schritt 4a: Zustand nach ACTION_CLICK: $state")

        // 4b) Rueckfall: Tipp-Geste auf die Mitte der Zeile bzw. des Titelknotens
        var tapInfo = "Tipp-Geste nicht versucht"
        if (state != ScreenState.CHAT) {
            val r = tapFallback(title, log)
            state = r.first
            tapInfo = r.second
        }

        // 5) Erfolg pruefen: Chat offen und Kopfzeile zeigt den Titel
        if (state != ScreenState.CHAT) {
            fail(
                "Treffer im Abschnitt Chats wurde gefunden, aber das Antippen blieb wirkungslos (Bildschirmzustand danach: $state). " +
                    "$clickInfo; $tapInfo.",
            )
        }
        val snap = svc.snapshot() ?: fail("Kein Accessibility-Baum nach dem Oeffnen des Chats.")
        val headerOk = headerMatches(snap, title)
        nav("Schritt 5: Kopfzeile enthaelt den Titel=$headerOk")
        if (headerOk) lap("Unterhaltung bestaetigt", null)
        if (!headerOk) {
            fail("Ein Chat wurde geoeffnet, aber die Kopfzeile zeigt nicht den gesuchten Titel (falscher Chat oder Titel in der Kopfzeile anders geschrieben). Abbruch zur Sicherheit, nichts wird gelesen.")
        }
        log("Chat geoeffnet, Kopfzeile stimmt.")
        delay(600)
    }

    /**
     * Einstieg fuer Setup, Auto und "Chat per Namen starten". Primaer die WhatsApp-Suche ([openChat]). Scheitert sie am Namen
     * (nicht gefunden oder mehrdeutig, nicht bei Navigationsfehlern), folgt der Rueckfall: sichtbare Zeile der Chatliste ohne langes Scrollen
     * ([openFromList], hoechstens [LIST_PAGES] Seiten je Richtung). Ist der Name mehrdeutig, gibt es keinen Rueckfall: lieber Fehler als raten.
     */
    suspend fun openChatForRun(title: String, log: (String) -> Unit, confirm: (suspend (String, Int) -> Boolean)? = null) {
        try {
            openChat(title, log, confirm)
        } catch (e: ChatNotFoundException) {
            if (e.message.orEmpty().startsWith("Mehrdeutig")) throw e
            nav("SUCHE: Suche ohne Treffer, Rueckfall auf die sichtbare Chatliste (hoechstens $LIST_PAGES Seiten). Grund: ${e.message?.take(160)}")
            log("Suche ohne Treffer, versuche die sichtbare Chatliste.")
            openFromList(title, log)
        } catch (e: AgentException) {
            // Suchfeld/Suchsymbol nicht erreichbar o.ae. (kein Navigationsfehler): dieselbe Rueckfallstufe
            if (e is NavigationException) throw e
            nav("SUCHE: Suche gescheitert (${e.message?.take(160)}), Rueckfall auf die sichtbare Chatliste.")
            log("Suche gescheitert, versuche die sichtbare Chatliste.")
            openFromList(title, log)
        }
    }

    /** Nach einem Chat sauber zurueck: Zurueck bis zur Chatliste, Tab Chats, Suchfeld ist damit geschlossen. Fehler werden nur geloggt. */
    suspend fun returnToList(log: (String) -> Unit) {
        try {
            nav("SUCHE: Zurueck zur Chatliste (Zurueck, Tab Chats pruefen, Suche schliessen).")
            ensureChatList(log)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            nav("SUCHE: Rueckkehr zur Chatliste nicht bestaetigt: ${e.message?.take(160)}")
        }
    }

    /** Wartet, bis ein Chat (Nachrichtenfeld unten) erkannt wird, oder bis die Zeit um ist. Gibt den letzten Zustand zurueck. */
    private suspend fun awaitChat(timeoutMs: Long): ScreenState {
        val end = System.currentTimeMillis() + timeoutMs
        var last = ScreenState.OTHER
        while (System.currentTimeMillis() < end) {
            coroutineContext.ensureActive()
            delay(400)
            guard.ensure(true, "Namenssuche")
            last = classify(svc.snapshot())
            if (last == ScreenState.CHAT) return last
        }
        return last
    }

    /**
     * Rueckfall per Tipp-Geste (dispatchGesture) nach kurzer Verzoegerung. Zielpunkte in dieser Reihenfolge:
     * Mitte der klickbaren Zeile, Mitte des Titelknotens. Jeder Punkt muss im Listenbereich liegen
     * (unter dem Suchfeld, ueber der Tastatur). Gibt Zustand und eine Beschreibung fuer die Fehlermeldung zurueck.
     */
    private suspend fun tapFallback(title: String, log: (String) -> Unit): Pair<ScreenState, String> {
        delay(400)
        val snap = svc.snapshot() ?: return ScreenState.NOT_WHATSAPP to "Tipp-Geste nicht moeglich, kein Accessibility-Baum"
        val st = classify(snap)
        if (st == ScreenState.CHAT) return st to "Seite wechselte verzoegert"
        if (st != ScreenState.SEARCH_ACTIVE) {
            nav("Schritt 4b: Tipp-Geste uebersprungen, Zustand ist $st (nicht Suche aktiv).")
            return st to "Tipp-Geste uebersprungen (Zustand $st)"
        }
        val res = SearchResultPicker.pick(snap, title, profile)
        val hit = res.hit
        if (hit == null) {
            nav("Schritt 4b: Tipp-Geste uebersprungen, Treffer nicht mehr gefunden: ${res.summary()}")
            return st to "Treffer war fuer die Tipp-Geste nicht mehr im Baum (${res.reason})"
        }
        val screenH = snap.bounds.b
        val screenW = snap.bounds.r
        val ime = svc.imeTop()
        // Ohne erkennbare Tastatur (Fensterliste evtl. auf WhatsApp beschraenkt) vorsichtig nur die obere Haelfte antippen
        val limit = ime ?: (screenH * 0.5).toInt()
        val points = listOfNotNull(
            hit.clickTarget?.let { it.bounds.centerX to it.bounds.centerY },
            hit.title.bounds.centerX to hit.title.bounds.centerY,
        ).distinct()
        var tried = 0
        var lastState = st
        val notes = ArrayList<String>()
        for ((x, y) in points) {
            coroutineContext.ensureActive()
            if (x !in 1 until screenW || y <= res.searchBottom || y >= limit - 4) {
                nav("Schritt 4b: Punkt ($x,$y) uebersprungen (Liste ab y>${res.searchBottom}, Tastatur ab ${ime ?: "nicht erkannt, Grenze bei halber Hoehe $limit"}, Bildschirm ${screenW}x$screenH).")
                notes.add("Punkt ($x,$y) lag ausserhalb des sicheren Bereichs")
                continue
            }
            log("Tipp-Geste auf den Treffer ($x,$y).")
            val ok = safeTap(x, y)
            tried++
            nav("Schritt 4b: Tipp-Geste bei ($x,$y) abgeschlossen=$ok, Tastatur ab ${ime ?: "nicht erkannt"}")
            lastState = awaitChat(3_000)
            nav("Schritt 4b: Zustand nach Tipp-Geste: $lastState")
            notes.add("Tipp-Geste bei ($x,$y) ${if (ok) "ausgefuehrt" else "vom System abgelehnt oder abgebrochen"}, Seite wechselte" + (if (lastState == ScreenState.CHAT) "" else " nicht"))
            if (lastState == ScreenState.CHAT) break
            // vor dem naechsten Versuch sicherstellen, dass die Suche noch aktiv ist
            if (classify(svc.snapshot()) != ScreenState.SEARCH_ACTIVE) break
        }
        if (tried == 0) notes.add("keine Tipp-Geste ausgefuehrt")
        return lastState to notes.joinToString("; ")
    }

    /** Scrollt die groesste sichtbare scrollbare Liste unterhalb des Suchfelds einen Schritt vorwaerts. */
    private fun scrollResultsForward(): Boolean {
        val root = svc.liveRoot() ?: return false
        val h = svc.boundsOf(root).b.coerceAtLeast(1)
        val lists = svc.findAllLive(root) { n ->
            n.isScrollable && n.isVisibleToUser && svc.boundsOf(n).centerY > h * profile.searchFieldTopFraction &&
                n.className?.toString()?.contains("ViewPager", ignoreCase = true) != true &&
                n.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id }
        }
        val list = lists.maxByOrNull { svc.boundsOf(it).area } ?: return false
        return list.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
    }

    private fun headerMatches(root: UiNode, title: String): Boolean {
        val h = root.bounds.b.coerceAtLeast(1)
        val want = NameMatcher.normalize(title)
        return root.walk().any {
            it.hasText() && !it.editable && it.bounds.t < h * profile.headerTopFraction && NameMatcher.normalize(it.text!!).let { got ->
                want.isNotEmpty() && (got.contains(want) || (got.length >= 4 && want.contains(got)))
            }
        }
    }

    private fun isDenied(n: AccessibilityNodeInfo): Boolean {
        val id = n.viewIdResourceName ?: return false
        return profile.denyClickIdSubstrings.any { id.contains(it, ignoreCase = true) } ||
            id in profile.sendButtonIds || id in profile.messageInputIds
    }

    private fun clickSearchButton(snap: UiNode): Boolean {
        val h = snap.bounds.b.coerceAtLeast(1)
        val node = svc.findLive { n ->
            if (!n.isVisibleToUser) return@findLive false
            val b = svc.boundsOf(n)
            if (b.centerY > h * profile.searchFieldTopFraction) return@findLive false
            val byId = profile.useKnownIds && n.viewIdResourceName in profile.searchButtonIds
            if (n.isEditable) return@findLive false
            // Beschreibung oder Text enthaelt "Suchen"/"Search" (auch Suchleiste mit Platzhaltertext)
            val labels = listOfNotNull(n.contentDescription?.toString(), n.text?.toString())
            val byDesc = labels.any { l -> profile.searchButtonDescriptions.any { l.contains(it, ignoreCase = true) } }
            byId || byDesc
        } ?: return false
        return clickWithAncestors(node)
    }

    private fun findSearchField(): AccessibilityNodeInfo? {
        val root = svc.liveRoot() ?: return null
        val h = svc.boundsOf(root).b.coerceAtLeast(1)
        return svc.findLive(root) { n ->
            n.isEditable && n.isVisibleToUser && svc.boundsOf(n).centerY < h * profile.searchFieldTopFraction && !isDenied(n)
        }
    }

    /**
     * Klickt den naechsten klickbaren Knoten ab [start] aufwaerts. Ein Vorfahr, der mehr als 60 Prozent der
     * Bildschirmhoehe einnimmt, gilt nicht als Zeile und wird nicht geklickt. Gesperrte IDs werden nie geklickt.
     */
    private fun clickWithAncestors(start: AccessibilityNodeInfo, screenH: Int = 0): Boolean {
        if (!guard.isForeground()) {
            nav("Klick verweigert: Vordergrund ist nicht ${profile.packageName} (${guard.describe()}).")
            return false
        }
        var n: AccessibilityNodeInfo? = start
        var hops = 0
        while (n != null && hops < 8) {
            if (isDenied(n)) {
                nav("Klick abgebrochen: gesperrte ID ${n.viewIdResourceName}")
                return false
            }
            if (n.isClickable) {
                val b = svc.boundsOf(n)
                if (screenH > 0 && b.height * 10 > screenH * 6) {
                    nav("Klick abgebrochen: klickbarer Vorfahr ${n.className} ist zu gross (b=$b).")
                    return false
                }
                val ok = n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                nav("ACTION_CLICK auf ${n.className} id=${n.viewIdResourceName} b=$b nach $hops Schritten aufwaerts: $ok")
                if (ok) return true
            }
            n = n.parent
            hops++
        }
        return false
    }

    // ---------- Chatliste (Setup, Auto aus Liste) ----------

    private val listScroller by lazy { AndroidScrollDevice(svc, guard) }
    private var lastHow: ListHow? = null

    /**
     * Die senkrecht scrollbare Chatliste, gewaehlt aus dem Schnappschuss und den erkannten Chatzeilen ([ListLocator]).
     * Seitenwechsler und waagerechte Listen werden nie gewaehlt. Ohne scrollbaren Knoten bleibt die Geometrie der Zeilen fuer die Wischgeste.
     */
    private fun locateList(): ListChoice? {
        val snap = svc.snapshot() ?: return null
        val ch = ListLocator.choose(snap, listRows(snap).map { it.bounds })
        if (ch.how != lastHow) {
            lastHow = ch.how
            nav("Liste: gewaehlt per ${ch.how.text}" + (ch.bounds?.let { ", Bereich $it" } ?: "") + (ch.node?.let { ", Klasse ${it.className}, id=${it.viewId ?: "-"}" } ?: "") + ".")
            ch.skipped.forEach { nav("Liste: ausgeschlossen $it") }
        }
        return ch
    }

    /** Alle scrollbaren Knoten mit Klasse, ID, Bounds, Aktionen und Elternkette ins Log (bei Misserfolg). */
    private fun logScrollables(why: String) {
        val snap = svc.snapshot()
        nav("Liste: $why. Scrollbare Knoten im Baum:")
        val lines = snap?.let { ListLocator.describeScrollables(it) }.orEmpty()
        if (lines.isEmpty()) nav("Liste: keine scrollbaren Knoten im Baum (Baum ${if (snap == null) "nicht lesbar" else "ohne scrollbare Knoten"}).")
        lines.take(12).forEach { nav(it) }
    }

    private fun liveFor(n: UiNode): AccessibilityNodeInfo? {
        val root = svc.liveRoot() ?: return null
        return svc.findAllLive(root) { x -> x.className?.toString() == n.className && x.viewIdResourceName == n.viewId && svc.boundsOf(x) == n.bounds }.firstOrNull()
    }

    /**
     * Knotenaktion auf dem gewaehlten Listenknoten. Nur senkrecht: ACTION_SCROLL_DOWN/UP; allgemein FORWARD/BACKWARD nur, wenn [ListChoice.allowGeneric]
     * (naechster scrollbarer Vorfahr der Zeilen, nie ein Seitenwechsler). END_SIGNAL: Android meldet, dass es nicht weiter geht.
     */
    private fun listAction(ch: ListChoice, forward: Boolean): ScrollTry {
        val node = ch.node ?: return ScrollTry.NO_WAY
        val live = liveFor(node) ?: return ScrollTry.NO_WAY
        val ids = live.actionList.map { it.id }.toSet()
        val a = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id to AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
        val primary = if (forward) a.first else a.second
        val opposite = if (forward) a.second else a.first
        val generic = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val genericOpp = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val act = when {
            primary in ids -> primary
            ch.allowGeneric && generic in ids -> generic
            else -> null
        }
        if (act == null) {
            val atEnd = opposite in ids || (ch.allowGeneric && genericOpp in ids)
            return if (atEnd) ScrollTry.END_SIGNAL else ScrollTry.NO_WAY
        }
        return if (live.performAction(act)) ScrollTry.DONE else ScrollTry.END_SIGNAL
    }

    /** Ein Scrollschritt aufwaerts oder abwaerts in der Chatliste; bei NO_WAY nach unten zusaetzlich die Wischgeste im Bereich der Zeilen. */
    private suspend fun listStep(forward: Boolean): ScrollTry {
        val ch = locateList()
        if (ch == null || ch.how == ListHow.NONE) { logScrollables("keine Liste und keine Zeilen erkannt"); return ScrollTry.NO_WAY }
        val r = listAction(ch, forward)
        if (r != ScrollTry.NO_WAY || !forward) {
            if (r == ScrollTry.NO_WAY) logScrollables("Knotenaktion nicht moeglich (${ch.how.text})")
            return r
        }
        val b = ch.bounds ?: return ScrollTry.NO_WAY
        return if (swipeIn(b, ch.how.text)) ScrollTry.DONE else { logScrollables("weder Knotenaktion noch Wischgeste moeglich"); ScrollTry.NO_WAY }
    }

    /** Wischgeste im Sicherheitsfenster; Verweigerungsgrund ins Log. */
    private suspend fun swipeIn(list: Bounds, via: String): Boolean {
        val ins = svc.screenInsets()
        val (wt, wb) = SwipeSafety.window(list, ins)
        val dist = ((wb - wt) * 0.6).toInt()
        if (dist <= 100) {
            nav("Wischgeste verweigert: Sicherheitsfenster $wt..$wb zu klein (Bereich $list, Bildschirm ${ins.width}x${ins.height}, Quelle: $via).")
            return false
        }
        val ok = listScroller.swipe(list, older = false, distancePx = dist, durationMs = 600, holdMs = 150)
        if (!ok) nav("Wischgeste verweigert oder fehlgeschlagen: ${guard.describe()}, Fenster $wt..$wb, Bereich $list (Quelle: $via).")
        else delay(700)
        return ok
    }

    /** Scrollt nur senkrecht (ACTION_SCROLL_DOWN / ACTION_SCROLL_UP, bzw. FORWARD/BACKWARD am naechsten Vorfahr der Zeilen), nie waagerecht. */
    private suspend fun scrollMainList(forward: Boolean): Boolean = listStep(forward) == ScrollTry.DONE

    /** Bringt WhatsApp zur Chatliste, siehe [ChatListEnsurer]. */
    suspend fun ensureChatList(log: (String) -> Unit) {
        val device = object : ListDevice {
            override suspend fun ensureForeground() = guard.ensure(true, "Chatliste")
            override fun snapshot(): UiNode? = svc.snapshot()
            override fun imeVisible(): Boolean = svc.imeTop() != null
            override fun isForeground(): Boolean = guard.isForeground()
            override fun describe(): String = guard.describe()
            override fun back(): Boolean = guardedBack()
            override suspend fun pause(ms: Long) = delay(ms)
            override fun dumpTree(): String? = runCatching { diagDump() }.getOrNull()
            override fun nav(msg: String) = this@WhatsAppNavigator.nav(msg)
            override fun openChatsTab(): Boolean = clickChatsTab()
        }
        ChatListEnsurer(device, profile).run(log)
    }

    /** Liest die Chatliste von oben her, scrollt bis genug Eintraege da sind (mindestens [want]) oder die Liste endet. */
    suspend fun readChatList(want: Int, log: (String) -> Unit): List<ChatListEntry> {
        ensureChatList(log)
        // Der Baum der Chatliste wird einmal je Lesevorgang automatisch gespeichert (maskiert); im Tab Debug exportierbar.
        val dumped = runCatching { diagDump() }.getOrNull()
        nav("Chatliste erkannt, Debug-Baum " + (dumped?.let { "gespeichert: $it" } ?: "nicht gespeichert"))
        var scrolls = 0
        while (scrollMainList(false) && scrolls++ < 30) { delay(250) }
        delay(500)
        val out = ArrayList<ChatListEntry>()
        val seen = HashSet<String>()
        var emptyRounds = 0
        for (page in 0 until 40) {
            coroutineContext.ensureActive()
            guard.ensure(true, "Chatliste lesen")
            val snap = svc.snapshot() ?: break
            var added = 0
            for (r in listRows(snap)) {
                if (r.diag.isNotEmpty() && seen.add("diag:" + NameMatcher.normalize(r.entry.title))) {
                    nav("Kurzer Name in der Chatliste (${r.entry.title.length} Zeichen): ${r.diag}")
                }
                if (seen.add(NameMatcher.normalize(r.entry.title))) {
                    out.add(r.entry.copy(order = out.size))
                    added++
                }
            }
            nav("Chatliste: Seite $page, $added neue Zeilen, gesamt ${out.size}")
            if (out.size >= want) break
            emptyRounds = if (added == 0) emptyRounds + 1 else 0
            if (emptyRounds >= 2) break
            if (!scrollMainList(true)) break
            delay(700)
        }
        log("Chatliste gelesen: ${out.size} Eintraege.")
        return out
    }

    /**
     * Checkup: liest nur die Chatliste (Name, Vorschau, Zeit, Ungelesen-Hinweis, Gruppen-Heuristik), oeffnet keinen Chat, bleibt im Tab Chats.
     * Scrollt abwaerts per Wischgeste im Sicherheitsfenster ([SwipeSafety], rein senkrecht, Fensterpruefung nach jedem Wischer) und
     * faellt bei Verweigerung oder erkannter Luecke auf die senkrechte Knotenaktion ACTION_SCROLL_DOWN zurueck.
     */
    suspend fun checkupScan(limit: Int, sink: (String) -> Unit): CheckupScan {
        val shorts = HashSet<String>()
        val dev = object : CheckupDevice {
            private val scroller = AndroidScrollDevice(svc, guard)

            override suspend fun prepare() {
                ensureChatList(sink)
                val dumped = runCatching { diagDump() }.getOrNull()
                nav("Checkup: Chatliste erkannt, Debug-Baum " + (dumped?.let { "gespeichert: $it" } ?: "nicht gespeichert"))
                var scrolls = 0
                while (scrollMainList(false) && scrolls++ < 30) { delay(250) }
                delay(500)
            }

            override suspend fun readVisible(): List<ChatListEntry> {
                guard.ensure(true, "Checkup")
                var snap = svc.snapshot()
                val st = classifyList(snap, svc.imeTop() != null)
                if (st != ScreenState.LIST) {
                    nav("Checkup: Ansicht ist $st statt Liste, stelle die Chatliste wieder her.")
                    ensureChatList(sink)
                    snap = svc.snapshot()
                }
                val rows = snap?.let { listRows(it) }.orEmpty()
                for (r in rows) {
                    if (r.diag.isNotEmpty() && shorts.add(NameMatcher.normalize(r.entry.title))) {
                        nav("Kurzer Name in der Chatliste (${r.entry.title.length} Zeichen): ${r.diag}")
                    }
                }
                return rows.map { it.entry }
            }

            override suspend fun scrollForward(precise: Boolean): Boolean = scrollStep(precise) == ScrollTry.DONE

            override suspend fun scrollStep(precise: Boolean): ScrollTry {
                val ch = locateList()
                if (ch == null || ch.how == ListHow.NONE) { logScrollables("Checkup: keine Liste und keine Zeilen erkannt"); return ScrollTry.NO_WAY }
                if (!precise) {
                    val b = ch.bounds
                    if (b != null && swipeIn(b, ch.how.text)) return ScrollTry.DONE
                    nav("Checkup: Wischgeste nicht moeglich oder verweigert, nutze die Knotenaktion.")
                }
                val r = listAction(ch, true)
                delay(700)
                if (r == ScrollTry.NO_WAY) logScrollables("Checkup: keine Knotenaktion und keine Wischgeste moeglich (${ch.how.text})")
                return r
            }

            override suspend fun scrollBackward(): Boolean {
                val ok = scrollMainList(false)
                delay(700)
                return ok
            }

            override suspend fun chatsTabOk(): Boolean {
                val tabs = svc.snapshot()?.let { TabBar.read(it, profile) } ?: return true
                if (!tabs.found || tabs.chatsSelected) return true
                nav("TAB: Anderer Tab erkannt (${tabs.selectedOther ?: "unbekannt"} gewaehlt) waehrend des Checkups, tippe den Tab Chats.")
                val ok = clickChatsTab()
                delay(900)
                val again = svc.snapshot()?.let { TabBar.read(it, profile) }
                nav("TAB: Tab Chats gewaehlt, Aktion angenommen=$ok, jetzt gewaehlt=${again?.chatsSelected}.")
                return again == null || !again.found || again.chatsSelected
            }

            override fun log(msg: String) {
                AppLog.i("CHECKUP: $msg")
                sink(msg)
            }
        }
        return CheckupScanner(dev).scan(limit)
    }

    /**
     * Rueckfall: oeffnet einen Chat aus der sichtbaren Chatliste (Zeile antippen), ohne Namenssuche. Kein langes Abscrollen:
     * erst die sichtbaren Zeilen, dann hoechstens [LIST_PAGES] Seiten abwaerts und, wenn die Liste weiter unten steht, hoechstens
     * [LIST_PAGES] Seiten aufwaerts, jeweils nur solange sich die Zeilen aendern, und nie laenger als [LIST_DEADLINE_MS]. Jeder Schritt steht im Log.
     * Der Namensabgleich ignoriert Umlaute und Gross/Klein ([NameMatcher.normalize]). Gleichnamige Zeilen: Fehler statt Raten. Prueft die Kopfzeile.
     */
    suspend fun openFromList(title: String, log: (String) -> Unit) {
        ensureChatList(log)
        val key = NameMatcher.normalize(title)
        val t0 = System.currentTimeMillis()
        suspend fun find(): ChatListParser.Row? {
            guard.ensure(true, "Chat aus der Liste")
            val snap = readTree("Chat aus der Liste") ?: return null
            val hits = listRows(snap).filter { NameMatcher.normalize(it.entry.title) == key }
            if (hits.map { it.bounds }.distinct().size > 1) {
                throw ChatNotFoundException("Mehrdeutig: ${hits.size} Zeilen der Chatliste heissen gleich (Name hat ${title.length} Zeichen). Aus Sicherheit wird keine gewaehlt.")
            }
            return hits.firstOrNull()
        }
        fun titlesOf(snap: UiNode?) = snap?.let { listRows(it).map { r -> NameMatcher.normalize(r.entry.title) } }.orEmpty()
        var row: ChatListParser.Row? = find()
        nav("Liste: sichtbare Zeilen geprueft, ${if (row != null) "Treffer" else "kein Treffer"} (+${System.currentTimeMillis() - t0} ms).")
        for (dir in listOf(true, false)) {
            if (row != null) break
            var pages = 0
            var last = titlesOf(svc.snapshot())
            while (row == null && pages < LIST_PAGES && System.currentTimeMillis() - t0 < LIST_DEADLINE_MS) {
                coroutineContext.ensureActive()
                if (!scrollMainList(dir)) { nav("Liste: Scrollen ${if (dir) "abwaerts" else "aufwaerts"} nicht moeglich oder Ende."); break }
                delay(600)
                pages++
                val now = titlesOf(svc.snapshot())
                nav("Liste: Seite $pages ${if (dir) "abwaerts" else "aufwaerts"}, ${now.size} Zeilen, ${if (now == last) "unveraendert" else "neu"} (+${System.currentTimeMillis() - t0} ms).")
                if (now == last) break
                last = now
                row = find()
            }
        }
        if (row == null) {
            throw ChatNotFoundException("Chat nicht gefunden: weder in der Suche noch in der sichtbaren Chatliste (hoechstens $LIST_PAGES Seiten, Name hat ${title.length} Zeichen). Evtl. archiviert, umbenannt oder die Liste hat sich verschoben.")
        }
        // Zeile in den sicheren Bereich bringen: nicht unter der Tab-Leiste oder in der Gestenzone, nicht unter der Statusleiste
        val ins = svc.screenInsets()
        var adj = 0
        while (!SwipeSafety.pointSafe(row!!.bounds.centerX, row.bounds.centerY, ins) && adj++ < 3) {
            nav("Liste: Zeile bei y=${row.bounds.centerY} liegt ausserhalb des sicheren Bereichs, scrolle (Versuch $adj).")
            if (row.bounds.centerY > ins.height / 2) scrollMainList(true) else scrollMainList(false)
            delay(700)
            row = find() ?: throw ChatNotFoundException("Chat in der Chatliste nach dem Scrollen nicht mehr gefunden.")
        }
        val r0 = row!!
        val safe = SwipeSafety.pointSafe(r0.bounds.centerX, r0.bounds.centerY, ins)
        val live = svc.findLive { n -> n.text?.toString()?.trim().equals(r0.entry.title, ignoreCase = true) && svc.boundsOf(n).t >= r0.bounds.t && svc.boundsOf(n).b <= r0.bounds.b }
        val screenH = svc.liveRoot()?.let { svc.boundsOf(it).b } ?: r0.bounds.b
        val clickAt = System.currentTimeMillis()
        val clicked = if (live != null) clickWithAncestors(live, screenH) else false
        nav("Liste: Zeile bei y=${r0.bounds.centerY} (sicher=$safe), ACTION_CLICK=$clicked, ${guard.describe()}")
        var state = if (clicked) awaitOpened(clickAt, 5_000) else ScreenState.OTHER
        if (state != ScreenState.CHAT) {
            // Kein zusaetzliches Antippen, wenn die Unterhaltung schon erreicht ist oder gerade aufgeht
            if (conversationSince(clickAt)) {
                nav("Liste: Aktivitaet ${guard.activity()} meldet die Unterhaltung, warte weiter statt zu tippen.")
                state = awaitOpened(clickAt, 4_000)
            }
        }
        if (state != ScreenState.CHAT) {
            val stNow = classifyList(svc.snapshot(), svc.imeTop() != null)
            if (stNow == ScreenState.LIST && guard.isForeground() && safe) {
                log("Zeile antippen (Geste), Klick hat die Unterhaltung nicht geoeffnet.")
                safeTap(r0.bounds.centerX, r0.bounds.centerY)
                state = awaitOpened(System.currentTimeMillis(), 4_000)
            } else {
                nav("Liste: kein Antippen (Zustand $stNow, vorn=${guard.isForeground()}, Zeile sicher=$safe).")
            }
        }
        if (state != ScreenState.CHAT) throw AgentException("Das Oeffnen der Zeile in der Chatliste fuehrte nicht zu einem Chat (Zustand $state, ${guard.describe()}).")
        val snap = svc.snapshot() ?: throw AgentException("Kein Accessibility-Baum nach dem Öffnen des Chats.")
        if (!headerMatches(snap, title)) {
            throw AgentException("Ein Chat wurde geöffnet, aber die Kopfzeile zeigt nicht den erwarteten Titel. Abbruch, nichts wird gelesen.")
        }
        log("Chat aus der Liste geöffnet, Kopfzeile stimmt.")
        delay(500)
    }

    /** true, wenn seit [since] ein Fensterwechsel zu einer Unterhaltungs-Aktivitaet gemeldet wurde. */
    private fun conversationSince(since: Long): Boolean {
        val act = guard.activity() ?: return false
        return act.contains("Conversation", ignoreCase = true) && guard.eventAgeMs() < System.currentTimeMillis() - since
    }

    /** Wartet auf einen offenen Chat (Nachrichtenfeld unten); prueft dabei den Vordergrund. Gibt den letzten Zustand zurueck. */
    private suspend fun awaitOpened(since: Long, timeoutMs: Long): ScreenState {
        val end = System.currentTimeMillis() + timeoutMs
        var last = ScreenState.OTHER
        while (System.currentTimeMillis() < end) {
            coroutineContext.ensureActive()
            delay(300)
            guard.ensure(true, "Chat oeffnen")
            last = classify(svc.snapshot())
            if (last == ScreenState.CHAT) {
                nav("Oeffnen: Unterhaltung erkannt nach ${System.currentTimeMillis() - since} ms, ${guard.describe()}")
                delay(400)
                return last
            }
        }
        return last
    }

    companion object {
        const val MAX_RESULT_SCROLLS = 3
        const val TREE_RETRIES = 3
        const val TREE_RETRY_WAIT_MS = 700L
        const val PHASE_OPEN_SEARCH_MS = 20_000L
        const val LIST_PAGES = 3
        const val LIST_DEADLINE_MS = 25_000L
    }
}
