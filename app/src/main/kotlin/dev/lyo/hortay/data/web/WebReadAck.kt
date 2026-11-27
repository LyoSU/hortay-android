package dev.lyo.hortay.data.web

import dev.lyo.hortay.data.TimelinePost

/**
 * What the guest read-ack dwell does with a batch of posts that were read — shared by
 * the guest feed ([dev.lyo.hortay.ui.web.WebModeScaffold]) and a guest channel screen
 * ([dev.lyo.hortay.ui.web.WebChannelScreen]) so both report reads the same way:
 *
 *  1. Advance each channel's local read cursor to the highest seq in the batch
 *     ([WebRepository.markChannelRead], MAX-clamped) — the unread strip.
 *  2. Hand the read posts to [WebViewReporter], which counts them as channel views.
 *  3. Tell [onChannelRead] (the new-post notifications, [WebNotifyCoordinator.onChannelRead])
 *     how far the channel was read, so a notification about posts already read goes away.
 *
 * Deliberately NOT [WebRepository.markRead]: that flips `post.is_read`, and SQLDelight's
 * table-level invalidation would re-run the whole feed query on every scroll pause.
 */
class WebReadAck(
    private val repository: WebRepository,
    private val views: WebViewReporter,
    private val onChannelRead: suspend (username: String, seq: Long) -> Unit = { _, _ -> },
) {
    /**
     * @param channel the channel every post belongs to (a channel screen), or null to
     *   group by each post's own handle (the merged feed — web posts carry no real chat
     *   id, so the handle is recovered from [TimelinePost.senderHandle]).
     */
    suspend fun onRead(batch: List<TimelinePost>, channel: String? = null) {
        if (batch.isEmpty()) return
        val byChannel = if (channel != null) {
            mapOf(channel.lowercase() to batch)
        } else {
            batch.groupBy { it.senderHandle?.removePrefix("@")?.lowercase().orEmpty() }
        }
        for ((username, group) in byChannel) {
            if (username.isEmpty()) continue
            views.onPostsRead(group.map { "$username/${it.id}" })
            val seq = group.maxOf { it.id }
            repository.markChannelRead(username, seq)
            onChannelRead(username, seq)
        }
    }
}
