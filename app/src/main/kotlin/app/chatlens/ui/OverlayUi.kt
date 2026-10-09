package app.chatlens.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chatlens.agent.AgentState
import app.chatlens.agent.AutoState
import app.chatlens.agent.ConfirmBroker
import app.chatlens.agent.PromptBookState
import app.chatlens.agent.PromptChoiceBroker
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class RingAction(val label: String) {
    ANALYSE("Analyse"), SUGGEST("Vorschlag"), ADVISE("Berater"), AUTO("Update"), SELF("Selbst"), SETTINGS("Optionen"), REMOVE("Entfernen"),
}

/** Seitenlaenge des Fensters: eingeklappt nur der Punkt, ausgeklappt der Ring. */
const val OVERLAY_DOT_DP = 60
const val OVERLAY_RING_DP = 248

/**
 * Schwebender Punkt mit Ring. Der Punkt bleibt beim Ausklappen in der Fenstermitte (das Fenster waechst um ihn herum,
 * siehe OverlayService). Ziehen verschiebt, Tippen klappt den Ring auf und zu.
 */
@Composable
fun OverlayDot(
    expanded: Boolean,
    onToggle: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onAction: (RingAction) -> Unit,
    onDragEnd: () -> Unit = {},
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val size = if (expanded) OVERLAY_RING_DP else OVERLAY_DOT_DP
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        val actions = RingAction.entries
        actions.forEachIndexed { i, a ->
            val p by animateFloatAsState(if (expanded) 1f else 0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "ring$i")
            val ang = -PI / 2 + i * 2 * PI / actions.size
            val r = 88f * p
            AnimatedVisibility(expanded, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                val danger = a == RingAction.REMOVE
                Box(
                    Modifier.offset(x = (cos(ang) * r).dp, y = (sin(ang) * r).dp).size(72.dp).scale(0.6f + 0.4f * p)
                        .background(GlassColors.PanelFill, CircleShape)
                        .border(
                            1.dp,
                            if (danger) Brush.verticalGradient(listOf(GlassColors.Danger, GlassColors.Danger.copy(alpha = 0.6f)))
                            else Brush.verticalGradient(listOf(Color(0xCCFFFFFF), Color(0x66FFFFFF))),
                            CircleShape,
                        )
                        .semantics { role = Role.Button; contentDescription = a.label }
                        .clickable(onClickLabel = a.label) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onAction(a) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        a.label, fontSize = 12.sp, color = if (danger) GlassColors.Danger else GlassColors.Text, textAlign = TextAlign.Center, lineHeight = 13.sp, maxLines = 2, softWrap = true,
                        fontWeight = if (danger) androidx.compose.ui.text.font.FontWeight.Bold else null, modifier = Modifier.padding(2.dp),
                    )
                }
            }
        }
        Box(
            Modifier.size(OVERLAY_DOT_DP.dp - 8.dp)
                .background(Brush.radialGradient(listOf(Color(0xFF1B2447), Color(0xFF0B1020))), CircleShape)
                .border(1.5.dp, Brush.linearGradient(listOf(GlassColors.Accent, GlassColors.Accent2)), CircleShape)
                .pointerInput(Unit) { detectTapGestures(onTap = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onToggle() }) }
                .pointerInput(Unit) { detectDragGestures(onDragEnd = { onDragEnd() }, onDragCancel = { onDragEnd() }) { change, drag -> change.consume(); onDrag(drag.x, drag.y) } }
                // TalkBack und Schalterzugriff: Zugaenglichkeitsaktionen statt reiner Zeigergesten (ab 0.3.0)
                .semantics {
                    role = Role.Button
                    contentDescription = "ChatLens schwebender Punkt, " + (if (expanded) "Ring ist offen" else "Ring ist geschlossen")
                    onClick(label = if (expanded) "Ring schließen" else "Ring öffnen") { onToggle(); true }
                    customActions = listOf(
                        CustomAccessibilityAction("Nach links") { onDrag(-200f, 0f); onDragEnd(); true },
                        CustomAccessibilityAction("Nach rechts") { onDrag(200f, 0f); onDragEnd(); true },
                        CustomAccessibilityAction("Nach oben") { onDrag(0f, -200f); onDragEnd(); true },
                        CustomAccessibilityAction("Nach unten") { onDrag(0f, 200f); onDragEnd(); true },
                    )
                },
            contentAlignment = Alignment.Center,
        ) { LogoMark(Modifier.size(36.dp)) }
    }
}

