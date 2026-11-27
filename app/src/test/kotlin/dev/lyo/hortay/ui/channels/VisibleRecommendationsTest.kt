package dev.lyo.hortay.ui.channels

import dev.lyo.hortay.data.discover.DiscoverChannel
import dev.lyo.hortay.data.discover.RecommendedChannel
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VisibleRecommendationsTest {

    private fun rec(id: Long, isMember: Boolean = false) = RecommendedChannel(
        channel = DiscoverChannel(id, "c$id", "C$id", null, null, null, isMember),
        voters = persistentListOf(1L),
        topVoterTitle = null,
    )

    @Test
    fun `channels read without joining are never recommended`() {
        val shown = visibleRecommendations(listOf(rec(1), rec(2), rec(3)), emptySet(), emptySet(), followed = setOf(2))
        assertEquals(listOf(1L, 3L), shown.map { it.channel.chatId })
    }

    @Test
    fun `members drop unless still holding their confirmation, joined rows drop after it`() {
        val shown = visibleRecommendations(
            listOf(rec(1, isMember = true), rec(2, isMember = true), rec(3)),
            justJoined = setOf(3),
            holding = setOf(2),
            followed = emptySet(),
        )
        assertEquals(listOf(2L), shown.map { it.channel.chatId })
    }
}
