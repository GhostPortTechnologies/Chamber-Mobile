package com.ghostporttechnologies.chamber.push

import com.ghostporttechnologies.chamber.data.ChamberJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** How this phone gets alerts (T-0388). */
enum class Provider { Google, UnifiedPush }

/** The decisions behind alerts, kept free of Android so plain unit tests cover them. */
object PushRules {

    /**
     * Google when it works, unless the person chose UnifiedPush; UnifiedPush when there is no Google (GrapheneOS
     * without Play, for example); nothing when neither is on the phone. A choice that no longer works falls back.
     */
    fun provider(google: Boolean, distributor: Boolean, chosen: Provider?): Provider? = when {
        chosen == Provider.UnifiedPush && distributor -> Provider.UnifiedPush
        google -> Provider.Google
        distributor -> Provider.UnifiedPush
        else -> null
    }

    /**
     * Chamber's wake-up decrypts to exactly {"kind":"wake"}. Anyone who knows a push address can post to it (an
     * ntfy topic is open), but only Chamber has this phone's keys, so a message that didn't decrypt, or says
     * anything else, wakes nothing.
     */
    fun isWake(decrypted: Boolean, content: ByteArray): Boolean {
        if (!decrypted || content.isEmpty() || content.size > 64) return false
        val message = runCatching { ChamberJson.parseToJsonElement(content.decodeToString()).jsonObject }.getOrNull() ?: return false
        val kind = message["kind"] as? JsonPrimitive ?: return false
        return message.size == 1 && kind.isString && kind.content == "wake"
    }

    /** What Settings says after "Send a test notification", from Chamber's answer. [why] is the push server's text. */
    fun testResult(pushService: String, why: String, via: String): String = when (pushService) {
        "ok" -> "Sent through ${via.ifBlank { "your push server" }}. It should show in a few seconds."
        "held" -> "Held back: Chamber sent this phone a lot of alerts in the last minute. Try again in a minute."
        "gone" -> "Your push server says this phone's address is gone. Open your push app, then open Chamber again."
        else -> "Your push server didn't take it" + if (why.isBlank()) "." else ": " + AskRules.visible(why, 160)
    }
}
