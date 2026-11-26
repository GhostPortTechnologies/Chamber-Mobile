package com.ghostporttechnologies.chamber.data

import com.ghostporttechnologies.chamber.push.AskRules
import java.time.Instant

/**
 * The Tickets tab as a queue (T-0318): what needs the person's decision comes first, in the order that unblocks the
 * most work; their own to-dos are kept apart from approvals. Approving stays inside an opened ticket, one at a time.
 */
object TicketQueue {

    data class Queue(
        /** Proposed: waiting for someone to approve or send back. */
        val decide: List<Ticket>,
        /** Approved or in progress, for a person to do. */
        val todo: List<Ticket>,
        /** Approved or in progress, for an agent. */
        val agents: List<Ticket>,
        val closed: List<Ticket>,
    )

    private val OPEN = setOf("approved", "in_progress")

    /** critical, high, medium/normal, low; anything else counts as normal. */
    fun rank(priority: String): Int = when (priority.lowercase()) {
        "critical" -> 0
        "high" -> 1
        "low" -> 3
        else -> 2
    }

    /** An agent starts as soon as it's approved, so those come first; then priority; then whatever has waited longest. */
    val urgency: Comparator<Ticket> = compareBy<Ticket>(
        { if (it.doneBy == "agent") 0 else 1 },
        { rank(it.priority) },
        { it.proposedAt.ifBlank { "~" } }, // ISO times sort as text; a missing one goes last
        { it.id },
    )

    fun arrange(tickets: List<Ticket>): Queue = Queue(
        decide = tickets.filter { it.status == "proposed" }.sortedWith(urgency),
        todo = tickets.filter { it.status in OPEN && it.doneBy == "human" }
            .sortedWith(compareBy<Ticket>({ rank(it.priority) }, { it.proposedAt.ifBlank { "~" } }, { it.id })),
        agents = tickets.filter { it.status in OPEN && it.doneBy != "human" }
            .sortedWith(compareBy<Ticket>({ if (it.status == "in_progress") 0 else 1 }).thenByDescending { it.id }),
        closed = tickets.filter { it.status == "done" || it.status == "rejected" }.sortedByDescending { it.id },
    )

    /** One line on what it does: the body's first line of text, without Markdown marks, hidden characters shown. */
    fun summary(body: String, max: Int = 140): String {
        val line = body.lineSequence()
            .map { it.trim().trimStart('#', '>', '-', '*', ' ').replace("**", "").replace("`", "").trim() }
            .firstOrNull { it.isNotEmpty() } ?: return ""
        return AskRules.visible(line.replace(Regex("\\s+"), " "), max)
    }

    /** How long it has waited, in words; null when the time is missing or in the future. */
    fun waited(proposedAt: String, now: Long): String? {
        val at = runCatching { Instant.parse(proposedAt).toEpochMilli() }.getOrNull() ?: return null
        val minutes = (now - at) / 60_000
        return when {
            minutes < 0 -> null
            minutes < 1 -> "just now"
            minutes < 60 -> "waiting $minutes min"
            minutes < 48 * 60 -> "waiting ${minutes / 60} h"
            else -> "waiting ${minutes / (24 * 60)} days"
        }
    }
}
