package com.ghostporttechnologies.chamber.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException

/** One server-sent event, as Chamber's /api/events writes them. */
data class SseEvent(val name: String, val data: String)

/** Splits an event stream into events. Comment lines (heartbeats) and unknown fields are skipped. */
class SseParser {
    private var name = "message"
    private val data = StringBuilder()

    /** Feed one line without its newline; returns an event when a blank line ends one. */
    fun line(line: String): SseEvent? {
        if (line.isEmpty()) {
            if (data.isEmpty()) return null.also { name = "message" }
            val event = SseEvent(name, data.toString())
            name = "message"
            data.setLength(0)
            return event
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> name = value
            "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
        }
        return null
    }
}

/**
 * Chamber's live "something changed" stream. It reconnects by itself (Chamber restarts during releases), and
 * the screens fetch what changed, so nothing is read from the stream except which room to refresh.
 */
fun ChamberApi.changes(): Flow<ChangeEvent> = flow {
    var wait = 1_000L
    while (true) {
        try {
            http.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
                .newCall(request("/api/events").header("Accept", "text/event-stream").build()).execute().use { response ->
                    if (response.code == 401) throw ChamberException(401, "You're signed out. Sign in again.")
                    if (response.code == 423) throw ChamberException(423, "Chamber is locked on this phone.", locked = true)
                    val source = response.body?.source() ?: return@use
                    wait = 1_000L
                    val parser = SseParser()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        val event = parser.line(line) ?: continue
                        if (event.name == "change") {
                            runCatching { ChamberJson.decodeFromString<ChangeEvent>(event.data) }.getOrNull()?.let { emit(it) }
                        }
                    }
                }
        } catch (e: IOException) {
            // Dropped connection or a restart: try again below.
        }
        delay(wait)
        wait = (wait * 2).coerceAtMost(30_000L)
    }
}.flowOn(Dispatchers.IO)
