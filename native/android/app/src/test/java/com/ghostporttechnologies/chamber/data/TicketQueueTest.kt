package com.ghostporttechnologies.chamber.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class TicketQueueTest {

    private fun t(id: String, status: String = "proposed", priority: String = "normal", doneBy: String? = "human", at: String = "2026-11-20T10:00:00.000Z") =
        Ticket(id = id, status = status, priority = priority, doneBy = doneBy, proposedAt = at)

    @Test fun approvalsThatLetAnAgentStartComeFirst() {
        val q = TicketQueue.arrange(listOf(t("T-1", priority = "critical"), t("T-2", doneBy = "agent", priority = "low")))
        assertEquals(listOf("T-2", "T-1"), q.decide.map { it.id })
    }

    @Test fun thenPriorityThenLongestWaiting() {
        val q = TicketQueue.arrange(listOf(
            t("T-1", priority = "low", at = "2026-11-01T00:00:00.000Z"),
            t("T-2", priority = "medium", at = "2026-11-25T00:00:00.000Z"),
            t("T-3", priority = "high", at = "2026-11-26T00:00:00.000Z"),
            t("T-4", priority = "normal", at = "2026-11-13T00:00:00.000Z"),
            t("T-5", priority = "critical", at = "2026-11-26T00:00:00.000Z"),
        ))
        assertEquals(listOf("T-5", "T-3", "T-4", "T-2", "T-1"), q.decide.map { it.id })
    }

    @Test fun aMissingTimeGoesLast() {
        val q = TicketQueue.arrange(listOf(t("T-1", at = ""), t("T-2", at = "2026-11-26T00:00:00.000Z")))
        assertEquals(listOf("T-2", "T-1"), q.decide.map { it.id })
    }

    @Test fun toDosAreKeptApartFromApprovalsAndAgentsWork() {
        val q = TicketQueue.arrange(listOf(
            t("T-1"),
            t("T-2", status = "approved"),
            t("T-3", status = "in_progress", doneBy = "agent"),
            t("T-4", status = "approved", doneBy = null),
            t("T-5", status = "done"),
            t("T-6", status = "rejected", doneBy = "agent"),
        ))
        assertEquals(listOf("T-1"), q.decide.map { it.id })
        assertEquals(listOf("T-2"), q.todo.map { it.id })
        assertEquals(listOf("T-3", "T-4"), q.agents.map { it.id })
        assertEquals(listOf("T-6", "T-5"), q.closed.map { it.id })
    }

    @Test fun unknownPrioritiesCountAsNormal() {
        assertEquals(TicketQueue.rank("normal"), TicketQueue.rank("whatever"))
        assertEquals(TicketQueue.rank("medium"), TicketQueue.rank("normal"))
        assertEquals(1, TicketQueue.rank("HIGH"))
    }

    @Test fun summaryIsTheFirstLineOfText() {
        assertEquals("Today every alert goes through Google.", TicketQueue.summary("\n\n## Today every **alert** goes through `Google`.\nMore."))
        assertEquals("A list item", TicketQueue.summary("- A list item"))
        assertEquals("", TicketQueue.summary("  \n "))
        assertEquals(141, TicketQueue.summary("x".repeat(500)).length) // 140 and an ellipsis
    }

    @Test fun summaryShowsHiddenCharacters() {
        assertFalse(TicketQueue.summary("ok‮hidden").contains('‮'))
    }

    @Test fun waitedInWords() {
        val at = "2026-11-20T10:00:00.000Z"
        val start = Instant.parse(at).toEpochMilli()
        assertEquals("just now", TicketQueue.waited(at, start + 30_000))
        assertEquals("waiting 12 min", TicketQueue.waited(at, start + 12 * 60_000))
        assertEquals("waiting 5 h", TicketQueue.waited(at, start + 5 * 3_600_000))
        assertEquals("waiting 13 days", TicketQueue.waited(at, start + 13 * 86_400_000L))
        assertNull(TicketQueue.waited("", start))
        assertNull(TicketQueue.waited(at, start - 60_000))
    }
}
