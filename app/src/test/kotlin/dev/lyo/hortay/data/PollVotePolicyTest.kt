package dev.lyo.hortay.data

import kotlinx.collections.immutable.toPersistentList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PollVotePolicyTest {

    private fun poll(vararg chosen: Int): PostContent.Poll {
        val options = (0..2).map { i ->
            PollOption(index = i, text = FormattedText.Empty, voterCount = 0, percent = 0, isChosen = i in chosen)
        }
        return PostContent.Poll(
            id = 1L,
            question = FormattedText.Empty,
            options = options.toPersistentList(),
            totalVotes = 0,
            isAnonymous = true,
            isClosed = false,
            kind = PollKind.Regular(allowsMultipleAnswers = false, allowsRevoting = true),
            closeDate = 0,
            openPeriod = 0,
            hasVoted = options.any { it.isChosen },
        )
    }

    private fun PostContent.Poll.chosen(): Set<Int> = options.filter { it.isChosen }.map { it.index }.toSet()

    @Test
    fun `failed retract restores the previous vote`() {
        val optimistic = PollVotePolicy.applyOptimistic(poll(1), emptySet())
        assertFalse(optimistic.hasVoted)

        val reverted = PollVotePolicy.clearPending(optimistic, revert = true)
        assertNotNull(reverted)
        assertEquals(setOf(1), reverted!!.chosen())
        assertTrue(reverted.hasVoted)
        assertTrue(reverted.options.none { it.isBeingChosen })
    }

    @Test
    fun `failed vote change restores the old choice`() {
        val optimistic = PollVotePolicy.applyOptimistic(poll(0), setOf(2))
        assertEquals(setOf(2), optimistic.chosen())

        val reverted = PollVotePolicy.clearPending(optimistic, revert = true)!!
        assertEquals(setOf(0), reverted.chosen())
    }

    @Test
    fun `failed fresh vote clears the selection`() {
        val reverted = PollVotePolicy.clearPending(PollVotePolicy.applyOptimistic(poll(), setOf(1)), revert = true)!!
        assertTrue(reverted.chosen().isEmpty())
        assertFalse(reverted.hasVoted)
    }

    @Test
    fun `successful retract keeps the cleared selection and drops the shimmer`() {
        val settled = PollVotePolicy.clearPending(PollVotePolicy.applyOptimistic(poll(1), emptySet()), revert = false)!!
        assertTrue(settled.chosen().isEmpty())
        assertTrue(settled.options.none { it.isBeingChosen })
    }

    @Test
    fun `nothing pending returns null`() {
        assertNull(PollVotePolicy.clearPending(poll(1), revert = true))
    }
}
