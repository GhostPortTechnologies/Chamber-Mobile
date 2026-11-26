package com.ghostporttechnologies.chamber.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ghostporttechnologies.chamber.push.Provider
import com.ghostporttechnologies.chamber.push.Push

/** Settings > Notifications: how this phone gets alerts, a choice when it has both ways, and a test (T-0388). */
@Composable
fun NotificationSettings(
    status: Push.Status,
    onChoose: (Provider) -> Unit,
    onTest: () -> Unit,
    onAndroidSettings: () -> Unit,
) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Notifications", style = MaterialTheme.typography.titleLarge)
        if (!status.allowed) {
            Text("Android has Chamber's notifications turned off, so nothing can show.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onAndroidSettings) { Text("Turn on in Android settings") }
        }
        val unified = "UnifiedPush" + (status.distributor?.let { " ($it)" } ?: "")
        when (status.provider) {
            null -> Text(
                "This phone can't get Chamber alerts yet. Install ntfy from F-Droid to get alerts without Google, " +
                    "then open Chamber again.",
            )
            else -> {
                val route = status.via.ifBlank { if (status.provider == Provider.Google) "Google (Firebase)" else unified }
                Text("Alerts come through $route.")
                Text(
                    "An alert carries nothing to read. It only wakes Chamber, which then asks the server what's waiting.",
                    color = dim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (status.google && status.distributor != null) {
            Column(Modifier.selectableGroup()) {
                Choice("Google (Firebase)", status.provider == Provider.Google) { onChoose(Provider.Google) }
                Choice("$unified, without Google", status.provider == Provider.UnifiedPush) { onChoose(Provider.UnifiedPush) }
            }
        } else if (status.google) {
            Text("To get alerts without Google, install ntfy from F-Droid.", color = dim, style = MaterialTheme.typography.bodyMedium)
        }
        status.problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (status.hosts.isNotEmpty()) {
            Text(
                "This workspace allows alerts only through: ${status.hosts.joinToString(", ")}. " +
                    "The workspace owner changes this in Chamber's Settings, Workspace.",
                color = dim,
            )
        }
        when (status.provider) {
            Provider.UnifiedPush -> {
                OutlinedButton(onClick = onTest, enabled = !status.testing) {
                    Text(if (status.testing) "Sending…" else "Send a test notification")
                }
                status.tested?.let { Text(it, color = dim) }
            }
            Provider.Google -> Text(
                "To test Google alerts, use Send a test in Chamber's Settings on the web.",
                color = dim,
                style = MaterialTheme.typography.bodyMedium,
            )
            null -> Unit
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label)
    }
}
