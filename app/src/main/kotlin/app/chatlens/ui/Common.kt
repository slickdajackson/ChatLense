package app.chatlens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions

@Composable
fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    // A4: the whole row is the switch (tap target; TalkBack reads the text and the state as one element)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun IntField(label: String, value: Int, onChange: (Int) -> Unit) {
    // N7: the field may be empty while typing; only the value 0 is emitted outward, and "0" is not forced
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) { if ((text.toIntOrNull() ?: 0) != value) text = value.toString() }
    OutlinedTextField(
        value = text,
        onValueChange = { s -> val f = s.filter { it.isDigit() }.take(7); text = f; onChange(f.toIntOrNull() ?: 0) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Privacy notice; in the Play flavor without API mode, the API paragraph is omitted (0.3.0). */
val PRIVACY_TEXT: String get() = privacyText(app.chatlens.data.BackendPolicy.apiAllowed)

fun privacyText(apiAllowed: Boolean): String =
    "Datenschutz in Kürze: ChatLens liest Chatinhalte Dritter. " +
        (if (apiAllowed) "Bei \"Nur Auslesen\" und \"Lokal\" bleiben die Inhalte auf diesem Gerät. " +
            "Bei \"API\" werden Text und optional Bilder an den eingetragenen Server gesendet. Das ist eine Übermittlung personenbezogener Daten und braucht eine Rechtsgrundlage. "
        else "Die Inhalte bleiben auf diesem Gerät und werden nicht an einen Server gesendet. ") +
        "Die Haushaltsausnahme der DSGVO gilt nur bei rein privater Nutzung, bei beruflicher oder gemischter Nutzung nicht. Bei Berufsgeheimnissen ist besondere Vorsicht nötig. " +
        "Das ist keine Rechtsberatung. Die WhatsApp-Bedingungen verbieten automatisierten Zugriff; eine Kontosperre ist möglich."
