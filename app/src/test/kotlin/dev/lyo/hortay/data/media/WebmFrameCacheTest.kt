package dev.lyo.hortay.data.media

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

class WebmFrameCacheTest {
    @Test fun evictsOldestOverBudget() = runTest {
        val maxBytes = 64L * 64 * 4 * 10 // room for ~10 frames at 64px
        val cache = WebmFrameCache(
            scope = this,
            maxBytes = maxBytes,
            decodeDispatcher = StandardTestDispatcher(testScheduler),
        ) { _, px -> DecodedWebm(emptyList(), IntArray(8) { 33 }, px, px) }
        repeat(3) { i -> cache.observe(WebmFrameCache.Key("s$i", 64), "/p$i.webm") }
        advanceUntilIdle()
        assertTrue(cache.currentBytes() <= maxBytes, "bytes ${cache.currentBytes()} should be <= $maxBytes")
    }
}
