package dev.lyo.hortay.ui.channels

import dev.lyo.hortay.data.subscriptions.FollowedChannel
import dev.lyo.hortay.testutil.testPost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChannelSectionsTest {

    @Test
    fun `joined wins over followed, followed over other`() {
        assertEquals(ChannelSection.Joined, channelSection(1, joined = setOf(1), followed = setOf(1)))
        assertEquals(ChannelSection.NotJoined, channelSection(2, joined = setOf(1), followed = setOf(2)))
        assertEquals(ChannelSection.Other, channelSection(3, joined = setOf(1), followed = setOf(2)))
    }

    @Test
    fun `rows split into all three sections in display order, keeping row order`() {
        val followed = FollowedChannel(-2L, "f", "F", 0L, 0L, 0L, FollowedChannel.Status.Ok)
        val rows = channelSummaries(
            listOf(
                testPost(id = 1, chatId = -3L, date = 30),
                testPost(id = 1, chatId = -1L, date = 20),
                testPost(id = 1, chatId = -4L, date = 10),
            ),
            mapOf(-2L to followed),
        )
        val sections = partitionChannels(rows, joined = setOf(-1L, -4L), followed = setOf(-2L))
        assertEquals(ChannelSection.entries, sections.keys.toList())
        assertEquals(listOf(-1L, -4L), sections.getValue(ChannelSection.Joined).map { it.chatId })
        assertEquals(listOf(-2L), sections.getValue(ChannelSection.NotJoined).map { it.chatId })
        assertEquals(listOf(-3L), sections.getValue(ChannelSection.Other).map { it.chatId })
    }

    @Test
    fun `empty sections are still present`() {
        val sections = partitionChannels(emptyList(), emptySet(), emptySet())
        assertEquals(3, sections.size)
        sections.values.forEach { assertEquals(0, it.size) }
    }
}
