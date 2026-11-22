package com.ghostporttechnologies.chamber;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Asks for permission once someone has signed in, then tells Chamber this phone's push token (T-0335). */
final class ChamberPush {

    private static final String LOG = "ChamberPush";
    private static final long RETRY_MS = 30_000;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static String registered; // token and session last accepted; memory only
    private static long lastTry;

    private ChamberPush() {}

    /** Safe to call often. Does nothing in a build without Firebase or before sign-in. */
    static void start(Activity activity) {
        if (FirebaseApp.getApps(activity).isEmpty() || ChamberApi.session() == null) return;
        ChamberNotifier.createChannels(activity);
        askOnce(activity);
        Context context = activity.getApplicationContext();
        FirebaseMessaging messaging = FirebaseMessaging.getInstance();
        messaging.setAutoInitEnabled(true); // off in the manifest, so no token exists before the first sign-in
        messaging.getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) register(context, task.getResult());
            else Log.w(LOG, "no push token yet");
        });
    }

    private static void askOnce(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        String permission = Manifest.permission.POST_NOTIFICATIONS;
        if (ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences prefs = activity.getSharedPreferences("push", Context.MODE_PRIVATE);
        if (prefs.getBoolean("asked", false)) return;
        prefs.edit().putBoolean("asked", true).apply();
        ActivityCompat.requestPermissions(activity, new String[] { permission }, 1);
    }

    static void register(Context context, String token) {
        WORKER.execute(() -> {
            String session = ChamberApi.session();
            if (session == null) return;
            String key = token + session;
            long now = System.currentTimeMillis();
            if (key.equals(registered) || now - lastTry < RETRY_MS) return;
            lastTry = now;
            try {
                JSONObject device = new JSONObject()
                    .put("platform", "android")
                    .put("provider", "fcm")
                    .put("token", token)
                    .put("name", Build.MODEL);
                ChamberApi.Reply reply = ChamberApi.post("/account/push", new JSONObject().put("device", device));
                if (reply.status == 200) registered = key;
                else Log.w(LOG, "device not registered: HTTP " + reply.status); // never the token or the session
            } catch (JSONException e) {
                Log.w(LOG, "device not registered", e);
            }
        });
    }
}
