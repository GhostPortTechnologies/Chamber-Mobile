package com.ghostporttechnologies.chamber

import com.ghostporttechnologies.chamber.push.AskRules
import com.ghostporttechnologies.chamber.push.Notifier
import com.ghostporttechnologies.chamber.push.Push
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.lifecycle.lifecycleScope
import com.ghostporttechnologies.chamber.drive.Drive
import com.ghostporttechnologies.chamber.drive.DriveRules
import com.ghostporttechnologies.chamber.ui.DrivingHold
import com.ghostporttechnologies.chamber.ui.DrivingOffScreen
import com.ghostporttechnologies.chamber.ui.DrivingPausedBanner
import com.ghostporttechnologies.chamber.ui.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
    private var recheckTick by mutableIntStateOf(0) // re-reads detection after Android's permission dialog or settings
    private val askDetection = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val permission = Drive.PERMISSION
        if (granted) Drive.startDetection(this)
        else if (permission != null && !shouldShowRequestPermissionRationale(permission)) {
            // Asked before and refused: Android won't show its dialog again, so open Chamber's page in Android settings.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        recheckTick++
    }

    private val askFirstRun = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (Drive.PERMISSION?.let { result[it] } == true) Drive.startDetection(this)
        Push.start(this)
        recheckTick++
    }

    /** Once, after the first sign-in: notifications (Android 13+) and driving detection (Android 10+) in one request. */
    private fun askOnceAfterSignIn() {
        val prefs = getSharedPreferences("permissions", MODE_PRIVATE)
        if (prefs.getBoolean("asked", false)) return
        prefs.edit().putBoolean("asked", true).apply()
        val wanted = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null,
            Drive.PERMISSION,
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) askFirstRun.launch(wanted.toTypedArray())
    }

    private fun changeDrive(change: (com.ghostporttechnologies.chamber.drive.DriveState) -> com.ghostporttechnologies.chamber.drive.DriveState) {
        lifecycleScope.launch { Drive.update(this@MainActivity, change) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // native screens keep themselves clear of the status bar, cutouts and gesture bar
        Drive.load(this) // before the first frame, so a drive in progress is never shown as "not driving"
        if (savedInstanceState == null) openFrom(intent)
        setContent {
            ChamberTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val ui by state.ui.collectAsStateWithLifecycle()
                    val signedIn = ui.screen !is Screen.SignIn && ui.screen != Screen.Starting
                    val drive by Drive.state.collectAsStateWithLifecycle()
                    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(Unit) { while (true) { delay(30_000); clock = System.currentTimeMillis() } } // a trip pause runs out by time
                    val now = maxOf(clock, System.currentTimeMillis())
                    val driving = DriveRules.driving(drive, now)
                    val pausedInVehicle = drive.inVehicle && DriveRules.paused(drive, now) && !drive.off
                    LaunchedEffect(signedIn) {
                        if (signedIn) { Push.start(this@MainActivity); askOnceAfterSignIn() } // right after sign-in too
                    }
                    val tabs: @androidx.compose.runtime.Composable (Tab) -> Unit = { current ->
                        BottomTabs(current, ui.asks.size) { tab ->
                            state.tab(when (tab) { Tab.Chat -> Screen.Rooms; Tab.Tickets -> Screen.Tickets; Tab.Questions -> Screen.Questions })
                        }
                    }
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    if (signedIn && pausedInVehicle) DrivingPausedBanner()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        // Driving mode (T-0321) covers everything once signed in, the screen lock's password box included.
                        if (signedIn && driving) {
                            DrivingHold(onNotDriving = {
                                confirmItsYou("I'm not driving", "Pauses driving mode for this trip. Don't use Chamber while you drive.") { problem ->
                                    if (problem == null) changeDrive { DriveRules.notDriving(it, System.currentTimeMillis()) } else state.say(problem)
                                }
                            })
                        } else {
                        if (ui.recheck) RecheckDialog(ui.busy, ui.error, state::recheck, state::cancelRecheck)
                        when (val screen = ui.screen) {
                            Screen.Starting -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            is Screen.SignIn -> SignInScreen(screen.hint, ui.busy, ui.error, state::signIn, state::enterCode)
                            Screen.Rooms -> RoomsScreen(ui.me, ui.rooms, ui.error, { tabs(Tab.Chat) }, state::openRoom, state::lockNow) { state.tab(Screen.Settings) }
                            Screen.Settings -> {
                                BackHandler(onBack = state::back)
                                val detection = recheckTick.let { Drive.detection(this@MainActivity) } // updates after the dialog
                                SettingsScreen(ui.me, drive, detection, state::back,
                                    onAllowDetection = { Drive.PERMISSION?.let { askDetection.launch(it) } ?: Drive.startDetection(this@MainActivity) },
                                    onDrivingNow = { changeDrive(DriveRules::drivingByHand) },
                                    onTurnOff = { state.tab(Screen.DrivingOff) },
                                    onTurnOn = { changeDrive(DriveRules::turnedOn) },
                                    onSignOut = state::signOut)
                            }
                            Screen.DrivingOff -> {
                                BackHandler(onBack = state::back)
                                DrivingOffScreen(drive.inVehicle, state::back) {
                                    confirmItsYou("Turn off driving mode?", "You take full responsibility for using Chamber while you drive.") { problem ->
                                        if (problem == null) {
                                            changeDrive { DriveRules.turnedOff(it, System.currentTimeMillis()) }
                                            state.back()
                                        } else state.say(problem)
                                    }
                                }
                            }
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
                                    confirmItsYou("Allow ${AskRules.visible(ask.agent, 40)} once?", AskRules.visible(AskRules.command(ask.summary), 120)) { problem ->
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
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    override fun onResume() {
        super.onResume()
        Push.start(this) // picks up a permission or token change; does nothing before sign-in
        Drive.startDetection(this) // does nothing without the permission or Play services
        recheckTick++
    }

    /** Any app can start this activity, so the only thing taken from the intent is one known word. */
    private fun openFrom(intent: Intent?) {
        if (intent?.getStringExtra(Notifier.EXTRA_OPEN) == Notifier.OPEN_QUESTIONS) state.openQuestions()
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
