package com.ghostporttechnologies.chamber.drive

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.ghostporttechnologies.chamber.push.Notifier
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Can this phone notice driving by itself? */
enum class Detection { On, NeedsPermission, Unavailable }

/**
 * Driving mode for the whole app (T-0321): one state, kept on the phone only, shared by the screens, notifications
 * and the detection receiver. The rules are in [DriveRules].
 */
object Drive {

    private const val LOG = "ChamberDrive"
    private val _state = MutableStateFlow(DriveState())
    val state: StateFlow<DriveState> = _state.asStateFlow()
    @Volatile private var loaded = false

    fun load(context: Context): DriveState {
        if (!loaded) synchronized(this) {
            if (!loaded) { _state.value = read(prefs(context)); loaded = true }
        }
        return _state.value
    }

    fun driving(context: Context, now: Long = System.currentTimeMillis()) = DriveRules.driving(load(context), now)

    /**
     * Change the state. When a drive ends this way (stopped, "I'm not driving", turned off), the one summary of
     * what waited is shown.
     */
    suspend fun update(context: Context, change: (DriveState) -> DriveState) {
        val now = System.currentTimeMillis()
        val (before, after) = synchronized(this) {
            val b = load(context)
            val a = change(b)
            write(prefs(context), a)
            _state.value = a
            b to a
        }
        val was = DriveRules.driving(before, now)
        val drivingNow = DriveRules.driving(after, now)
        if (!was && drivingNow) Notifier.drivingStarted(context)
        if (was && !drivingNow) Notifier.afterDrive(context)
    }

    // ── Detection: Android's own activity detection, through Google Play services ────────────────────

    fun detection(context: Context): Detection = when {
        !playServices(context) -> Detection.Unavailable
        !permitted(context) -> Detection.NeedsPermission
        else -> Detection.On
    }

    /** Runtime permission on Android 10+; before that it is granted at install. */
    val PERMISSION: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACTIVITY_RECOGNITION else null

    private fun permitted(context: Context) =
        PERMISSION == null || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun playServices(context: Context) =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /** Safe to call often: registering again replaces the earlier registration. Lost on reboot, so the boot receiver calls it too. */
    @SuppressLint("MissingPermission") // checked by detection()
    fun startDetection(context: Context) {
        if (detection(context) != Detection.On) return
        val vehicle = listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
            ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE).setActivityTransition(it).build()
        }
        runCatching {
            ActivityRecognition.getClient(context.applicationContext)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(vehicle), transitions(context))
                .addOnFailureListener { Log.w(LOG, "driving detection didn't start") }
        }.onFailure { Log.w(LOG, "driving detection didn't start") }
    }

    private fun transitions(context: Context): PendingIntent {
        val intent = Intent(context, DriveReceiver::class.java).setAction(DriveReceiver.TRANSITIONS)
        // Play services adds the result to this intent, so it has to be mutable; it is explicit, to our own receiver.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 7, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    // ── Storage (app-private; driving mode never leaves the phone) ───────────────────────────────────

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("drive", Context.MODE_PRIVATE)

    private fun read(p: SharedPreferences) = DriveState(
        off = p.getBoolean("off", false), offAt = p.getLong("offAt", 0),
        manual = p.getBoolean("manual", false), inVehicle = p.getBoolean("inVehicle", false),
        enteredAt = p.getLong("enteredAt", 0), leftAt = p.getLong("leftAt", 0),
        pausedAt = p.getLong("pausedAt", 0), held = p.getInt("held", 0),
    )

    private fun write(p: SharedPreferences, s: DriveState) = p.edit()
        .putBoolean("off", s.off).putLong("offAt", s.offAt)
        .putBoolean("manual", s.manual).putBoolean("inVehicle", s.inVehicle)
        .putLong("enteredAt", s.enteredAt).putLong("leftAt", s.leftAt)
        .putLong("pausedAt", s.pausedAt).putInt("held", s.held)
        .commit() // the receiver's process may end right after, so write now
}
