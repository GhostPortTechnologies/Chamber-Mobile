package com.ghostporttechnologies.chamber

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghostporttechnologies.chamber.ui.ChamberTheme
import com.ghostporttechnologies.chamber.ui.ChatScreen
import com.ghostporttechnologies.chamber.ui.BottomTabs
import com.ghostporttechnologies.chamber.ui.LockedScreen
import com.ghostporttechnologies.chamber.ui.QuestionsScreen
import com.ghostporttechnologies.chamber.ui.RecheckDialog
import com.ghostporttechnologies.chamber.ui.Tab
import com.ghostporttechnologies.chamber.ui.TicketScreen
import com.ghostporttechnologies.chamber.ui.TicketsScreen
import com.ghostporttechnologies.chamber.ui.RoomsScreen
import com.ghostporttechnologies.chamber.ui.SignInScreen

class MainActivity : FragmentActivity() {

    private val state: AppState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // native screens keep themselves clear of the status bar, cutouts and gesture bar
        setContent {
            ChamberTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val ui by state.ui.collectAsStateWithLifecycle()
                    val tabs: @androidx.compose.runtime.Composable (Tab) -> Unit = { current ->
                        BottomTabs(current, ui.asks.size) { tab ->
                            state.tab(when (tab) { Tab.Chat -> Screen.Rooms; Tab.Tickets -> Screen.Tickets; Tab.Questions -> Screen.Questions })
                        }
                    }
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        if (ui.recheck) RecheckDialog(ui.busy, ui.error, state::recheck, state::cancelRecheck)
                        when (val screen = ui.screen) {
                            Screen.Starting -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            is Screen.SignIn -> SignInScreen(screen.hint, ui.busy, ui.error, state::signIn, state::enterCode)
                            Screen.Rooms -> RoomsScreen(ui.me, ui.rooms, ui.error, { tabs(Tab.Chat) }, state::openRoom, state::lockNow, state::signOut)
                            Screen.Tickets -> TicketsScreen(ui.tickets, ui.error, ui.notice, { tabs(Tab.Tickets) }, state::openTicket)
                            is Screen.TicketOpen -> {
                                BackHandler(onBack = state::back)
                                TicketScreen(ui.open, ui.busy, ui.error, ui.notice, state::back,
                                    onApprove = {
                                        ui.open.ticket?.let { t ->
                                            confirmItsYou("Approve ${t.id}?", t.title) { problem ->
                                                if (problem == null) state.decide(true, null) else state.say(problem)
                                            }
                                        }
                                    },
                                    onReject = { reason -> state.decide(false, reason) })
                            }
                            Screen.Questions -> QuestionsScreen(ui.asks, ui.busy, ui.error, ui.notice, { tabs(Tab.Questions) },
                                onAllow = { ask ->
                                    confirmItsYou("Allow ${ask.agent} once?", ask.summary.removePrefix("run this command: ").take(120)) { problem ->
                                        if (problem == null) state.answer(ask, true) else state.say(problem)
                                    }
                                },
                                onDeny = { ask -> state.answer(ask, false) })
                            is Screen.Locked -> LockedScreen(ui.me, ui.busy, ui.error, state::unlock, state::signOut)
                            is Screen.Chat -> {
                                BackHandler(onBack = state::back)
                                val room = ui.rooms[screen.room]
                                val title = when {
                                    room == null -> "#" + screen.room
                                    room.dm -> room.name.ifBlank { screen.room }
                                    else -> "#" + room.name.ifBlank { screen.room }
                                }
                                ChatScreen(screen.room, title, ui.chat, ui.error, state::back, state::send)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) state.touched()
        return super.dispatchTouchEvent(event)
    }

    override fun onStart() {
        super.onStart()
        state.foreground(true)
    }

    override fun onStop() {
        state.foreground(false)
        super.onStop()
    }
}
