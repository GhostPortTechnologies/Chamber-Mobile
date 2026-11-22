package com.ghostporttechnologies.chamber;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.text.format.DateFormat;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * The native twin of Chamber's public/sw.js: a push says only "something new", and the phone asks Chamber itself
 * what is waiting. On a locked screen neither the agent's name nor the command is shown.
 */
final class ChamberNotifier {

    static final String EXTRA_MESSAGE = "chamber.message";
    private static final String ASKS = "approvals";
    private static final String NEWS = "activity";
    private static final String ASK_TAG = "ask-";
    private static final int ID = 1;
    private static final int MAX_SHOWN = 5;

    private ChamberNotifier() {}

    static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = manager(context);
        NotificationChannel asks = new NotificationChannel(ASKS, "Agents waiting for you", NotificationManager.IMPORTANCE_HIGH);
        asks.setDescription("An agent asks before it does something. No answer means no.");
        manager.createNotificationChannel(asks);
        manager.createNotificationChannel(
            new NotificationChannel(NEWS, "Messages and mentions", NotificationManager.IMPORTANCE_DEFAULT));
    }

    static void onPush(Context context) {
        createChannels(context);
        NotificationManager manager = manager(context);
        if (showTest(context, manager)) return;
        ChamberApi.Reply reply = ChamberApi.get("/api/asks?status=waiting");
        Set<String> shown = new HashSet<>();
        for (StatusBarNotification n : manager.getActiveNotifications()) {
            if (n.getTag() != null && n.getTag().startsWith(ASK_TAG)) shown.add(n.getTag());
        }

        boolean fresh = false;
        if (reply.status == 200 || reply.status == 401 || reply.status == 423) {
            JSONArray asks = reply.body.optJSONArray("asks");
            Set<String> live = new HashSet<>();
            int count = 0;
            for (int i = 0; asks != null && i < asks.length(); i++) {
                JSONObject ask = asks.optJSONObject(i);
                if (ask == null || !ask.optBoolean("canAnswer") || !AskRules.validAskId(ask.optString("id"))) continue;
                String tag = ASK_TAG + ask.optString("id") + "-" + fingerprint(ask);
                live.add(tag);
                if (count++ < MAX_SHOWN && !shown.contains(tag)) {
                    showAsk(context, tag, ask);
                    fresh = true;
                }
            }
            // Answered elsewhere, out of time, signed out or locked: it leaves this phone.
            for (String tag : shown) if (!live.contains(tag)) manager.cancel(tag, ID);
        }
        if (fresh) return; // the question is the news

        manager.notify("news", ID, base(context, NEWS)
            .setContentTitle("Chamber")
            .setContentText("Something new for you")
            .setContentIntent(open(context, null))
            .build());
    }

    /** Settings > Notifications > Send a test. "Shown" is reported only if Android really lets it show. */
    private static boolean showTest(Context context, NotificationManager manager) {
        ChamberApi.Reply test = ChamberApi.get("/account/push/test");
        if (test.status != 200 || !test.body.optBoolean("test")) return false;
        boolean allowed = manager.areNotificationsEnabled();
        if (allowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel news = manager.getNotificationChannel(NEWS);
            allowed = news != null && news.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        if (!allowed) return true;
        manager.notify("test", ID, base(context, NEWS)
            .setContentTitle("Test notification from Chamber")
            .setContentText("It works: alerts reach this device.")
            .setContentIntent(open(context, null))
            .build());
        try {
            ChamberApi.post("/account/push/test/shown", new JSONObject().put("nonce", test.body.optString("nonce")));
        } catch (JSONException ignored) {
            // Settings then says it didn't appear, which is the safe way to be wrong.
        }
        return true;
    }

    private static void showAsk(Context context, String tag, JSONObject ask) {
        long now = System.currentTimeMillis();
        long expires = ask.optLong("expiresAt");
        if (expires <= now) return;
        String summary = ask.optString("summary");
        String agent = AskRules.visible(ask.optString("agent").replace('\n', ' '), 40);
        boolean command = "Bash".equals(ask.optString("tool"));
        boolean buttons = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S // older Android can't insist on an unlock first
            && AskRules.canAnswerFromNotification(ask.optString("tool"), summary, true, expires, now);
        String until = DateFormat.getTimeFormat(context).format(new Date(expires));
        String what = AskRules.visible(AskRules.command(summary), AskRules.MAX_COMMAND);
        // The deadline and instruction come first, so text from the agent can't push them out of sight or fake them.
        String body = "No answer by " + until + " means no."
            + (buttons ? "" : " Tap to read it all and answer.") + "\n\n" + what;

        Notification locked = base(context, ASKS)
            .setContentTitle("An agent is waiting for you")
            .setContentText("Unlock to see it. No answer by " + until + " means no.")
            .build();
        NotificationCompat.Builder builder = base(context, ASKS)
            .setContentTitle(agent + (command ? " asks: may I run this command?" : " asks: may I do this?"))
            .setContentText(what)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(locked)
            .setTimeoutAfter(expires - now)
            .setContentIntent(open(context, ask.optString("msgId")));
        if (buttons) {
            builder.addAction(answer(context, tag, ask, "allow", "Allow once"));
            builder.addAction(answer(context, tag, ask, "deny", "Deny"));
        }
        manager(context).notify(tag, ID, builder.build());
    }

    private static NotificationCompat.Action answer(Context context, String tag, JSONObject ask, String decision, String label) {
        Intent intent = new Intent(context, AskAnswerReceiver.class)
            .setData(Uri.parse("chamber-ask://" + decision + "/" + tag)) // a separate PendingIntent for every question and choice
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(AskAnswerReceiver.ID, ask.optString("id"))
            .putExtra(AskAnswerReceiver.TAG, tag)
            .putExtra(AskAnswerReceiver.FINGERPRINT, fingerprint(ask))
            .putExtra(AskAnswerReceiver.DECISION, decision);
        PendingIntent pending = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Action.Builder(0, label, pending).setAuthenticationRequired(true).build();
    }

    static void answered(Context context, String tag, String title, String detail, String messageId) {
        NotificationManager manager = manager(context);
        manager.cancel(tag, ID);
        manager.notify("answer", ID, base(context, NEWS)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(open(context, messageId))
            .build());
    }

    static String fingerprint(JSONObject ask) {
        return AskRules.fingerprint(ask.optString("agent"), ask.optString("summary"), ask.optLong("expiresAt"));
    }

    private static PendingIntent open(Context context, String messageId) {
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        String key = "home";
        if (AskRules.validMessageId(messageId)) {
            intent.putExtra(EXTRA_MESSAGE, messageId);
            key = messageId;
        }
        intent.setData(Uri.parse("chamber-open://" + key));
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static NotificationCompat.Builder base(Context context, String channel) {
        return new NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true);
    }

    private static NotificationManager manager(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }
}
