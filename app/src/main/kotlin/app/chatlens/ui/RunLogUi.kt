package app.chatlens.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import app.chatlens.data.SettingsRepo
import app.chatlens.service.RunLogStore

/** Buttons for the log of the last (or current) run as Markdown: share it, and save it under Downloads/ChatLens. */
@Composable
fun RunLogButtons() {
    val ctx = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            runCatching { RunLogStore.share(ctx, SettingsRepo(ctx).load()) }
                .onFailure { Toast.makeText(ctx, "Teilen nicht moeglich: ${it.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
        }) { Text("Log teilen (.md)") }
        OutlinedButton(onClick = {
            val where = RunLogStore.saveToDownloads(ctx, SettingsRepo(ctx).load())
            Toast.makeText(ctx, if (where != null) "Gespeichert: $where" else "Speichern in Downloads fehlgeschlagen", Toast.LENGTH_LONG).show()
        }) { Text("In Downloads") }
    }
}
