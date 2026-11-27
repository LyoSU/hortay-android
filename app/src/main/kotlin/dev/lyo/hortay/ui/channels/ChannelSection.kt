package dev.lyo.hortay.ui.channels

/**
 * The Channels tab's sections in signed-in mode. [Joined]: the chat is in the main or
 * archive chat list. [NotJoined]: read without joining (the followed store). [Other]: a
 * channel the app only met through updates — a forward, an opened preview — that is
 * neither; it sits last and starts collapsed.
 */
internal enum class ChannelSection { Joined, NotJoined, Other }

/** A joined channel still in the followed store is mid hand-off and already counts as joined. */
internal fun channelSection(chatId: Long, joined: Set<Long>, followed: Set<Long>): ChannelSection = when (chatId) {
    in joined -> ChannelSection.Joined
    in followed -> ChannelSection.NotJoined
    else -> ChannelSection.Other
}

/**
 * [rows] split by [channelSection], each section keeping the rows' order; every section is
 * present (possibly empty), in display order.
 */
internal fun partitionChannels(
    rows: List<ChannelSummary>,
    joined: Set<Long>,
    followed: Set<Long>,
): Map<ChannelSection, List<ChannelSummary>> {
    val bySection = rows.groupBy { channelSection(it.chatId, joined, followed) }
    return ChannelSection.entries.associateWith { bySection[it].orEmpty() }
}
