package com.ghostporttechnologies.chamber.ui

import com.ghostporttechnologies.chamber.push.AskRules
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import com.ghostporttechnologies.chamber.data.TicketQueue
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ghostporttechnologies.chamber.TicketView
import com.ghostporttechnologies.chamber.data.Ask
import com.ghostporttechnologies.chamber.data.Ticket
import java.text.DateFormat
import java.util.Date

enum class Tab(val label: String) { Chat("Chat"), Tickets("Tickets"), Questions("Questions") }

@Composable
fun BottomTabs(current: Tab, waiting: Int, onTab: (Tab) -> Unit) {
    NavigationBar {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == current,
                onClick = { onTab(tab) },
                icon = {},
                label = { Text(if (tab == Tab.Questions && waiting > 0) "${tab.label} ($waiting)" else tab.label) },
            )
        }
    }
}

@Composable
fun Notice(notice: String?) {
    if (notice != null) Text(notice, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

private fun statusLabel(s: String) = when (s) {
    "proposed" -> "Waiting for approval"
    "approved" -> "Approved"
    "in_progress" -> "In progress"
    "done" -> "Done"
    "rejected" -> "Not going ahead"
    else -> s
}

/** A list to read and open. There are deliberately no approve buttons here: decisions happen inside a ticket. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketsScreen(
    tickets: List<Ticket>, error: String?, notice: String?, showDone: Boolean, onShowDone: (Boolean) -> Unit,
    bottom: @Composable () -> Unit, onOpen: (String) -> Unit,
) {
    val queue = remember(tickets) { TicketQueue.arrange(tickets) }
    val now = System.currentTimeMillis()
    Scaffold(topBar = { TopAppBar(title = { Text("Tickets") }) }, bottomBar = bottom) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { ErrorLine(error); Notice(notice) }
            // T-0318: decisions first, in the order that unblocks the most; approving happens inside the ticket, one at a time.
            section("decide", "Needs your decision", queue.decide, now, onOpen,
                hint = "An agent waiting to start comes first, then priority, then what has waited longest. Open one to approve it or send it back.",
                empty = "Nothing waits for your approval.")
            section("todo", "Your to-dos", queue.todo, now, onOpen, hint = "Approved steps only a person can do.")
            section("agents", "Agents' work", queue.agents, now, onOpen)
            if (showDone) section("closed", "Done and not going ahead", queue.closed, now, onOpen)
            item {
                TextButton(onClick = { onShowDone(!showDone) }, modifier = Modifier.padding(8.dp)) { Text(if (showDone) "Hide done and rejected" else "Show done and rejected") }
            }
        }
    }
}

private fun LazyListScope.section(
    key: String, title: String, group: List<Ticket>, now: Long, onOpen: (String) -> Unit, hint: String? = null, empty: String? = null,
) {
    if (group.isEmpty() && empty == null) return
    item(key = "h-$key") {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
            Text("$title · ${group.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (group.isEmpty()) Text(empty.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            else if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    items(group, key = { "$key-${it.id}" }) { t ->
        Column(Modifier.fillMaxWidth().clickable { onOpen(t.id) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t.id, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                val urgent = TicketQueue.rank(t.priority) <= 1
                Text(t.priority, style = MaterialTheme.typography.labelMedium,
                    color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                TicketQueue.waited(t.proposedAt, now)?.takeIf { t.status == "proposed" }?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(AskRules.visible(t.title, 200))
            TicketQueue.summary(t.body).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Text(listOfNotNull(
                when (t.doneBy) { "human" -> "for you to do"; "agent" -> "an agent does it"; else -> null },
                t.assignee?.let { "worked by $it" },
                if (t.status == "proposed") "from ${t.proposedBy}" else statusLabel(t.status),
            ).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketScreen(view: TicketView, busy: Boolean, error: String?, notice: String?, onBack: () -> Unit, onApprove: () -> Unit, onReject: (String) -> Unit) {
    val t = view.ticket
    var rejecting by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    Scaffold(topBar = { TopAppBar(title = { Text(t?.id ?: "Ticket") }, navigationIcon = { TextButton(onClick = onBack) { Text("‹ Tickets") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (t == null) { ErrorLine(error ?: "This ticket isn't loaded yet."); return@Column }
            Text(t.title, style = MaterialTheme.typography.titleLarge)
            Text("${statusLabel(t.status)} · proposed by ${t.proposedBy}" + (t.approvedBy?.let { " · approved by $it" } ?: ""),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(t.body)
            t.rejectReason?.let { Text("Not going ahead: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

            val change = view.change
            if (!view.changeChecked) Text("Loading the change…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
            if (change != null) {
                Spacer(Modifier.height(16.dp))
                Text("The change (version ${change.meta.version})", style = MaterialTheme.typography.titleMedium)
                change.meta.plain?.let { p ->
                    listOf("What it does" to p.what, "How to try it" to p.tryIt, "What could go wrong" to p.risk, "How to undo it" to p.undo)
                        .filter { it.second.isNotBlank() }
                        .forEach { (label, text) -> Text(label, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)); Text(text) }
                }
                Text("${change.meta.files.size} file(s), +${change.meta.added} −${change.meta.removed}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(change.diff.take(20_000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp))
                }
            }

            if (t.updates.isNotEmpty()) {
                Text("Notes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                t.updates.takeLast(10).forEach { Text("${it.by}: ${it.note}", modifier = Modifier.padding(top = 4.dp)) }
            }

            ErrorLine(error)
            Notice(notice)
            if (t.status == "proposed") {
                Spacer(Modifier.height(16.dp))
                if (!rejecting) {
                    Button(onClick = onApprove, enabled = !busy && view.changeChecked, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Approve") }
                    OutlinedButton(onClick = { rejecting = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Reject…") }
                    Text("Approving asks for your fingerprint or phone PIN. Chamber's own rules on who may approve still apply.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                } else {
                    OutlinedTextField(reason, { reason = it }, label = { Text("Why not? (required)") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { onReject(reason.trim()); rejecting = false }, enabled = !busy && reason.isNotBlank()) { Text("Reject") }
                        TextButton(onClick = { rejecting = false }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionsScreen(asks: List<Ask>, busy: Boolean, error: String?, notice: String?, bottom: @Composable () -> Unit, onAllow: (Ask) -> Unit, onDeny: (Ask) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Agents waiting for you") }) }, bottomBar = bottom) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { ErrorLine(error); Notice(notice) }
            if (asks.isEmpty()) item { Text("No agent is waiting for an answer.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            items(asks, key = { it.id }) { a ->
                val command = a.tool == "Bash"
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("${AskRules.visible(a.agent, 40)} asks: " + if (command) "may I run this command?" else "may I do this?", fontWeight = FontWeight.Bold)
                    Text("${a.id}${a.ticket?.let { " · $it" } ?: ""} · no answer by ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(a.expiresAt))} means no",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // The whole request, wrapped, never cut: the person reads everything they allow.
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(AskRules.visible(AskRules.command(a.summary), Int.MAX_VALUE), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAllow(a) }, enabled = !busy) { Text("Allow once") }
                        OutlinedButton(onClick = { onDeny(a) }, enabled = !busy) { Text("Deny") }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
fun RecheckDialog(busy: Boolean, error: String?, onPassword: (String) -> Unit, onCancel: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Confirm it's you") },
        text = {
            Column {
                Text(error ?: "Chamber wants your password again before this.")
                OutlinedTextField(password, { password = it }, singleLine = true, label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { Button(onClick = { onPassword(password); password = "" }, enabled = !busy && password.isNotEmpty()) { Text("Confirm") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
