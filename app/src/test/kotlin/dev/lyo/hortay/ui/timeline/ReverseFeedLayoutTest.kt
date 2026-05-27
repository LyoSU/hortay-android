package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.FeedOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReverseFeedLayoutTest {

    // reverseLayout

    @Test
    fun `OldestUnreadFirst reverseLayout is true`() {
        assertEquals(true, FeedOrder.OldestUnreadFirst.reverseLayout)
    }

    @Test
    fun `Newest reverseLayout is false`() {
        assertEquals(false, FeedOrder.Newest.reverseLayout)
    }

    // shouldLoadOlder — fires near the older (high-index) edge

    @Test
    fun `shouldLoadOlder true when lastVisible is within threshold of total`() {
        assertEquals(true, shouldLoadOlder(firstVisible = 40, lastVisible = 99, total = 100, threshold = 5))
    }

    @Test
    fun `shouldLoadOlder true when lastVisible equals total minus threshold exactly`() {
        assertEquals(true, shouldLoadOlder(firstVisible = 0, lastVisible = 95, total = 100, threshold = 5))
    }

    @Test
    fun `shouldLoadOlder false when lastVisible is far from the older edge`() {
        assertEquals(false, shouldLoadOlder(firstVisible = 0, lastVisible = 10, total = 100, threshold = 5))
    }

    // guards

    @Test
    fun `shouldLoadOlder false when total is zero`() {
        assertEquals(false, shouldLoadOlder(firstVisible = 0, lastVisible = 0, total = 0, threshold = 5))
    }

    @Test
    fun `shouldLoadOlder false when lastVisible is negative`() {
        assertEquals(false, shouldLoadOlder(firstVisible = -1, lastVisible = -1, total = 100, threshold = 5))
    }
}
