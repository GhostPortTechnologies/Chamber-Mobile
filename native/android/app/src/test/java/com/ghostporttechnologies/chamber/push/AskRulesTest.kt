package com.ghostporttechnologies.chamber.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskRulesTest {

    private fun buttons(command: String) = AskRules.canAnswerFromNotification("Bash", "run this command: $command", true, 2, 1)

    @Test fun readableCommandGetsButtons() {
        assertTrue(buttons("npm test -- --watch=false"))
        assertTrue(buttons("a\nb\nc\nd"))
        assertTrue(buttons("x".repeat(300)))
    }

    @Test fun commandThatDoesNotFitHasToBeOpened() {
        assertFalse(buttons("x".repeat(301)))
        assertFalse(buttons("a\nb\nc\nd\ne"))
        assertFalse(buttons("ls" + " ".repeat(8) + "; rm -rf ~"))
        assertFalse(buttons("   "))
    }

    @Test fun hiddenCharactersHaveToBeOpened() {
        for (c in intArrayOf(0x202E, 0x2066, 0x200B, 0x00A0, 0x2028, 0x3164, 0x000D, 0x001B)) {
            val command = "ls " + String(Character.toChars(c)) + " x"
            assertFalse(Integer.toHexString(c), buttons(command))
            assertEquals("ls � x", AskRules.visible(command, 300))
        }
    }

    @Test fun onlyLiveCommandsThePersonMayAnswer() {
        val ask = "run this command: ls"
        assertFalse(AskRules.canAnswerFromNotification("Edit", ask, true, 2, 1))
        assertFalse(AskRules.canAnswerFromNotification("Bash", ask, false, 2, 1))
        assertFalse(AskRules.canAnswerFromNotification("Bash", ask, true, 1, 1))
        assertFalse(AskRules.canAnswerFromNotification("Bash", "ls", true, 2, 1))
    }

    @Test fun fingerprintTellsQuestionsApart() {
        val one = AskRules.fingerprint("agent", "run this command: ls", 5)
        assertEquals(64, one.length)
        assertEquals(one, AskRules.fingerprint("agent", "run this command: ls", 5))
        assertNotEquals(one, AskRules.fingerprint("other", "run this command: ls", 5))
        assertNotEquals(one, AskRules.fingerprint("agent", "run this command: rm", 5))
        assertNotEquals(one, AskRules.fingerprint("agent", "run this command: ls", 6))
    }

    @Test fun idsCannotSteerTheUrl() {
        assertTrue(AskRules.validAskId("A-14"))
        assertFalse(AskRules.validAskId("A-14/../../x"))
        assertFalse(AskRules.validAskId(null))
    }

    @Test fun visibleCutsLongText() {
        assertEquals("abc…", AskRules.visible("abcdef", 3))
    }
}
