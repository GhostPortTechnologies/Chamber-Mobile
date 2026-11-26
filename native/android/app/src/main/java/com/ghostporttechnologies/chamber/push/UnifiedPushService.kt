package com.ghostporttechnologies.chamber.push

import android.content.Intent
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Alerts without Google (T-0388): a UnifiedPush distributor such as ntfy wakes the app, and the app then asks Chamber
 * what is waiting, exactly as with Firebase. The message itself carries nothing but {"kind":"wake"}.
 */
class UnifiedPushService : PushService() {

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        Push.unifiedEndpoint(applicationContext, endpoint.url, endpoint.pubKeySet?.pubKey, endpoint.pubKeySet?.auth)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (!PushRules.isWake(message.decrypted, message.content)) return
        // This can run on the main thread, and the library lets go of this service 5 seconds after the last event.
        // Starting the service keeps the app running until Chamber has answered. While the distributor holds the app
        // in the foreground Android allows it; if it doesn't, the check still runs, just without that guarantee.
        val started = runCatching { startService(Intent(this, UnifiedPushService::class.java).setAction(WAKE)) }.isSuccess
        if (!started) Push.wake(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == WAKE) Push.wake(applicationContext) { stopSelf(startId) } else stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) =
        Push.unifiedFailed(applicationContext, reason)

    override fun onUnregistered(instance: String) = Push.unifiedGone(applicationContext)

    private companion object {
        const val WAKE = "com.ghostporttechnologies.chamber.push.WAKE"
    }
}
