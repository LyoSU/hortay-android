package dev.lyo.hortay.data

import dev.lyo.hortay.data.RatePromptPolicy.MIN_AGE_MS
import dev.lyo.hortay.data.RatePromptPolicy.MIN_DISTINCT_DAYS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RatePromptPolicyTest {

    private val firstSeen = 1_700_000_000_000L

    private fun eligible(
        now: Long = firstSeen + MIN_AGE_MS,
        first: Long? = firstSeen,
        days: Int = MIN_DISTINCT_DAYS,
        shown: Boolean = false,
        fromPlay: Boolean = true,
    ) = RatePromptPolicy.isEligible(now, first, days, shown, fromPlay)

    @Test
    fun `thresholds are a week and five days`() {
        assertEquals(7L * 24 * 60 * 60 * 1000, MIN_AGE_MS)
        assertEquals(5, MIN_DISTINCT_DAYS)
    }

    @Test
    fun `exactly seven days and exactly five distinct days qualify`() {
        assertTrue(eligible())
    }

    @Test
    fun `one millisecond short of seven days does not qualify`() {
        assertFalse(eligible(now = firstSeen + MIN_AGE_MS - 1))
    }

    @Test
    fun `four distinct days do not qualify`() {
        assertFalse(eligible(days = MIN_DISTINCT_DAYS - 1))
    }

    @Test
    fun `a prompt already shown is never shown again`() {
        assertFalse(eligible(now = firstSeen + 100 * MIN_AGE_MS, days = 100, shown = true))
    }

    @Test
    fun `an install not from Google Play is never asked`() {
        assertFalse(eligible(fromPlay = false))
    }

    @Test
    fun `no recorded first launch is not eligible`() {
        assertFalse(eligible(first = null))
    }

    @Test
    fun `a clock set back before the first launch waits`() {
        assertFalse(eligible(now = firstSeen - MIN_AGE_MS))
    }

    @Test
    fun `a day is counted once`() {
        assertEquals(1, RatePromptPolicy.nextDayCount(count = 0, lastCountedDay = null, today = 19_000))
        assertEquals(3, RatePromptPolicy.nextDayCount(count = 3, lastCountedDay = 19_000, today = 19_000))
        assertEquals(4, RatePromptPolicy.nextDayCount(count = 3, lastCountedDay = 19_000, today = 19_001))
    }
}
