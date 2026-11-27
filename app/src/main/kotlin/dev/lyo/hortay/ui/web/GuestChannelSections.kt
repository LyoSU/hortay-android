package dev.lyo.hortay.ui.web

import dev.lyo.hortay.data.web.ChannelEntry

/**
 * Guest Channels tab split: channels the reader follows (`is_subscribed = 1`) and the rest
 * of `web.db`'s channel table — channels left after unsubscribing, or only seen through a
 * forward or a link. Order within each list is kept.
 */
internal data class GuestChannelSections(val following: List<ChannelEntry>, val other: List<ChannelEntry>)

internal fun partitionGuestChannels(entries: List<ChannelEntry>): GuestChannelSections {
    val (following, other) = entries.partition { it.isSubscribed }
    return GuestChannelSections(following, other)
}
