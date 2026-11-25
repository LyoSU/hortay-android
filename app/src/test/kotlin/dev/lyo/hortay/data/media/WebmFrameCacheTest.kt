package dev.lyo.hortay.data.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// Pure-Kotlin stand-in so we can build non-empty frame lists without the Android bitmap backend.
// WebmFrameCache only reads frames.size for its byte accounting, never the pixels.
private val fakeFrame = object : ImageBitmap {
    override val width = 1
    override val height = 1
    override val config = ImageBitmapConfig.Argb8888
    override val colorSpace: ColorSpace = ColorSpaces.Srgb
    override val hasAlpha = true
    override fun prepareToDraw() { /* no-op fake */ }
    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) { /* no-op fake — the cache never reads pixels, only frames.size */ }
}

class WebmFrameCacheTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun evictsOldestOverBudget() = runTest {
        val frames = 8
        val side = 64
        val entryBytes = frames.toLong() * side * side * 4 // 131_072
        // Budget holds one entry but not two, so a third observe must evict down to one.
        val cache = WebmFrameCache(
            scope = this,
            maxBytes = entryBytes + entryBytes / 2,
            decodeDispatcher = StandardTestDispatcher(testScheduler),
        ) { _, w, h -> DecodedWebm(List(frames) { fakeFrame }, IntArray(frames) { 33 }, w, h) }

        repeat(3) { i -> cache.observe(WebmFrameCache.Key("s$i", side, side), "/p$i.webm") }
        advanceUntilIdle()

        // Exactly one entry survives — proves eviction actually ran (not a vacuous <= check).
        assertEquals(entryBytes, cache.currentBytes())
    }

    // Contract WebmAlphaImage relies on: an on-screen observer's flow goes null on eviction, and a
    // fresh observe() of the same key decodes again instead of returning the dead flow.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun reObserveAfterEvictionReDecodes() = runTest {
        val frames = 8
        val side = 64
        val entryBytes = frames.toLong() * side * side * 4
        var decodes = 0
        val cache = WebmFrameCache(
            scope = this,
            maxBytes = entryBytes + entryBytes / 2,
            decodeDispatcher = StandardTestDispatcher(testScheduler),
        ) { _, w, h ->
            decodes++
            DecodedWebm(List(frames) { fakeFrame }, IntArray(frames) { 33 }, w, h)
        }
        val a = WebmFrameCache.Key("a", side, side)
        val first = cache.observe(a, "/a.webm")
        advanceUntilIdle()
        assertNotNull(first.value)

        cache.observe(WebmFrameCache.Key("b", side, side), "/b.webm")
        advanceUntilIdle()
        assertNull(first.value, "evicted entry must null its observed flow")

        val second = cache.observe(a, "/a.webm")
        advanceUntilIdle()
        assertNotNull(second.value)
        assertEquals(3, decodes)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun clearDropsEntriesAndNullsObservers() = runTest {
        val frames = 4
        val side = 16
        val cache = WebmFrameCache(
            scope = this,
            decodeDispatcher = StandardTestDispatcher(testScheduler),
        ) { _, w, h -> DecodedWebm(List(frames) { fakeFrame }, IntArray(frames) { 33 }, w, h) }
        val key = WebmFrameCache.Key("1", side, side)
        val flow = cache.observe(key, "/1.webm")
        advanceUntilIdle()
        assertNotNull(flow.value)

        cache.clear()

        assertNull(flow.value, "an observer of a cleared entry must see null")
        assertEquals(0L, cache.currentBytes())
        // Same TDLib file id after logout = a different file: it must decode afresh.
        val again = cache.observe(key, "/other-account/1.webm")
        assertNull(again.value)
        advanceUntilIdle()
        assertNotNull(again.value)
    }

    // A decode that straddles clear() (logout) must not publish the previous account's frames.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun clearDiscardsInFlightDecode() = runTest {
        val frames = 4
        val side = 16
        val paths = mutableListOf<String>()
        val cache = WebmFrameCache(
            scope = this,
            decodeDispatcher = StandardTestDispatcher(testScheduler),
        ) { path, w, h ->
            paths += path
            DecodedWebm(List(frames) { fakeFrame }, IntArray(frames) { 33 }, w, h)
        }
        val key = WebmFrameCache.Key("1", side, side)
        val stale = cache.observe(key, "/a/1.webm")
        cache.clear()
        advanceUntilIdle()

        assertNull(stale.value, "a stale decode must not reach its old observer")
        assertEquals(0L, cache.currentBytes())

        val fresh = cache.observe(key, "/b/1.webm")
        advanceUntilIdle()
        assertNotNull(fresh.value)
        assertEquals(listOf("/a/1.webm", "/b/1.webm"), paths)
        assertEquals(frames.toLong() * side * side * 4, cache.currentBytes())
    }
}