/**
 * Ergebnisfenster neben dem Punkt. Begrenzte Hoehe ([maxHeightDp]) mit eigener senkrechter Scrollflaeche: Kopf (Status, Fortschritt, Knoepfe
 * Abbrechen, Schliessen, Punkt entfernen) bleibt stehen, darunter scrollt alles andere (Promptwahl, Rueckfragen, lange Ergebnisse, Entwuerfe).
 * Das Ergebnis ist vollstaendig lesbar und kopierbar. So laesst sich alles Wesentliche im Overlay bedienen, ohne die App zu oeffnen.
 */
@Composable
fun OverlayPanel(
    onInsert: (String) -> Unit,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
    onCancel: () -> Unit = {},
    onRemoveDot: () -> Unit = {},
    maxHeightDp: Int = 480,
) {
    val st by AgentState.state.collectAsState()
    val ask by ConfirmBroker.pending.collectAsState()
    val choice by PromptChoiceBroker.pending.collectAsState()
    val book by PromptBookState.book.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    GlassCard(Modifier.fillMaxWidth().heightIn(max = maxHeightDp.dp), fill = GlassColors.PanelFill) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Fester Kopf
            RunStatus(st, AutoState.state.collectAsState().value, compact = true)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (st.running) OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
                OutlinedButton(onClick = onClose) { Text("Schließen") }
                OutlinedButton(onClick = onRemoveDot) { Text("Punkt entfernen", color = GlassColors.Danger) }
            }
            // Scrollflaeche
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Kompakte DISC-Leiste des Gegenuebers, wenn zu diesem Chat ein Gedaechtnis mit Einschaetzung vorliegt
                val discMem by androidx.compose.runtime.produceState<app.chatlens.memory.ChatMemory?>(null, st.resultChat, st.phase) {
                    value = if (st.resultChat.isBlank()) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { app.chatlens.data.MemoryRepo.get(ctx).loadByTitle(st.resultChat) }.getOrNull()
                    }
                }
                discMem?.disc?.let { DiscBar(it, who = "Gegenüber") }
                choice?.let { c ->
                    PromptChoiceCard(
                        chatTitle = c.chatTitle, messageCount = c.messageCount, book = book,
                        onChoice = { PromptChoiceBroker.answer(c.id, it) },
                        onSaveTemplate = { n, t -> PromptBookState.update(ctx) { b -> b.withSaved(n, t) } },
                        onDeleteSaved = { n -> PromptBookState.update(ctx) { b -> b.withoutSaved(n) } },
                        onDeleteRecent = { t -> PromptBookState.update(ctx) { b -> b.withoutRecent(t) } },
                        onOpenApp = onOpenApp,
                    )
                }
                ask?.let { q ->
                    Text(q.title, style = MaterialTheme.typography.titleMedium, color = GlassColors.Text)
                    Text(q.body, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { ConfirmBroker.answer(q.id, true) }) { Text("Ja") }
                        OutlinedButton(onClick = { ConfirmBroker.answer(q.id, false) }) { Text("Nein") }
                    }
                }
                if (st.error.isNotEmpty()) {
                    Text(st.error, color = GlassColors.Bad, style = MaterialTheme.typography.bodySmall)
                    // N4: ein Fehler im Overlay braucht einen Weg zur Loesung (Einstellungen und Berechtigungen liegen in der App)
                    OutlinedButton(onClick = onOpenApp) { Text("ChatLens öffnen") }
                }
                if (st.suggestions.isNotEmpty()) {
                    Text("Entwürfe (nichts wird gesendet)", style = MaterialTheme.typography.labelLarge, color = GlassColors.Accent)
                    st.suggestions.forEach { t ->
                        Text(t, style = MaterialTheme.typography.bodyMedium, color = GlassColors.Text)
                        OutlinedButton(onClick = { onInsert(t) }) { Text("Eintragen") }
                    }
                } else if (st.result.isNotEmpty() && !st.running) {
                    // Das ganze Ergebnis, auswaehlbar und kopierbar; scrollt mit der Flaeche
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(st.result, style = MaterialTheme.typography.bodySmall, color = GlassColors.Text)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { copyToClipboard(ctx, st.result) }) { Text("Kopieren") }
                        OutlinedButton(onClick = onOpenApp) { Text("In der App öffnen") }
                    }
                }
            }
        }
    }
}

/** Kopiert Text in die Zwischenablage (Ergebnis des Panels). */
fun copyToClipboard(ctx: android.content.Context, text: String) {
    runCatching {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("ChatLens", text))
        android.widget.Toast.makeText(ctx.applicationContext, "Kopiert.", android.widget.Toast.LENGTH_SHORT).show()
    }
}
