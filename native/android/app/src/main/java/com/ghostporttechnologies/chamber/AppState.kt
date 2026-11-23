package com.ghostporttechnologies.chamber

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ghostporttechnologies.chamber.data.ChamberApi
import com.ghostporttechnologies.chamber.data.ChamberException
import com.ghostporttechnologies.chamber.data.LoginReply
import com.ghostporttechnologies.chamber.data.Message
import com.ghostporttechnologies.chamber.data.Room
import com.ghostporttechnologies.chamber.data.TokenVault
import com.ghostporttechnologies.chamber.data.changes
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Starting : Screen
    data class SignIn(val mfaToken: String? = null, val hint: String? = null) : Screen
    data object Rooms : Screen
    data class Chat(val room: String) : Screen
}

data class ChatState(
    val messages: List<Message> = emptyList(),
    val roles: Map<String, String> = emptyMap(),
    val loading: Boolean = true,
    val sending: Boolean = false,
)

data class UiState(
    val screen: Screen = Screen.Starting,
    val me: String = "",
    val rooms: Map<String, Room> = emptyMap(),
    val chat: ChatState = ChatState(),
    val busy: Boolean = false,
    val error: String? = null,
)

class AppState(app: Application) : AndroidViewModel(app) {

    private val vault = TokenVault(app)
    private val api = ChamberApi { vault.read() }
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var live: Job? = null

    init {
        if (vault.read() == null) show(Screen.SignIn()) else start()
    }

    private fun show(screen: Screen) = _ui.update { it.copy(screen = screen, error = null, busy = false) }

    private fun fail(e: Throwable) {
        if (e is ChamberException && e.signedOut) return signedOut()
        _ui.update { it.copy(busy = false, error = (e as? ChamberException)?.message ?: "Something went wrong. Try again.") }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    // ── Signing in ──────────────────────────────────────────────────────────────────────────────

    fun signIn(identity: String, password: String) = work {
        finishOrAskForCode(api.login(identity, password))
    }

    fun enterCode(code: String) = work {
        val mfa = (_ui.value.screen as? Screen.SignIn)?.mfaToken ?: return@work show(Screen.SignIn())
        finishOrAskForCode(api.loginCode(mfa, code))
    }

    private fun finishOrAskForCode(reply: LoginReply) {
        when {
            reply.token != null -> { vault.write(reply.token); start() }
            reply.mfa && reply.mfaToken != null && reply.totp ->
                show(Screen.SignIn(reply.mfaToken, reply.message ?: "Enter the 6-digit code from your authenticator app, or a recovery code."))
            reply.mfa -> _ui.update {
                it.copy(busy = false, error = "This account signs in with a passkey only. Passkeys in the app are coming; for now add an authenticator code to your account in Chamber on the web.")
            }
            else -> _ui.update { it.copy(busy = false, error = "Chamber didn't sign you in. Try again.") }
        }
    }

    fun signOut() = work {
        api.logout()
        signedOut()
    }

    private fun signedOut() {
        live?.cancel()
        vault.clear()
        _ui.value = UiState(screen = Screen.SignIn())
    }

    // ── Rooms and chat ──────────────────────────────────────────────────────────────────────────

    private fun start() = work {
        val me = api.me()
        val rooms = api.rooms()
        _ui.update { it.copy(me = me.username, rooms = rooms, screen = Screen.Rooms, busy = false, error = null) }
        listen()
    }

    private fun listen() {
        live?.cancel()
        live = viewModelScope.launch {
            try {
                api.changes().collect { change ->
                    when (change.kind) {
                        "rooms" -> refreshRooms()
                        "messages" -> (_ui.value.screen as? Screen.Chat)?.let { if (change.room == null || change.room == it.room) refreshChat(it.room) }
                    }
                }
            } catch (e: ChamberException) {
                fail(e)
            }
        }
    }

    private suspend fun refreshRooms() {
        runCatching { api.rooms() }.onSuccess { rooms -> _ui.update { it.copy(rooms = rooms) } }
    }

    fun openRoom(room: String) {
        _ui.update { it.copy(screen = Screen.Chat(room), chat = ChatState(), error = null) }
        work { refreshChat(room) }
    }

    fun back() {
        if (_ui.value.screen is Screen.Chat) show(Screen.Rooms)
    }

    private suspend fun refreshChat(room: String) {
        val current = _ui.value.chat
        val since = current.messages.maxOfOrNull { it.ts }
        val reply = api.messages(room, since = since)
        if ((_ui.value.screen as? Screen.Chat)?.room != room) return
        val merged = (current.messages.associateBy { it.id } + reply.messages.associateBy { it.id }).values
            .filter { it.type != "status" || it.text.isNotBlank() }
            .sortedBy { it.ts }
        _ui.update {
            it.copy(chat = it.chat.copy(messages = merged, roles = it.chat.roles + reply.users.mapValues { u -> u.value.role }, loading = false))
        }
    }

    fun send(text: String) {
        val room = (_ui.value.screen as? Screen.Chat)?.room ?: return
        if (text.isBlank()) return
        _ui.update { it.copy(chat = it.chat.copy(sending = true)) }
        viewModelScope.launch {
            try {
                api.send(room, text)
                refreshChat(room)
            } catch (e: Throwable) {
                fail(e)
            } finally {
                _ui.update { it.copy(chat = it.chat.copy(sending = false)) }
            }
        }
    }

    private fun work(block: suspend () -> Unit) {
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                block()
                _ui.update { it.copy(busy = false) }
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }
}
