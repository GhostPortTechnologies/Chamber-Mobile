package com.ghostporttechnologies.chamber.data

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

const val CHAMBER_ORIGIN = "https://chamber.ghostporttechnologies.com"

/** What went wrong, in words a person can act on. [status] 0 = Chamber couldn't be reached. */
class ChamberException(
    val status: Int,
    override val message: String,
    val locked: Boolean = false,
    /** The server wants the password again before this action (T-0241 re-check). */
    val recheck: Boolean = false,
) : Exception(message) {
    val signedOut get() = status == 401
}

/**
 * Chamber's own HTTP API, as the person who signed in. Every rule is the server's (screen lock, 2FA, password
 * re-check, who may approve): the app never decides those, it only shows what the server says.
 */
class ChamberApi(private val tokens: () -> String?) {

    private val json = "application/json".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false) // the token is for Chamber only
        .build()

    fun request(path: String): Request.Builder {
        val builder = Request.Builder().url(CHAMBER_ORIGIN + path)
            .header("Accept", "application/json")
            // T-0359: the session shows as "Chamber app on <phone>" and gets the screen lock and re-check, like a browser.
            .header("X-Chamber-App", APP_HEADER)
        tokens()?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private suspend fun call(path: String, body: JsonObject? = null): String = withContext(Dispatchers.IO) {
        val request = request(path).apply { if (body != null) post(body.toString().toRequestBody(json)) }.build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) text else throw ChamberException(response.code, errorText(response.code, text), isLocked(response.code, text), flag(response.code == 403, text, "recheckRequired"))
            }
        } catch (e: IOException) {
            throw ChamberException(0, "Can't reach Chamber. Check your connection and try again.")
        }
    }

    suspend fun login(identity: String, password: String): LoginReply {
        val field = if ('@' in identity) "email" else "username"
        return decode(call("/account/login", buildJsonObject { put(field, identity.trim()); put("password", password) }))
    }

    suspend fun loginCode(mfaToken: String, code: String): LoginReply =
        decode(call("/account/login/totp", buildJsonObject { put("mfaToken", mfaToken); put("code", code.trim()) }))

    suspend fun logout() {
        runCatching { call("/account/logout", buildJsonObject { }) }
    }

    /** The phone's screen lock is Chamber's: the server decides when this session locks, the app shows it. */
    suspend fun unlock(password: String) {
        call("/account/unlock", buildJsonObject { put("password", password) })
    }

    suspend fun lockNow() {
        runCatching { call("/account/lock", buildJsonObject { put("reason", "manual") }) }
    }

    /** "The app is open", and "a person used it" when they did, as the web page reports it. */
    suspend fun activity(active: Boolean) {
        call("/account/activity", buildJsonObject { put("active", active) })
    }

    suspend fun recheck(password: String) {
        call("/account/recheck", buildJsonObject { put("password", password) })
    }

    // ── Tickets ─────────────────────────────────────────────────────────────────────────────────

    suspend fun tickets(): List<Ticket> = decode<TicketsReply>(call("/api/tickets")).tickets

    /** The change on a ticket, or null when it carries none (404). */
    suspend fun diff(id: String): DiffReply? = try {
        decode(call("/api/tickets/${path(id)}/diff"))
    } catch (e: ChamberException) {
        if (e.status == 404) null else throw e
    }

    /**
     * Approve or reject. [version] is the version of the change the person was shown: Chamber refuses the approval
     * if the agent replaced the change after it was opened.
     */
    suspend fun decide(id: String, approve: Boolean, by: String, reason: String? = null, version: Int? = null) {
        call("/api/tickets/${path(id)}/" + (if (approve) "approve" else "reject"), buildJsonObject {
            put("by", by)
            if (reason != null) put("reason", reason)
            if (version != null) put("version", version)
        })
    }

    // ── Agents' questions ───────────────────────────────────────────────────────────────────────

    suspend fun waitingAsks(): List<Ask> = decode<AsksReply>(call("/api/asks?status=waiting")).asks.filter { it.canAnswer }

    suspend fun answer(id: String, allow: Boolean, fromNotification: Boolean = false) {
        call("/api/asks/${path(id)}/answer", buildJsonObject {
            put("decision", if (allow) "allow" else "deny")
            if (fromNotification) put("via", "notification")
        })
    }

    /** One question as it is now, so a notification button can check it is still the one it showed. */
    suspend fun ask(id: String): Ask = decode(call("/api/asks/${path(id)}"))

    // ── Push (T-0355): Chamber only learns this phone's push token. Pushes carry no content. ───────

    /** 503 means the server has no Firebase key yet; the app just tries again later. */
    suspend fun registerPush(token: String, name: String) {
        call("/account/push", buildJsonObject {
            put("device", buildJsonObject {
                put("platform", "android")
                put("provider", "fcm")
                put("token", token)
                put("name", name)
            })
        })
    }

    /** Settings > Notifications > Send a test: is this push that test? */
    suspend fun pushTest(): PushTest = decode(call("/account/push/test"))

    suspend fun pushTestShown(nonce: String) {
        call("/account/push/test/shown", buildJsonObject { put("nonce", nonce) })
    }

    /** Ids come from Chamber, but never let one steer the URL. */
    private fun path(id: String): String {
        require(Regex("^[A-Za-z0-9-]{1,40}$").matches(id)) { "bad id" }
        return id
    }

    suspend fun me(): Me = decode(call("/api/me"))

    suspend fun rooms(): Map<String, Room> = decode<RoomsReply>(call("/api/rooms")).rooms

    suspend fun messages(room: String, since: Long? = null, limit: Int = 100): MessagesReply {
        val url = (CHAMBER_ORIGIN + "/api/messages").toHttpUrl().newBuilder()
            .addQueryParameter("room", room)
            .addQueryParameter("limit", limit.toString())
            .apply { if (since != null) addQueryParameter("since", since.toString()) }
            .build()
        return decode(call(url.encodedPath + "?" + url.encodedQuery))
    }

    suspend fun send(room: String, text: String, replyTo: String? = null): Message? = decode<PostReply>(
        call("/api/messages", buildJsonObject {
            put("room", room)
            put("text", text)
            if (replyTo != null) put("reply_to", replyTo)
        }),
    ).message

    private inline fun <reified T> decode(text: String): T = ChamberJson.decodeFromString(text)

    companion object {
        /** Lazy: the phone model only exists on a phone, not in plain unit tests. */
        val APP_HEADER: String by lazy {
            "android; " + listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { !it.isNullOrBlank() }.joinToString(" ").replace(Regex("[^\\x20-\\x7E]"), "").take(40)
        }

        fun isLocked(status: Int, body: String): Boolean = flag(status == 423, body, "sessionLocked")

        fun flag(statusMatches: Boolean, body: String, name: String): Boolean = statusMatches && runCatching {
            (ChamberJson.parseToJsonElement(body).jsonObject[name] as? JsonPrimitive)?.contentOrNull == "true"
        }.getOrDefault(false)

        /** Chamber answers errors as {error: "..."} written for people, so show that, never a raw code. */
        fun errorText(status: Int, body: String): String {
            val said = runCatching {
                (ChamberJson.parseToJsonElement(body).jsonObject["error"] as? JsonPrimitive)?.contentOrNull
            }.getOrNull()
            if (!said.isNullOrBlank()) return said
            return when (status) {
                401 -> "You're signed out. Sign in again."
                403 -> "Chamber didn't allow that."
                423 -> "Chamber is locked. Unlock it to continue."
                429 -> "Too many tries. Wait a minute."
                in 500..599 -> "Chamber had a problem. Try again in a moment."
                else -> "Chamber said no ($status)."
            }
        }
    }
}
