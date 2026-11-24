package com.ghostporttechnologies.chamber.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

/** Firebase carries a wake-up and nothing else. Whatever is in the message is ignored on purpose. */
class MessagingService : FirebaseMessagingService() {

    // Firebase calls this on its own background thread and gives it a few seconds, so waiting here is fine.
    override fun onMessageReceived(message: RemoteMessage) = runBlocking { Notifier.onPush(applicationContext) }

    override fun onNewToken(token: String) = Push.register(applicationContext, token)
}
