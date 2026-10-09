package app.chatlens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.chatlens.memory.Disc
import app.chatlens.memory.DiscProfile

object DiscColors {
    val D = GlassColors.Bad
    val I = GlassColors.Warn
    val S = GlassColors.Ok
    val C = GlassColors.Accent2Text
}

/**
 * Compact DISC bar: four segments by percentage, with labels and confidence. If there is too little data, only the hint.
 * Always marked as a cautious assessment; [showReason] shows the reasoning and the note "keine Diagnose".
 */
@Composable
fun DiscBar(p: DiscProfile?, modifier: Modifier = Modifier, showReason: Boolean = false, who: String = "") {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (p == null || p.insufficient) {
            Text("DISC" + (if (who.isNotEmpty()) " ($who)" else "") + ": zu wenig Daten", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
            return@Column
        }
        Row(Modifier.fillMaxWidth().height(8.dp).semantics { contentDescription = p.line() }, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(p.d to DiscColors.D, p.i to DiscColors.I, p.s to DiscColors.S, p.c to DiscColors.C).forEach { (v, c) ->
                if (v > 0) Box(Modifier.weight(v.toFloat()).height(8.dp).background(c, RoundedCornerShape(3.dp)))
            }
        }
        Text(
            "DISC" + (if (who.isNotEmpty()) " ($who)" else "") + ": D ${p.d}  I ${p.i}  S ${p.s}  C ${p.c} Prozent, Konfidenz ${p.confidence.label}, vorsichtige Einschätzung",
            style = MaterialTheme.typography.bodySmall, color = GlassColors.Text,
        )
        if (showReason) {
            if (p.reason.isNotBlank()) Text("Begründung: " + p.reason, style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
            Text(Disc.DISCLAIMER + " Basis: ${p.basis} Nachrichten.", style = MaterialTheme.typography.bodySmall, color = GlassColors.TextDim)
        }
    }
}

const val MEMORY_PRIVACY_NOTE =
    "Datenschutz: Steckbrief, DISC-Einschätzung und Ich-Profil liegen nur auf diesem Gerät, verschlüsselt. Beim lokalen Modell verlässt nichts das Gerät. " +
        "Mit einem API-Modell gehen die Chattexte, das bisherige Gedächtnis und der kurze Ich-Block an den eingetragenen Server."
