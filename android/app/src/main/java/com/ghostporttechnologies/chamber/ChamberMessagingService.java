package com.ghostporttechnologies.chamber;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/** Firebase carries a wake-up and nothing else. Whatever is in the message is ignored on purpose. */
public class ChamberMessagingService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        ChamberNotifier.onPush(getApplicationContext());
    }

    @Override
    public void onNewToken(@NonNull String token) {
        ChamberPush.register(getApplicationContext(), token);
    }
}
