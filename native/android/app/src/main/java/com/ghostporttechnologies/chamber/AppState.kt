package com.ghostporttechnologies.chamber

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ghostporttechnologies.chamber.data.Ask
import com.ghostporttechnologies.chamber.data.ChamberApi
import com.ghostporttechnologies.chamber.data.DiffReply
import com.ghostporttechnologies.chamber.data.ChamberException
import com.ghostporttechnologies.chamber.data.LoginReply
import com.ghostporttechnologies.chamber.data.Message
import com.ghostporttechnologies.chamber.data.Room
import com.ghostporttechnologies.chamber.data.Ticket
import com.ghostporttechnologies.chamber.data.Chamber
import com.ghostporttechnologies.chamber.push.Push
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
    data class Locked(val then: Screen) : Screen
    data object Tickets : Screen
    data class TicketOpen(val id: String) : Screen
    data object Questions : Screen
}

data class ChatState(
    val messages: List<Message> = emptyList(),
    val roles: Map<String, String> = emptyMap(),
    val loading: Boolean = true,
    val sending: Boolean = false,
)

data class TicketView(
    val ticket: Ticket? = null,
    val change: DiffReply? = null,
    /** True once the change (or the knowledge that there is none) is on screen: approving needs it. */
    val changeChecked: Boolean = false,
)

