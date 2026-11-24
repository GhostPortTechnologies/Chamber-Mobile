package com.ghostporttechnologies.chamber

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghostporttechnologies.chamber.ui.ChamberTheme
import com.ghostporttechnologies.chamber.ui.ChatScreen
import com.ghostporttechnologies.chamber.ui.LockedScreen
import com.ghostporttechnologies.chamber.ui.RoomsScreen
import com.ghostporttechnologies.chamber.ui.SignInScreen

class MainActivity : ComponentActivity() {

    private val state: AppState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // native screens keep themselves clear of the status bar, cutouts and gesture bar
        setContent {
            ChamberTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val ui by state.ui.collectAsStateWithLifecycle()
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        when (val screen = ui.screen) {
                            Screen.Starting -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            is Screen.SignIn -> SignInScreen(screen.hint, ui.busy, ui.error, state::signIn, state::enterCode)
                            Screen.Rooms -> RoomsScreen(ui.me, ui.rooms, ui.error, state::openRoom, state::lockNow, state::signOut)
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
