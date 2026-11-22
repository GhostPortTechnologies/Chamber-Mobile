package com.ghostporttechnologies.chamber;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * What a notification may do with an agent's question. No Android classes here, so it is unit tested.
 *
 * Allow and Deny appear on a notification only when the person can read the whole command there. Anything longer,
 * padded, multi-screen or with invisible characters has to be opened in Chamber.
 */
final class AskRules {

    static final int MAX_COMMAND = 300;
    private static final int MAX_LINES = 4;
    private static final int MAX_BLANK_RUN = 8;
    private static final String PREFIX = "run this command: ";
    private static final Pattern ASK_ID = Pattern.compile("^A-[0-9]{1,6}$");
    private static final Pattern MESSAGE_ID = Pattern.compile("^[a-zA-Z0-9]{1,32}$");

    private AskRules() {}

    static boolean validAskId(String id) {
        return id != null && ASK_ID.matcher(id).matches();
    }

    static boolean validMessageId(String id) {
        return id != null && MESSAGE_ID.matcher(id).matches();
    }

    static String command(String summary) {
        return summary.startsWith(PREFIX) ? summary.substring(PREFIX.length()) : summary;
    }

    static boolean canAnswerFromNotification(String tool, String summary, boolean canAnswer, long expiresAt, long now) {
        if (!canAnswer || expiresAt <= now || !"Bash".equals(tool) || !summary.startsWith(PREFIX)) return false;
        String command = command(summary);
        if (command.trim().isEmpty() || command.length() > MAX_COMMAND) return false;
        int lines = 1;
        int blanks = 0;
        for (int i = 0; i < command.length(); ) {
            int c = command.codePointAt(i);
            i += Character.charCount(c);
            if (hidden(c)) return false;
            if (c == '\n' && ++lines > MAX_LINES) return false;
            blanks = (c == ' ' || c == '\t') ? blanks + 1 : 0;
            if (blanks >= MAX_BLANK_RUN) return false;
        }
        return true;
    }

    /** Text for display: cut to max, with characters that could hide or reorder text made visible. */
    static String visible(String text, int max) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            if (out.length() >= max) return out.append('…').toString();
            int c = text.codePointAt(i);
            i += Character.charCount(c);
            out.appendCodePoint(hidden(c) ? 0xFFFD : c);
        }
        return out.toString();
    }

    private static boolean hidden(int c) {
        if (c == '\n' || c == '\t' || c == ' ') return false;
        if (c == 0x115F || c == 0x1160 || c == 0x2800 || c == 0x3164 || c == 0xFFA0) return true; // blank-looking letters
        int type = Character.getType(c);
        return Character.isISOControl(c) || type == Character.FORMAT || type == Character.SPACE_SEPARATOR
            || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }

    /**
     * Question numbers restart in every workspace. An answer is sent only if this still matches, so an old
     * notification can't answer a different question that happens to have the same number.
     */
    static String fingerprint(String agent, String summary, long expiresAt) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest((agent.length() + ":" + agent + summary.length() + ":" + summary + expiresAt).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
