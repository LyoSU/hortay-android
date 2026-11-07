package dev.lyo.hortay.data.posts

import dev.lyo.hortay.data.MessageContentMapper
import dev.lyo.hortay.data.TimelinePost
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coalescing buffer for `UpdateMessageInteractionInfo`, keyed by `(chatId, messageId)`.
 *
 * On busy days these updates arrive in dozens-per-second bursts for *every* channel in
 * the user's list, not just visible ones. Applying each one as its own O(N)
 * `_posts.update` cost ~50 MB/s of garbage at 1000 posts × 50 events/s, so the feed
 * buffers them and applies a whole window in one pass ([applyInteractionInfo]).
 *
 * Inserts go through `merge`, not `put` (ARCHITECTURE.md → "Interaction-buffer
 * merge-on-put"): TDLib often emits two back-to-back updates for one message — fresh
 * views/replies with `reactions = null` ("see your local copy"), then the new
 * `reactions`. A naive `put` lets the null-reactions heartbeat overwrite the reaction
 * change inside one coalesce window. The merge keeps the latest non-null value per field.
 *
 * Updates with a null `interactionInfo` never reach here — the caller drops them (they
 * were a no-op in the per-field handler, and `ConcurrentHashMap` forbids null values).
 */
internal class InteractionInfoBuffer {
    private val pending = ConcurrentHashMap<Pair<Long, Long>, TdApi.MessageInteractionInfo>()
    private val flushScheduled = AtomicBoolean(false)

    /**
     * Merge [info] into the buffer. Returns `true` when this call opened a new window —
     * the caller schedules exactly one [drain] for it.
     */
    fun offer(chatId: Long, messageId: Long, info: TdApi.MessageInteractionInfo): Boolean {
        pending.merge(chatId to messageId, info, ::mergeInteractionInfo)
        return flushScheduled.compareAndSet(false, true)
    }

    /**
     * Close the window and take everything buffered. An update that lands after the
     * window closes opens a new one ([offer] returns `true` again), so nothing is lost:
     * entries are removed one by one, never wholesale.
     */
    fun drain(): Map<Pair<Long, Long>, TdApi.MessageInteractionInfo> {
        flushScheduled.set(false)
        if (pending.isEmpty()) return emptyMap()
        val drained = HashMap<Pair<Long, Long>, TdApi.MessageInteractionInfo>()
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            drained[e.key] = e.value
            it.remove()
        }
        return drained
    }

    fun clear() {
        pending.clear()
        flushScheduled.set(false)
    }
}

/** Null-preserving, monotonic merge of two updates for the same message. */
internal fun mergeInteractionInfo(
    existing: TdApi.MessageInteractionInfo,
    incoming: TdApi.MessageInteractionInfo,
): TdApi.MessageInteractionInfo = TdApi.MessageInteractionInfo().apply {
    // viewCount is monotonic per Telegram; take max so a slightly-stale heartbeat
    // can't downgrade a fresher value already in the buffer.
    viewCount = maxOf(existing.viewCount, incoming.viewCount)
    // forwardCount: TDLib emits 0 to mean "not changed" in heartbeats too,
    // so keep the prior non-zero value when the incoming says zero.
    forwardCount = if (incoming.forwardCount != 0) incoming.forwardCount else existing.forwardCount
    // reactions / replyInfo: null in the incoming update means "see your local
    // copy" — fall through to the existing buffered value, NOT overwrite it.
    reactions = incoming.reactions ?: existing.reactions
    replyInfo = incoming.replyInfo ?: existing.replyInfo
}

/**
 * Apply a drained window to the feed in one pass. Pure function of its inputs — safe
 * inside the `_posts.update {}` CAS loop.
 *
 * Album-aware: an update may target ANY member of a merged album, while `post.id` is the
 * anchor (oldest member). A `(chatId, memberId) → index` map covers the anchor and every
 * `albumMessageIds` entry, so each event dispatches in O(1). Without it, views /
 * reactions / comment counts for non-anchor members were dropped — reactions never
 * appeared on photo albums, because TDLib fills `MessageReactions` only through these
 * updates after the initial `GetChatHistory` (which returns `reactions = null`).
 */
internal fun applyInteractionInfo(
    current: PersistentList<TimelinePost>,
    drained: Map<Pair<Long, Long>, TdApi.MessageInteractionInfo>,
): PersistentList<TimelinePost> {
    if (drained.isEmpty()) return current
    return current.mutate { list ->
        val byMessageId = HashMap<Pair<Long, Long>, Int>(list.size * 2)
        for (i in list.indices) {
            val post = list[i]
            byMessageId[post.chatId to post.id] = i
            for (memberId in post.albumMessageIds) {
                if (memberId != post.id) byMessageId[post.chatId to memberId] = i
            }
        }
        for ((key, info) in drained) {
            val idx = byMessageId[key] ?: continue
            val post = list[idx]
            list[idx] = post.copy(
                // Max instead of overwrite: every album member can fire its own update
                // against this anchor, and a member's count can lag (TDLib catching up
                // after a reconnect). View counts never decrease, so the max — against
                // the card AND across the burst — never downgrades a shown number.
                views = maxOf(post.views, info.viewCount),
                // Monotonic like views; the buffer already kept the prior non-zero value
                // when a heartbeat reported 0 (see [mergeInteractionInfo]).
                forwardCount = maxOf(post.forwardCount, info.forwardCount),
                // Null inner field = "not recomputed". Per tdlib/td#2312 only the first
                // album member carries non-null reactions / replyInfo, so null-preserve is
                // what keeps the other members from blanking the merged card's aggregate.
                reactions = info.reactions?.let(MessageContentMapper::mapReactions) ?: post.reactions,
                commentCount = info.replyInfo?.replyCount ?: post.commentCount,
            )
        }
    }
}
