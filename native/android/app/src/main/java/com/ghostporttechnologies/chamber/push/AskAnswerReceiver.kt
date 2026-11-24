package com.ghostporttechnologies.chamber.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ghostporttechnologies.chamber.data.Chamber
import com.ghostporttechnologies.chamber.data.ChamberException
import com.ghostporttechnologies.chamber.drive.Drive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Allow once or Deny, tapped on a notification (Android unlocks the phone first). Re-reads the question before it answers. */
class AskAnswerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(ID)
        val tag = intent.getStringExtra(TAG) ?: return
        val fingerprint = intent.getStringExtra(FINGERPRINT) ?: return
        val decision = intent.getStringExtra(DECISION)
        if (!AskRules.validAskId(id) || (decision != "allow" && decision != "deny")) return
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                answer(app, id!!, tag, fingerprint, decision == "allow")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun answer(context: Context, id: String, tag: String, fingerprint: String, allow: Boolean) {
        if (Drive.driving(context)) {
            return Notifier.answered(context, tag, "Not answered", "Driving mode is on. Answer in Chamber once you've stopped.")
        }
        val api = Chamber.api(context)
        val current = try {
            api.ask(id)
        } catch (e: ChamberException) {
            return Notifier.answered(context, tag, "Not answered", why(e, sent = false))
        }
        if (fingerprint != Notifier.fingerprint(current)) {
            return Notifier.answered(context, tag, "Not answered", "That question is no longer the one on this notification. Open Chamber to check.")
        }
        try {
            api.answer(id, allow, fromNotification = true)
            Notifier.answered(context, tag, if (allow) "Allowed once" else "You said no",
                AskRules.visible(AskRules.command(current.summary), AskRules.MAX_COMMAND))
        } catch (e: ChamberException) {
            Notifier.answered(context, tag, "Not answered", why(e, sent = true))
        }
    }

    private fun why(e: ChamberException, sent: Boolean): String = when {
        // After a send that got no reply we can't know whether it arrived, so don't claim either way.
        e.status == 0 && sent -> "Chamber didn't reply. Open Chamber to check whether it still needs an answer."
        e.status == 0 -> "Can't reach Chamber, so nothing was answered. Open Chamber to answer."
        e.signedOut -> "You're signed out on this phone, so nothing was answered. Open Chamber to answer."
        e.locked -> "Chamber is locked, so nothing was answered. Open Chamber to unlock and answer."
        else -> AskRules.visible(e.message, 200) + " Open Chamber to check."
    }

    companion object {
        const val ID = "id"
        const val TAG = "tag"
        const val FINGERPRINT = "fingerprint"
        const val DECISION = "decision"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
