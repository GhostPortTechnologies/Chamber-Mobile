package com.ghostporttechnologies.chamber.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ghostporttechnologies.chamber.drive.Detection
import com.ghostporttechnologies.chamber.drive.DriveState
import java.text.DateFormat
import java.util.Date

/**
 * Shown instead of everything else while driving (T-0321): nothing to read, nothing to approve, one way out for
 * passengers.
 */
@Composable
fun DrivingHold(onNotDriving: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Driving mode", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Chamber is holding everything until you stop.", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(64.dp))
        OutlinedButton(onClick = onNotDriving) { Text("I'm not driving") }
        Spacer(Modifier.height(8.dp))
        Text(
            "For passengers. Asks for your fingerprint or PIN, then pauses driving mode for this trip only.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The reminder while a passenger has paused driving mode. */
@Composable
fun DrivingPausedBanner() {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Text(
            "Driving mode is paused for this trip. Don't use Chamber while you drive.",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** No signal, or Chamber is restarting: say so, instead of letting old messages look current. */
@Composable
fun OfflineBanner() {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Text(
            "No connection to Chamber. Showing what was loaded last; it reconnects by itself.",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

private fun day(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

/** The only place for switches (CHAMBER-MOBILE-SOP). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    me: String,
    drive: DriveState,
    detection: Detection,
    onBack: () -> Unit,
    onAllowDetection: () -> Unit,
    onDrivingNow: () -> Unit,
    onTurnOff: () -> Unit,
    onTurnOn: () -> Unit,
    onSignOut: () -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Driving mode", style = MaterialTheme.typography.titleLarge)
            if (drive.off) {
                Text(
                    "Off. You turned it off on ${day(drive.offAt)} and accepted the risk. Chamber may ask you to read " +
                        "and approve things while you drive.",
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onTurnOn) { Text("Turn driving mode back on") }
            } else {
                Text(
                    "On. While you drive, Chamber shows nothing to read or tap and holds its notifications. When you stop, " +
                        "one notification says what waited. Agents' questions that time out meanwhile count as no.",
                )
                when (detection) {
                    Detection.On -> Text("Notices driving by itself.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Detection.NeedsPermission -> {
                        Text(
                            "To notice when you're driving, Chamber needs Android's activity detection (\"Physical activity\"). " +
                                "Chamber uses it only for driving mode and doesn't send it anywhere.",
                        )
                        Button(onClick = onAllowDetection) { Text("Allow driving detection") }
                    }
                    Detection.Unavailable -> Text(
                        "This phone can't notice driving for Chamber (it needs Google Play services). Use \"I'm driving now\".",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onDrivingNow) { Text("I'm driving now") }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                TextButton(onClick = onTurnOff, enabled = !drive.inVehicle) { Text("Turn off driving mode…") }
                if (drive.inVehicle) {
                    Text(
                        "The phone says you're in a vehicle. Stop first to change this.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Text("Account", style = MaterialTheme.typography.titleLarge)
            Text("Signed in as $me", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onSignOut) { Text("Sign out") }
        }
    }
}

/** Turning driving mode off completely: plain warning, a tick, then fingerprint or PIN (asked by the caller). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrivingOffScreen(inVehicle: Boolean, onBack: () -> Unit, onConfirm: () -> Unit) {
    var accepted by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Turn off driving mode") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "With driving mode off, Chamber may ask you to read and approve things while you're driving. Using your " +
                    "phone while driving is dangerous and may be illegal where you are. You take full responsibility.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Riding as a passenger? You don't need this. Tap \"I'm not driving\" when driving mode shows, and it pauses " +
                    "for that trip only.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().clickable { accepted = !accepted }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = accepted, onCheckedChange = { accepted = it })
                Text("I understand and accept the risk")
            }
            Button(
                onClick = onConfirm,
                enabled = accepted && !inVehicle,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Turn off driving mode") }
            if (inVehicle) Text("The phone says you're in a vehicle. Stop first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
