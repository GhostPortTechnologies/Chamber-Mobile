package com.ghostporttechnologies.chamber;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONException;
import org.json.JSONObject;

/** Allow once or Deny, tapped on a notification. Re-reads the question before it answers. */
public class AskAnswerReceiver extends BroadcastReceiver {

    static final String ID = "id";
    static final String TAG = "tag";
    static final String FINGERPRINT = "fingerprint";
    static final String DECISION = "decision";

    @Override
    public void onReceive(Context context, Intent intent) {
        String id = intent.getStringExtra(ID);
        String tag = intent.getStringExtra(TAG);
        String fingerprint = intent.getStringExtra(FINGERPRINT);
        String decision = intent.getStringExtra(DECISION);
        if (!AskRules.validAskId(id) || tag == null || fingerprint == null) return;
        if (!"allow".equals(decision) && !"deny".equals(decision)) return;

        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                answer(context.getApplicationContext(), id, tag, fingerprint, decision);
            } finally {
                pending.finish();
            }
        }).start();
    }

    private static void answer(Context context, String id, String tag, String fingerprint, String decision) {
        ChamberApi.Reply current = ChamberApi.get("/api/asks/" + id);
        String messageId = current.body.optString("msgId");
        if (current.status != 200) {
            ChamberNotifier.answered(context, tag, "Not answered", why(current), null);
            return;
        }
        if (!fingerprint.equals(ChamberNotifier.fingerprint(current.body))) {
            ChamberNotifier.answered(context, tag, "Not answered",
                "That question is no longer the one on this notification. Open Chamber to check.", null);
            return;
        }
        try {
            JSONObject body = new JSONObject().put("decision", decision).put("via", "notification");
            ChamberApi.Reply sent = ChamberApi.post("/api/asks/" + id + "/answer", body);
            if (sent.status == 200) {
                ChamberNotifier.answered(context, tag, "allow".equals(decision) ? "Allowed once" : "You said no",
                    AskRules.visible(AskRules.command(current.body.optString("summary")), AskRules.MAX_COMMAND), messageId);
            } else {
                ChamberNotifier.answered(context, tag, "Not answered", why(sent), messageId);
            }
        } catch (JSONException e) {
            ChamberNotifier.answered(context, tag, "Not answered", "Open Chamber to answer.", messageId);
        }
    }

    private static String why(ChamberApi.Reply reply) {
        // After a POST that got no reply we can't know whether it arrived, so don't claim either way.
        if (reply.status == 0) return "Chamber didn't reply. Open Chamber to check whether it still needs an answer.";
        if (reply.status == 401) return "You're signed out on this phone, so nothing was answered. Open Chamber to answer.";
        if (reply.status == 423) return "Chamber is locked, so nothing was answered. Open Chamber to unlock and answer.";
        return AskRules.visible(reply.body.optString("error", "Chamber didn't take the answer."), 200) + " Open Chamber to check.";
    }
}
