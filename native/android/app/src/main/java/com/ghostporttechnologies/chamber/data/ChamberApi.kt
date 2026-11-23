package com.ghostporttechnologies.chamber.data

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
class ChamberException(val status: Int, override val message: String) : Exception(message) {
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
        val builder = Request.Builder().url(CHAMBER_ORIGIN + path).header("Accept", "application/json")
        tokens()?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private suspend fun call(path: String, body: JsonObject? = null): String = withContext(Dispatchers.IO) {
        val request = request(path).apply { if (body != null) post(body.toString().toRequestBody(json)) }.build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) text else throw ChamberException(response.code, errorText(response.code, text))
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
