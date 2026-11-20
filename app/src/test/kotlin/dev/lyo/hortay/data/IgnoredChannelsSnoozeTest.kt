package dev.lyo.hortay.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IgnoredChannelsSnoozeTest {

    @Test
    fun `a snoozed channel is hidden until its time runs out`() {
        val snoozes = mapOf(7L to 1_000L)
        assertEquals(setOf(1L, 7L), IgnoredChannelsStore.hiddenAt(setOf(1L), snoozes, at = 999L))
        assertEquals(setOf(1L), IgnoredChannelsStore.hiddenAt(setOf(1L), snoozes, at = 1_000L))
    }

    @Test
    fun `a permanent hide outlives a snooze of the same channel`() {
        assertEquals(setOf(7L), IgnoredChannelsStore.hiddenAt(setOf(7L), mapOf(7L to 10L), at = 500L))
    }

    @Test
    fun `snoozes survive an encode and parse round trip, negative chat ids included`() {
        val snoozes = mapOf(-1001234567890L to 1_700_000_000_000L, 42L to 5L)
        assertEquals(snoozes, IgnoredChannelsStore.parseSnoozes(IgnoredChannelsStore.encodeSnoozes(snoozes)))
    }

    @Test
    fun `malformed entries are skipped`() {
        assertEquals(mapOf(3L to 9L), IgnoredChannelsStore.parseSnoozes(setOf("3:9", "junk", "4:", ":5", "")))
        assertEquals(emptyMap<Long, Long>(), IgnoredChannelsStore.parseSnoozes(null))
    }
}
