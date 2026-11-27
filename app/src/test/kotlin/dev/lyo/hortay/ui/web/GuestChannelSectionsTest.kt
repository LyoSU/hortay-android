package dev.lyo.hortay.ui.web

import dev.lyo.hortay.data.web.ChannelEntry
import dev.lyo.hortay.data.web.ChannelFetchStatus
import dev.lyo.hortay.data.web.WebChannelInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GuestChannelSectionsTest {

    private fun entry(username: String, subscribed: Boolean) = ChannelEntry(
        info = WebChannelInfo(username, username, "", null, null, false),
        isSubscribed = subscribed,
        lastFetchedAtMs = null,
        status = ChannelFetchStatus.Idle,
        error = null,
        retryAfterMs = null,
    )

    @Test
    fun `subscribed rows are following, the rest other, order kept`() {
        val sections = partitionGuestChannels(
            listOf(entry("a", true), entry("b", false), entry("c", true), entry("d", false)),
        )
        assertEquals(listOf("a", "c"), sections.following.map { it.info.username })
        assertEquals(listOf("b", "d"), sections.other.map { it.info.username })
    }
}
