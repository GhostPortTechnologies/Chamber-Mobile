package com.ghostporttechnologies.chamber;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AskRulesTest {

    private static boolean buttons(String command) {
        return AskRules.canAnswerFromNotification("Bash", "run this command: " + command, true, 2, 1);
    }

    private static String repeat(char c, int times) {
        return new String(new char[times]).replace('\0', c);
    }

    @Test
    public void readableCommandGetsButtons() {
        assertTrue(buttons("npm test -- --watch=false"));
        assertTrue(buttons("a\nb\nc\nd"));
        assertTrue(buttons(repeat('x', 300)));
    }

    @Test
    public void commandThatDoesNotFitHasToBeOpened() {
        assertFalse(buttons(repeat('x', 301)));
        assertFalse(buttons("a\nb\nc\nd\ne"));
        assertFalse(buttons("ls" + repeat(' ', 8) + "; rm -rf ~"));
        assertFalse(buttons("   "));
    }

    @Test
    public void hiddenCharactersHaveToBeOpened() {
        int[] hidden = { 0x202E, 0x2066, 0x200B, 0x00A0, 0x2028, 0x3164, 0x000D, 0x001B };
        for (int c : hidden) {
            String command = "ls " + new String(Character.toChars(c)) + " x";
            assertFalse(Integer.toHexString(c), buttons(command));
            assertEquals("ls � x", AskRules.visible(command, 300));
        }
    }

    @Test
    public void onlyLiveCommandsThePersonMayAnswer() {
        String ask = "run this command: ls";
        assertFalse(AskRules.canAnswerFromNotification("Edit", ask, true, 2, 1));
        assertFalse(AskRules.canAnswerFromNotification("Bash", ask, false, 2, 1));
        assertFalse(AskRules.canAnswerFromNotification("Bash", ask, true, 1, 1));
        assertFalse(AskRules.canAnswerFromNotification("Bash", "ls", true, 2, 1));
    }

    @Test
    public void fingerprintTellsQuestionsApart() {
        String one = AskRules.fingerprint("agent", "run this command: ls", 5);
        assertEquals(one, AskRules.fingerprint("agent", "run this command: ls", 5));
        assertNotEquals(one, AskRules.fingerprint("other", "run this command: ls", 5));
        assertNotEquals(one, AskRules.fingerprint("agent", "run this command: rm", 5));
        assertNotEquals(one, AskRules.fingerprint("agent", "run this command: ls", 6));
    }

    @Test
    public void idsCannotSteerTheUrl() {
        assertTrue(AskRules.validAskId("A-14"));
        assertFalse(AskRules.validAskId("A-14/../../x"));
        assertTrue(AskRules.validMessageId("mwr4hx7ck52r"));
        assertFalse(AskRules.validMessageId("x');alert(1)//"));
        assertFalse(AskRules.validMessageId("../settings"));
    }
}
