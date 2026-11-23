package com.ghostporttechnologies.chamber.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ghostporttechnologies.chamber.ChatState
import com.ghostporttechnologies.chamber.data.Message
import com.ghostporttechnologies.chamber.data.Room
import java.text.DateFormat
import java.util.Date

@Composable
fun ErrorLine(error: String?) {
    if (error != null) {
        Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun Brand() {
    Text(
        "CHAMBER",
        color = MaterialTheme.colorScheme.primary,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
fun SignInScreen(codeHint: String?, busy: Boolean, error: String?, onPassword: (String, String) -> Unit, onCode: (String) -> Unit) {
    var identity by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") } // never saved to disk, not even across rotation
    var code by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp).imePadding(),
        verticalArrangement = Arrangement.Center,
    ) {
        Brand()
        Spacer(Modifier.height(24.dp))
        if (codeHint == null) {
            OutlinedTextField(
                value = identity, onValueChange = { identity = it }, singleLine = true,
                label = { Text("Email or username") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it }, singleLine = true,
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (!busy) onPassword(identity, password) }),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(codeHint, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = code, onValueChange = { code = it }, singleLine = true,
                label = { Text("Code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (!busy) onCode(code) }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ErrorLine(error)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { if (codeHint == null) onPassword(identity, password) else onCode(code) },
            enabled = !busy && (if (codeHint == null) identity.isNotBlank() && password.isNotEmpty() else code.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(if (codeHint == null) "Sign in" else "Continue")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsScreen(me: String, rooms: Map<String, Room>, error: String?, onOpen: (String) -> Unit, onSignOut: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Brand() }, actions = { TextButton(onClick = onSignOut) { Text("Sign out") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text("Signed in as $me", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                ErrorLine(error)
            }
            val sorted = rooms.entries.sortedWith(compareBy({ it.value.dm }, { it.key }))
            items(sorted, key = { it.key }) { (key, room) ->
                Column(
                    Modifier.fillMaxWidth().clickable { onOpen(key) }.padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(if (room.dm) room.name.ifBlank { key } else "#" + room.name.ifBlank { key }, style = MaterialTheme.typography.titleMedium)
                    if (room.topic.isNotBlank()) Text(room.topic, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(room: String, title: String, chat: ChatState, error: String?, onBack: () -> Unit, onSend: (String) -> Unit) {
    var draft by rememberSaveable(room) { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(chat.messages.size) { if (chat.messages.isNotEmpty()) list.animateScrollToItem(chat.messages.size - 1) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onClick = onBack) { Text("‹ Rooms") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (chat.loading && chat.messages.isEmpty()) {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
                items(chat.messages, key = { it.id }) { MessageRow(it, chat.roles[it.username]) }
            }
            ErrorLine(error)
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    placeholder = { Text("Message $title") },
                    modifier = Modifier.weight(1f), maxLines = 5,
                )
                Button(
                    onClick = { onSend(draft); draft = "" },
                    enabled = draft.isNotBlank() && !chat.sending,
                    modifier = Modifier.padding(start = 8.dp).height(56.dp),
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun MessageRow(message: Message, role: String?) {
    val agent = role == "ai"
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                message.username,
                fontWeight = FontWeight.Bold,
                color = if (agent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
            )
            if (agent) Text("  agent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "  " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.ts)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (message.replyTo != null) Text("↩ reply in a thread", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (message.deleted) "(removed)" else message.text,
            modifier = Modifier.widthIn(max = 640.dp),
            color = if (message.deleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
        )
        message.attachments.forEach { Text("📎 ${it.name}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (message.edited) Text("edited", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
