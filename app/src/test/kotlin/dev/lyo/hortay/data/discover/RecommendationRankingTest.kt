package dev.lyo.hortay.data.discover

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecommendationRankingTest {

    @Test
    fun `candidates rank by votes and keep voters in source order`() {
        val ranked = rankRecommendations(
            similarBySource = linkedMapOf(
                1L to listOf(100L, 200L),
                2L to listOf(200L, 300L),
                3L to listOf(200L, 300L),
            ),
            joined = setOf(1L, 2L, 3L),
            hidden = emptySet(),
            votingSources = 3,
        )
        assertEquals(listOf(200L, 300L, 100L), ranked.map { it.chatId })
        assertEquals(listOf(1L, 2L, 3L), ranked.first().voters)
        assertEquals(3, ranked.first().votes)
    }

    @Test
    fun `equal votes break by subscribers then by id`() {
        val ranked = rankRecommendations(
            similarBySource = mapOf(1L to listOf(500L, 400L, 300L, 200L)),
            joined = setOf(1L),
            hidden = emptySet(),
            votingSources = 1,
            subscribers = mapOf(300L to 10, 400L to 5_000),
        )
        // Known counts first (desc), unknown after, ties by id.
        assertEquals(listOf(400L, 300L, 200L, 500L), ranked.map { it.chatId })
    }

    @Test
    fun `joined and excluded candidates are dropped`() {
        val ranked = rankRecommendations(
            similarBySource = mapOf(1L to listOf(100L, 200L, 300L)),
            joined = setOf(1L, 100L),
            hidden = emptySet(),
            votingSources = 1,
            excluded = setOf(300L),
        )
        assertEquals(listOf(200L), ranked.map { it.chatId })
    }

    @Test
    fun `channels hidden from the feed do not vote, archived ones do`() {
        // 2 is hidden from the feed; 3 is archived — archive is not a ranking input at all,
        // it is just another subscribed source.
        val ranked = rankRecommendations(
            similarBySource = linkedMapOf(
                1L to listOf(100L),
                2L to listOf(100L, 999L),
                3L to listOf(100L),
            ),
            joined = setOf(1L, 2L, 3L),
            hidden = setOf(2L),
            votingSources = 2,
        )
        assertEquals(listOf(100L), ranked.map { it.chatId })
        assertEquals(listOf(1L, 3L), ranked.single().voters)
    }

    @Test
    fun `ten voting sources raise the bar to two votes`() {
        val nine = (1L..9L).associateWith { if (it == 1L) listOf(100L, 101L) else listOf(100L) }
        assertEquals(
            setOf(100L, 101L),
            rankRecommendations(nine, joined = nine.keys, hidden = emptySet(), votingSources = 9)
                .map { it.chatId }.toSet(),
        )

        val ten = (1L..10L).associateWith { if (it == 1L) listOf(100L, 101L) else listOf(100L) }
        assertEquals(
            listOf(100L),
            rankRecommendations(ten, joined = ten.keys, hidden = emptySet(), votingSources = 10)
                .map { it.chatId },
        )
    }

    @Test
    fun `the vote bar follows the total voting sources, not how many are scanned`() {
        // A 50-channel user two sources into a scan: single votes are already noise, so
        // nothing shows yet — and nothing will vanish when the tenth source lands.
        val partial = linkedMapOf(1L to listOf(100L, 200L), 2L to listOf(100L))
        assertEquals(
            listOf(100L),
            rankRecommendations(partial, joined = partial.keys, hidden = emptySet(), votingSources = 50)
                .map { it.chatId },
        )
        // A 3-channel user with the same partial data keeps the single-vote row.
        assertEquals(
            setOf(100L, 200L),
            rankRecommendations(partial, joined = partial.keys, hidden = emptySet(), votingSources = 3)
                .map { it.chatId }.toSet(),
        )
    }

    @Test
    fun `list is capped`() {
        val ranked = rankRecommendations(
            similarBySource = mapOf(1L to (100L until 200L).toList()),
            joined = setOf(1L),
            hidden = emptySet(),
            votingSources = 1,
        )
        assertEquals(RECOMMENDED_LIMIT, ranked.size)
        assertTrue(ranked.all { it.votes == 1 })
    }
}
