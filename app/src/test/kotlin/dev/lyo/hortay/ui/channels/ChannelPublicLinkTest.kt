package dev.lyo.hortay.ui.channels

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChannelPublicLinkTest {

    @Test
    fun `public handle becomes a t_me link with or without the at sign`() {
        assertEquals("https://t.me/hortay", channelPublicLink("@hortay"))
        assertEquals("https://t.me/hortay", channelPublicLink("hortay"))
    }

    @Test
    fun `channel without a handle has no link`() {
        assertNull(channelPublicLink(null))
        assertNull(channelPublicLink(""))
        assertNull(channelPublicLink("@"))
    }
}
