package com.ghostporttechnologies.chamber.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRulesTest {

    @Test fun googleByDefaultWhenThePhoneHasIt() {
        assertEquals(Provider.Google, PushRules.provider(google = true, distributor = true, chosen = null))
        assertEquals(Provider.Google, PushRules.provider(google = true, distributor = false, chosen = null))
    }

    @Test fun unifiedPushWhenThereIsNoGoogle() {
        assertEquals(Provider.UnifiedPush, PushRules.provider(google = false, distributor = true, chosen = null))
        assertEquals(Provider.UnifiedPush, PushRules.provider(google = false, distributor = true, chosen = Provider.Google))
    }

    @Test fun thePersonsChoiceWinsWhileItWorks() {
        assertEquals(Provider.UnifiedPush, PushRules.provider(google = true, distributor = true, chosen = Provider.UnifiedPush))
        // ntfy uninstalled: back to Google rather than silence
        assertEquals(Provider.Google, PushRules.provider(google = true, distributor = false, chosen = Provider.UnifiedPush))
    }

    @Test fun nothingWithNeither() {
        assertNull(PushRules.provider(google = false, distributor = false, chosen = null))
        assertNull(PushRules.provider(google = false, distributor = false, chosen = Provider.UnifiedPush))
    }

    @Test fun onlyChambersExactWakeUpWakes() {
        assertTrue(PushRules.isWake(true, """{"kind":"wake"}""".toByteArray()))
        assertTrue(PushRules.isWake(true, """ { "kind" : "wake" } """.toByteArray()))
    }

    @Test fun aMessageThatDidNotDecryptWakesNothing() {
        // Anyone who knows an ntfy topic can post to it in plain text.
        assertFalse(PushRules.isWake(false, """{"kind":"wake"}""".toByteArray()))
    }

    @Test fun anythingElseWakesNothing() {
        listOf(
            "",
            "wake",
            "[]",
            """{"kind":"Wake"}""",
            """{"kind":"wake","text":"hi"}""",
            """{"kind":true}""",
            """{"kind":["wake"]}""",
            """{"other":"wake"}""",
            """{"kind":"wake"""",
            "{\"kind\":\"wake\"}" + " ".repeat(60),
        ).forEach { assertFalse(it, PushRules.isWake(true, it.toByteArray())) }
    }

    @Test fun testResultsSayWhatHappened() {
        assertEquals("Sent through UnifiedPush (ntfy.sh). It should show in a few seconds.", PushRules.testResult("ok", "", "UnifiedPush (ntfy.sh)"))
        assertTrue(PushRules.testResult("held", "", "").startsWith("Held back"))
        assertTrue(PushRules.testResult("gone", "", "").contains("gone"))
        assertEquals("Your push server didn't take it: 429 Too Many Requests", PushRules.testResult("error", "429 Too Many Requests", ""))
        assertEquals("Your push server didn't take it.", PushRules.testResult("error", "", ""))
    }

    @Test fun aPushServersReasonCannotHideText() {
        val said = PushRules.testResult("error", "bad‮evil", "")
        assertFalse(said.contains('‮'))
    }
}
