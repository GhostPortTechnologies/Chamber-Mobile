package com.ghostporttechnologies.chamber.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Only the fields the app uses; Chamber can add more without breaking the app. */
val ChamberJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable
data class Account(val username: String = "", val role: String = "", val email: String = "")

@Serializable
data class LoginReply(
    val ok: Boolean = false,
    val token: String? = null,
    val account: Account? = null,
    val mfa: Boolean = false,
    val mfaToken: String? = null,
    val totp: Boolean = false,
    val passkey: Boolean = false,
    val message: String? = null,
)

@Serializable
data class Me(val username: String, val role: String = "human")

@Serializable
data class Room(val name: String = "", val topic: String = "", val dm: Boolean = false)

@Serializable
data class RoomsReply(val rooms: Map<String, Room> = emptyMap())

@Serializable
data class Attachment(val id: String = "", val name: String = "", val mime: String = "", val size: Long = 0)

@Serializable
data class Message(
    val id: String,
    val username: String = "",
    val text: String = "",
    val ts: Long = 0,
    val room: String = "",
    @SerialName("reply_to") val replyTo: String? = null,
    val type: String = "message",
    val deleted: Boolean = false,
    val edited: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val reactions: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class UserInfo(val role: String = "human")

@Serializable
data class MessagesReply(
    val messages: List<Message> = emptyList(),
    val users: Map<String, UserInfo> = emptyMap(),
    val more: Boolean = false,
)

@Serializable
data class PostReply(val ok: Boolean = false, val message: Message? = null)

@Serializable
data class ChangeEvent(val kind: String = "", val room: String? = null, val from: String? = null)
