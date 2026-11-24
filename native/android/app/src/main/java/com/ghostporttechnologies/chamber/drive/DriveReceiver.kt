package com.ghostporttechnologies.chamber.drive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** "In a vehicle" and "left the vehicle" from Android's activity detection, and re-registering after a reboot or update. */
class DriveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Drive.startDetection(app)
            TRANSITIONS -> {
                if (!ActivityTransitionResult.hasResult(intent)) return
                val events = ActivityTransitionResult.extractResult(intent)?.transitionEvents.orEmpty()
                    .filter { it.activityType == DetectedActivity.IN_VEHICLE }
                if (events.isEmpty()) return
                val pending = goAsync()
                scope.launch {
                    try {
                        for (e in events) Drive.update(app) { s ->
                            val now = System.currentTimeMillis()
                            if (e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) DriveRules.entered(s, now) else DriveRules.left(s, now)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val TRANSITIONS = "com.ghostporttechnologies.chamber.drive.TRANSITIONS"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