data class UiState(
    val screen: Screen = Screen.Starting,
    val me: String = "",
    val rooms: Map<String, Room> = emptyMap(),
    val chat: ChatState = ChatState(),
    val tickets: List<Ticket> = emptyList(),
    val open: TicketView = TicketView(),
    val asks: List<Ask> = emptyList(),
    /** Chamber wants the password again before the last action (T-0241). */
    val recheck: Boolean = false,
    val notice: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

class AppState(app: Application) : AndroidViewModel(app) {

    private val vault = Chamber.vault(app)
    private val api = Chamber.api(app)
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var live: Job? = null
    private var presence: Job? = null
    private var afterRecheck: (suspend () -> Unit)? = null
    @Volatile private var touched = false
    private var questionsNext = false

    init {
        if (vault.read() == null) show(Screen.SignIn()) else start()
    }

    private fun show(screen: Screen) = _ui.update { it.copy(screen = screen, error = null, busy = false) }

    private fun fail(e: Throwable) {
        if (e is ChamberException && e.signedOut) return signedOut()
        if (e is ChamberException && e.locked) return locked()
        _ui.update { it.copy(busy = false, error = (e as? ChamberException)?.message ?: "Something went wrong. Try again.") }
    }

    /** Show a message from the phone itself (e.g. why a confirmation didn't happen). */
    fun say(message: String) = _ui.update { it.copy(error = message, busy = false) }

    fun dismissError() = _ui.update { it.copy(error = null, notice = null) }

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
        presence?.cancel()
        Push.signedOut(getApplication<Application>()) // no more pushes for this session, and its notifications leave the screen
        vault.clear()
        _ui.value = UiState(screen = Screen.SignIn())
    }

    // ── Screen lock (the server's, T-0241/T-0359) ───────────────────────────────────────────────

    private fun locked() {
        live?.cancel()
        val now = _ui.value.screen
        if (now !is Screen.Locked) _ui.update { it.copy(screen = Screen.Locked(now), busy = false, error = null) }
    }

    fun unlock(password: String) = work {
        api.unlock(password)
        val then = (_ui.value.screen as? Screen.Locked)?.then ?: Screen.Rooms
        _ui.update { it.copy(screen = then, busy = false, error = null) }
        listen()
        (then as? Screen.Chat)?.let { refreshChat(it.room) }
        if (then == Screen.Questions) refreshAsks()
    }

    fun lockNow() = work {
        api.lockNow()
        locked()
    }

    /** Called for every touch. Only "someone used it since the last report" is sent, never what they did. */
    fun touched() { touched = true }

    /** While the app is on screen; stops in the background, so the server's "closed" lock rule can apply. */
    fun foreground(visible: Boolean) {
        presence?.cancel()
        if (!visible || vault.read() == null) return
        presence = viewModelScope.launch {
            while (true) {
                val active = touched.also { touched = false }
                try {
                    api.activity(active)
                } catch (e: ChamberException) {
                    if (e.locked || e.signedOut) { fail(e); return@launch }
                } catch (e: Throwable) {
                    // offline: the server counts the gap
                }
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    // ── Rooms and chat ──────────────────────────────────────────────────────────────────────────

    private fun start() = work {
        val me = api.me()
        val rooms = api.rooms()
        val first = if (questionsNext) Screen.Questions else Screen.Rooms
        questionsNext = false
        _ui.update { it.copy(me = me.username, rooms = rooms, screen = first, busy = false, error = null) }
        listen()
        if (first == Screen.Questions) refreshAsks()
    }

    private fun listen() {
        live?.cancel()
        live = viewModelScope.launch {
            try {
                api.changes().collect { change ->
                    when (change.kind) {
                        "rooms" -> refreshRooms()
                        "tickets", "reviews" -> if (_ui.value.screen.let { it is Screen.Tickets || it is Screen.TicketOpen }) refreshTickets()
                        "asks" -> refreshAsks()
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

    /** A tap on a notification about an agent's question. Never skips sign-in or the screen lock. */
    fun openQuestions() {
        when (val now = _ui.value.screen) {
            Screen.Starting -> questionsNext = true
            is Screen.SignIn -> questionsNext = true
            is Screen.Locked -> _ui.update { it.copy(screen = Screen.Locked(Screen.Questions)) }
            else -> if (now != Screen.Questions) tab(Screen.Questions) else work { refreshAsks() }
        }
    }

    fun back() {
        when (_ui.value.screen) {
            is Screen.Chat -> show(Screen.Rooms)
            is Screen.TicketOpen -> show(Screen.Tickets)
            Screen.Tickets, Screen.Questions -> show(Screen.Rooms)
            else -> Unit
        }
    }

    fun tab(to: Screen) {
        show(to)
        when (to) {
            Screen.Tickets -> work { refreshTickets() }
            Screen.Questions -> work { refreshAsks() }
            else -> Unit
        }
    }

    // ── Tickets: decisions only inside an opened ticket (CHAMBER-MOBILE-SOP §4) ─────────────────

    private suspend fun refreshTickets() {
        val tickets = api.tickets()
        _ui.update { st ->
            val open = st.open.ticket?.let { cur -> tickets.firstOrNull { it.id == cur.id } }
            st.copy(tickets = tickets, open = if (open != null) st.open.copy(ticket = open) else st.open)
        }
    }

    fun openTicket(id: String) {
        val ticket = _ui.value.tickets.firstOrNull { it.id == id }
        _ui.update { it.copy(screen = Screen.TicketOpen(id), open = TicketView(ticket = ticket), error = null, notice = null) }
        work {
            val change = api.diff(id)
            if ((_ui.value.screen as? Screen.TicketOpen)?.id == id) _ui.update { it.copy(open = it.open.copy(change = change, changeChecked = true)) }
        }
    }

    /** Called only after the person confirmed with fingerprint or PIN on the opened ticket. */
    fun decide(approve: Boolean, reason: String?) {
        val open = _ui.value.open
        val ticket = open.ticket ?: return
        if (approve && !open.changeChecked) return
        guarded {
            api.decide(ticket.id, approve, _ui.value.me, reason, open.change?.meta?.version)
            refreshTickets()
            _ui.update { it.copy(notice = if (approve) "You approved ${ticket.id}." else "You sent ${ticket.id} back.") }
        }
    }

    // ── Agents' questions ───────────────────────────────────────────────────────────────────────

    private suspend fun refreshAsks() {
        val asks = api.waitingAsks()
        _ui.update { it.copy(asks = asks) }
    }

    /** Allow is called only after the fingerprint or PIN confirmation; Deny needs none. */
    fun answer(ask: Ask, allow: Boolean) = guarded {
        api.answer(ask.id, allow)
        refreshAsks()
        _ui.update { it.copy(notice = if (allow) "Allowed once: ${ask.id}." else "You said no to ${ask.id}.") }
    }

    // ── Password re-check: Chamber asks again before sensitive actions ──────────────────────────

    private fun guarded(action: suspend () -> Unit) = work {
        try {
            action()
        } catch (e: ChamberException) {
            if (!e.recheck) throw e
            afterRecheck = action
            _ui.update { it.copy(recheck = true, busy = false, error = e.message) }
        }
    }

    fun recheck(password: String) = work {
        api.recheck(password)
        val again = afterRecheck
        afterRecheck = null
        _ui.update { it.copy(recheck = false, error = null) }
        again?.invoke()
    }

    fun cancelRecheck() {
        afterRecheck = null
        _ui.update { it.copy(recheck = false, error = null, notice = "Not sent.") }
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
