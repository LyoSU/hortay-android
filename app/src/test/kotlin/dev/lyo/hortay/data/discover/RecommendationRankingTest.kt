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
        )
        assertEquals(listOf(100L), ranked.map { it.chatId })
        assertEquals(listOf(1L, 3L), ranked.single().voters)
    }

    @Test
    fun `ten voting sources raise the bar to two votes`() {
        val nine = (1L..9L).associateWith { if (it == 1L) listOf(100L, 101L) else listOf(100L) }
        assertEquals(
            setOf(100L, 101L),
            rankRecommendations(nine, joined = nine.keys, hidden = emptySet()).map { it.chatId }.toSet(),
        )

        val ten = (1L..10L).associateWith { if (it == 1L) listOf(100L, 101L) else listOf(100L) }
        assertEquals(
            listOf(100L),
            rankRecommendations(ten, joined = ten.keys, hidden = emptySet()).map { it.chatId },
        )

        // A hidden 10th source does not count towards the threshold.
        assertEquals(
            setOf(100L, 101L),
            rankRecommendations(ten, joined = ten.keys, hidden = setOf(10L)).map { it.chatId }.toSet(),
        )
    }

    @Test
    fun `list is capped`() {
        val ranked = rankRecommendations(
            similarBySource = mapOf(1L to (100L until 200L).toList()),
            joined = setOf(1L),
            hidden = emptySet(),
        )
        assertEquals(RECOMMENDED_LIMIT, ranked.size)
        assertTrue(ranked.all { it.votes == 1 })
    }
}
