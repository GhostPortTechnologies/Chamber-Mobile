package com.ghostporttechnologies.chamber

import com.ghostporttechnologies.chamber.data.ChamberApi
import com.ghostporttechnologies.chamber.data.ChamberJson
import com.ghostporttechnologies.chamber.data.LoginReply
import com.ghostporttechnologies.chamber.data.MessagesReply
import com.ghostporttechnologies.chamber.data.RoomsReply
import com.ghostporttechnologies.chamber.data.SseEvent
import com.ghostporttechnologies.chamber.data.SseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataTest {

    private fun parse(vararg lines: String): List<SseEvent> {
        val parser = SseParser()
        return lines.mapNotNull { parser.line(it) }
    }

    @Test fun sseEventsAndHeartbeats() {
        val events = parse(": heartbeat", "", "event: change", "data: {\"kind\":\"messages\",\"room\":\"general\"}", "", "event: shutdown", "data: {}", "")
        assertEquals(listOf(SseEvent("change", "{\"kind\":\"messages\",\"room\":\"general\"}"), SseEvent("shutdown", "{}")), events)
    }

    @Test fun sseMultiLineDataAndDefaultName() {
        assertEquals(listOf(SseEvent("message", "a\nb")), parse("data: a", "data: b", ""))
        assertEquals(emptyList<SseEvent>(), parse("event: change", "")) // no data, no event
    }

    @Test fun loginNeedsSecondStep() {
        val reply = ChamberJson.decodeFromString<LoginReply>("""{"ok":true,"mfa":true,"mfaToken":"m1","totp":true,"passkey":false,"message":"Enter the code"}""")
        assertTrue(reply.mfa); assertEquals("m1", reply.mfaToken); assertNull(reply.token)
    }

    @Test fun aSecondStepThatDoesNotMentionTheCodeStillOffersIt() {
        // The web page shows the code box unless "totp" is false; the app must not read a missing field as "passkey only".
        val reply = ChamberJson.decodeFromString<LoginReply>("""{"ok":true,"mfa":true,"mfaToken":"m1"}""")
        assertNull(reply.totp)
        assertEquals(false, ChamberJson.decodeFromString<LoginReply>("""{"mfa":true,"mfaToken":"m1","totp":false}""").totp)
    }

    @Test fun loginDoneAndUnknownFieldsIgnored() {
        val reply = ChamberJson.decodeFromString<LoginReply>("""{"ok":true,"token":"t","account":{"username":"a","role":"owner","new":1},"future":[1]}""")
        assertEquals("t", reply.token); assertFalse(reply.mfa); assertEquals("a", reply.account?.username)
    }

    @Test fun messagesWithRepliesRolesAndMissingFields() {
        val reply = ChamberJson.decodeFromString<MessagesReply>(
            """{"messages":[{"id":"m1","username":"pi-claude","text":"hi","ts":5,"room":"general","reply_to":"m0","attachments":[{"id":"f","name":"a.png","mime":"image/png","size":3}],"reactions":{"👍":["x"]},"run":{"steps":[]}},
               {"id":"m2","ts":6,"deleted":true,"text":null}],"users":{"pi-claude":{"role":"ai","prefs":{}}}}""",
        )
        assertEquals("m0", reply.messages[0].replyTo)
        assertEquals("a.png", reply.messages[0].attachments.single().name)
        assertEquals("ai", reply.users["pi-claude"]?.role)
        assertTrue(reply.messages[1].deleted); assertEquals("", reply.messages[1].text)
    }

    @Test fun rooms() {
        val rooms = ChamberJson.decodeFromString<RoomsReply>("""{"rooms":{"general":{"name":"general","topic":"t"},"dm:a:b":{"name":"b","dm":true}}}""").rooms
        assertTrue(rooms.getValue("dm:a:b").dm); assertEquals("t", rooms.getValue("general").topic)
    }

    @Test fun errorsArePeoplesWords() {
        assertEquals("Wrong email/username or password.", ChamberApi.errorText(401, """{"error":"Wrong email/username or password."}"""))
        assertEquals("You're signed out. Sign in again.", ChamberApi.errorText(401, "not json"))
        assertEquals("Chamber is locked. Unlock it to continue.", ChamberApi.errorText(423, "{}"))
        assertEquals("Chamber had a problem. Try again in a moment.", ChamberApi.errorText(502, "<html>"))
    }
}
