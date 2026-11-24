package com.ghostporttechnologies.chamber.push

import java.security.MessageDigest

/**
 * What a notification may do with an agent's question. No Android classes here, so it is unit tested.
 *
 * Allow and Deny appear on a notification only when the person can read the whole command there. Anything longer,
 * padded, multi-screen or with invisible characters has to be opened in Chamber.
 */
object AskRules {

    const val MAX_COMMAND = 300
    private const val MAX_LINES = 4
    private const val MAX_BLANK_RUN = 8
    private const val PREFIX = "run this command: "
    private val ASK_ID = Regex("^A-[0-9]{1,6}$")

    fun validAskId(id: String?): Boolean = id != null && ASK_ID.matches(id)

    fun command(summary: String): String = summary.removePrefix(PREFIX)

    fun canAnswerFromNotification(tool: String, summary: String, canAnswer: Boolean, expiresAt: Long, now: Long): Boolean {
        if (!canAnswer || expiresAt <= now || tool != "Bash" || !summary.startsWith(PREFIX)) return false
        val command = command(summary)
        if (command.isBlank() || command.length > MAX_COMMAND) return false
        var lines = 1
        var blanks = 0
        var i = 0
        while (i < command.length) {
            val c = command.codePointAt(i)
            i += Character.charCount(c)
            if (hidden(c)) return false
            if (c == '\n'.code && ++lines > MAX_LINES) return false
            blanks = if (c == ' '.code || c == '\t'.code) blanks + 1 else 0
            if (blanks >= MAX_BLANK_RUN) return false
        }
        return true
    }

    /** Text for display: cut to [max], with characters that could hide or reorder text made visible. */
    fun visible(text: String, max: Int): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (out.length >= max) return out.append('…').toString()
            val c = text.codePointAt(i)
            i += Character.charCount(c)
            out.appendCodePoint(if (hidden(c)) 0xFFFD else c)
        }
        return out.toString()
    }

    private fun hidden(c: Int): Boolean {
        if (c == '\n'.code || c == '\t'.code || c == ' '.code) return false
        if (c == 0x115F || c == 0x1160 || c == 0x2800 || c == 0x3164 || c == 0xFFA0) return true // blank-looking letters
        val type = Character.getType(c)
        return Character.isISOControl(c) || type == Character.FORMAT.toInt() || type == Character.SPACE_SEPARATOR.toInt() ||
            type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt()
    }

    /**
     * Question numbers restart in every workspace. An answer is sent only if this still matches, so an old
     * notification can't answer a different question that happens to have the same number.
     */
    fun fingerprint(agent: String, summary: String, expiresAt: Long): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${agent.length}:$agent${summary.length}:$summary$expiresAt".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
