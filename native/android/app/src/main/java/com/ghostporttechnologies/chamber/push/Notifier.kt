package com.ghostporttechnologies.chamber.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import com.ghostporttechnologies.chamber.MainActivity
import com.ghostporttechnologies.chamber.R
import com.ghostporttechnologies.chamber.data.Ask
import com.ghostporttechnologies.chamber.data.Chamber
import com.ghostporttechnologies.chamber.data.ChamberApi
import com.ghostporttechnologies.chamber.data.ChamberException
import java.util.Date

/**
 * A push says only "something new"; the phone then asks Chamber itself what is waiting, signed in as the person.
 * On a locked screen neither the agent's name nor the command is shown.
 */
object Notifier {

    const val EXTRA_OPEN = "chamber.open"
    const val OPEN_QUESTIONS = "questions"
    private const val ASKS = "approvals"
    private const val NEWS = "activity"
    private const val ASK_TAG = "ask-"
    private const val ID = 1
    private const val MAX_SHOWN = 5

    fun createChannels(context: Context) {
        val manager = manager(context)
        manager.createNotificationChannel(
            NotificationChannel(ASKS, "Agents waiting for you", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "An agent asks before it does something. No answer means no."
            },
        )
        manager.createNotificationChannel(NotificationChannel(NEWS, "Messages and mentions", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Runs on Firebase's background thread, so it may wait for Chamber. Whatever the push carried is ignored. */
    suspend fun onPush(context: Context) {
        createChannels(context)
        val api = Chamber.api(context)
        val manager = manager(context)
        if (showTest(context, api, manager)) return

        val asks: List<Ask>? = try {
            api.waitingAsks()
        } catch (e: ChamberException) {
            // Signed out or locked: nothing here may show what is waiting. Offline: leave what is shown alone.
            if (e.signedOut || e.locked) emptyList() else null
        }
        val shown = manager.activeNotifications.mapNotNull { it.tag }.filter { it.startsWith(ASK_TAG) }.toSet()
        var fresh = false
        if (asks != null) {
            val live = mutableSetOf<String>()
            asks.filter { AskRules.validAskId(it.id) }.forEachIndexed { i, ask ->
                val tag = ASK_TAG + ask.id + "-" + fingerprint(ask)
                live += tag
                if (i < MAX_SHOWN && tag !in shown && showAsk(context, tag, ask)) fresh = true
            }
            // Answered elsewhere, out of time, signed out or locked: it leaves this phone.
            shown.filterNot { it in live }.forEach { manager.cancel(it, ID) }
        }
        if (fresh) return // the question is the news

        manager.notify("news", ID, base(context, NEWS)
            .setContentTitle("Chamber")
            .setContentText("Something new for you")
            .setContentIntent(open(context, null))
            .build())
    }

    /** "Shown" is reported to Settings only if Android really lets it show. */
    private suspend fun showTest(context: Context, api: ChamberApi, manager: NotificationManager): Boolean {
        val test = runCatching { api.pushTest() }.getOrNull()
        if (test == null || !test.test) return false
        val allowed = manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(NEWS)?.importance.let { it != null && it != NotificationManager.IMPORTANCE_NONE }
        if (!allowed) return true
        manager.notify("test", ID, base(context, NEWS)
            .setContentTitle("Test notification from Chamber")
            .setContentText("It works: alerts reach this device.")
            .setContentIntent(open(context, null))
            .build())
        // If this fails, Settings says it didn't appear, which is the safe way to be wrong.
        runCatching { api.pushTestShown(test.nonce) }
        return true
    }

    private fun showAsk(context: Context, tag: String, ask: Ask): Boolean {
        val now = System.currentTimeMillis()
        if (ask.expiresAt <= now) return false
        val agent = AskRules.visible(ask.agent.replace('\n', ' '), 40)
        val command = ask.tool == "Bash"
        // Older Android can't insist on an unlock before a notification button works, so no buttons there.
        val buttons = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            AskRules.canAnswerFromNotification(ask.tool, ask.summary, ask.canAnswer, ask.expiresAt, now)
        val until = DateFormat.getTimeFormat(context).format(Date(ask.expiresAt))
        val what = AskRules.visible(AskRules.command(ask.summary), AskRules.MAX_COMMAND)
        // The deadline and instruction come first, so text from the agent can't push them out of sight or fake them.
        val body = "No answer by $until means no." + (if (buttons) "" else " Tap to read it all and answer.") + "\n\n" + what

        val locked = base(context, ASKS)
            .setContentTitle("An agent is waiting for you")
            .setContentText("Unlock to see it. No answer by $until means no.")
            .build()
        val builder = base(context, ASKS)
            .setContentTitle(agent + if (command) " asks: may I run this command?" else " asks: may I do this?")
            .setContentText(what)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(locked)
            .setTimeoutAfter(ask.expiresAt - now)
            .setContentIntent(open(context, OPEN_QUESTIONS))
        if (buttons) {
            builder.addAction(answerAction(context, tag, ask, "allow", "Allow once"))
            builder.addAction(answerAction(context, tag, ask, "deny", "Deny"))
        }
        manager(context).notify(tag, ID, builder.build())
        return true
    }

    private fun answerAction(context: Context, tag: String, ask: Ask, decision: String, label: String): NotificationCompat.Action {
        val intent = Intent(context, AskAnswerReceiver::class.java)
            .setData(Uri.parse("chamber-ask://$decision/$tag")) // a separate PendingIntent for every question and choice
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(AskAnswerReceiver.ID, ask.id)
            .putExtra(AskAnswerReceiver.TAG, tag)
            .putExtra(AskAnswerReceiver.FINGERPRINT, fingerprint(ask))
            .putExtra(AskAnswerReceiver.DECISION, decision)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(0, label, pending).setAuthenticationRequired(true).build()
    }

    fun answered(context: Context, tag: String, title: String, detail: String) {
        val manager = manager(context)
        manager.cancel(tag, ID)
        manager.notify("answer", ID, base(context, NEWS)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(open(context, OPEN_QUESTIONS))
            .build())
    }

    /** Signing out takes everything Chamber showed off this phone. */
    fun clearAll(context: Context) = manager(context).cancelAll()

    fun fingerprint(ask: Ask): String = AskRules.fingerprint(ask.agent, ask.summary, ask.expiresAt)

    private fun open(context: Context, where: String?): PendingIntent {
        val key = where ?: "home"
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setData(Uri.parse("chamber-open://$key"))
            .putExtra(EXTRA_OPEN, key)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun base(context: Context, channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setAutoCancel(true)

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
}
