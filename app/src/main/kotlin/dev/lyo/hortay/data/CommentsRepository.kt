package dev.lyo.hortay.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.shareIn
import org.drinkless.tdlib.TdApi
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Live discussion-thread feed for a channel post. Emits a [ThreadState] that updates as new
 * comments arrive, reactions/views change, or messages get deleted upstream — without ever
 * requiring the consumer to refetch.
 *
 * Mapping is delegated to a shared [MessageMapper] so author / forward / reply preview
 * resolution + caches are common with [PostsRepository]. Each comment is mapped to a
 * [TimelinePost] (the same model channel posts use) — channel posts and discussion
 * comments are technically the same Telegram message kind with a small set of contextual
 * extras, and one mapper / one model means we don't duplicate content rendering, sender
 * resolution or update plumbing for what is essentially the same thing.
 *
 * Caching strategy (mirrors what TDesktop / Telegram-X do for chat re-entry):
 *   - Anchor resolution `(chatId, anchorId) → (threadChatId, rootId)` is permanent for
 *     the lifetime of the repo. The mapping doesn't change for a given post during a
 *     session, and a re-probe via [TdApi.GetMessageProperties] + [TdApi.GetMessageThread]
 *     is wasted work even when answered from the local TDLib cache.
 *   - The thread itself is shared between subscribers via [shareIn] with a 30-second
 *     `WhileSubscribed` linger. Within that window, a back-out + re-entry to the comments
 *     overlay reuses the live flow — no second history fetch, no second update fan-in,
 *     and the user sees their comment list instantly.
 *   - The cache map is bounded by an LRU of [MAX_CACHED_THREADS] entries. Without an
 *     upper bound a long session accumulates dead [SharedFlow] instances, each holding
 *     their last replayed [ThreadState] (a few KB for a busy thread). 64 covers any
 *     realistic reading session; entries past the cap drop their replay buffer the next
 *     time the map is mutated.
 */
class CommentsRepository(
    private val td: TdSender,
    private val mapper: MessageMapper,
    private val scope: CoroutineScope,
    private val res: StringResolver,
) {

    private val unavailableMsg: String get() = res.getString(dev.lyo.hortay.R.string.comments_unavailable)


    sealed interface ThreadState {
        data object Loading : ThreadState
        data class Ready(val rows: List<ThreadRow>, val threadChatId: Long) : ThreadState
        data class Error(val message: String) : ThreadState
    }

    private data class ResolvedAnchor(val threadChatId: Long, val rootId: Long)

    // Permanent for the lifetime of the repo. ~16 bytes per entry; even with thousands
    // of unique posts viewed in a session this is negligible. Keeping it forever means
    // a thread that aged out of the SharedFlow LRU still skips GetMessageProperties +
    // GetMessageThread on the next observe — only the history fetch runs.
    private val resolvedAnchors = ConcurrentHashMap<Pair<Long, Long>, ResolvedAnchor>()

    // Bounded LRU. accessOrder=true bumps an entry to most-recently-used on every
    // get/put; removeEldestEntry caps the size and lets the JVM GC the dropped
    // SharedFlow once its WhileSubscribed upstream cancels. Synchronized at the map
    // level because LinkedHashMap is not thread-safe — the hot path inside the
    // synchronized block is a single SharedFlow construction (no IO, no suspend), so
    // contention is irrelevant in practice.
    private val streams: MutableMap<Pair<Long, Long>, SharedFlow<ThreadState>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<Pair<Long, Long>, SharedFlow<ThreadState>>(
                /* initialCapacity */ 16,
                /* loadFactor */ 0.75f,
                /* accessOrder */ true,
            ) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<Pair<Long, Long>, SharedFlow<ThreadState>>,
                ): Boolean = size > MAX_CACHED_THREADS
            },
        )

    /**
     * Background warm-up for posts lingering in the viewport. Resolves the anchor AND
     * primes TDLib's local DB with one batch of thread history, so the eventual tap hits
     * a warm cache and `GetMessageThreadHistory` in [threadFlow] returns from local
     * storage instead of paying a server round-trip. See [ChatPresence] for why we
     * scope the open/close around the fetch.
     */
    suspend fun prefetchThread(chatId: Long, candidateMessageIds: List<Long>) {
        val anchor = ensureAnchor(chatId, candidateMessageIds) ?: return
        ChatPresence.withOpenChat(td, anchor.threadChatId) {
            runCatching {
                td.send(TdApi.GetMessageThreadHistory(anchor.threadChatId, anchor.rootId, 0, 0, BATCH_SIZE))
            }.warnUnlessCancelled(TAG, "prefetchHistory(${anchor.threadChatId})")
        }
    }

    /**
     * Subscribe to the live thread for the given anchor.
     *
     * First call per (chatId, anchor) runs the bootstrap (anchor resolve + history load)
     * on attach and then keeps the data fresh via the shared [TdClient.updates] stream
     * filtered to the thread's chat id. Concurrent subscribers share the same upstream
     * coroutine. Once the last subscriber detaches and the [STOP_TIMEOUT_MS] linger
     * expires, the upstream cancels and live updates stop being processed for this
     * thread — but the entry stays in the LRU with its last [ThreadState.Ready] in the
     * replay buffer, so a re-entry within the LRU window starts from the cached state.
     *
     * [candidateMessageIds] — every id worth probing. For a standalone post that's a
     * single-element list; for an album it's all sibling ids. Telegram pins the
     * discussion thread to a single album member (typically the one with the caption),
     * and [TdApi.GetMessageThread] on any other returns "Message has no thread". The key
     * uses [List.minOrNull] so the same album maps to the same SharedFlow regardless of
     * input ordering.
     */
    fun observeThread(
        chatId: Long,
        candidateMessageIds: List<Long>,
        limit: Int = DEFAULT_LIMIT,
    ): Flow<ThreadState> {
        val anchorKey = candidateMessageIds.minOrNull()
            ?: return flowOf(ThreadState.Error(unavailableMsg))
        val key = chatId to anchorKey
        return synchronized(streams) {
            streams.getOrPut(key) {
                threadFlow(chatId, candidateMessageIds, limit)
                    .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)
            }
        }
    }

    private fun threadFlow(
        chatId: Long,
        candidateMessageIds: List<Long>,
        limit: Int,
    ): Flow<ThreadState> = flow {
        val anchor = ensureAnchor(chatId, candidateMessageIds) ?: run {
            emit(ThreadState.Error(unavailableMsg))
            return@flow
        }

        // Open the thread chat BEFORE the first history fetch so TDLib starts streaming
        // updates and prioritises caching for it; close it on flow termination via
        // [ChatPresence.withOpenChat] so a fast back-press still cleans up. Without this
        // the cold-path `GetMessageThreadHistory` pays full server round-trip latency,
        // which is the intermittent "1–3 sec Loading" the user sees.
        ChatPresence.withOpenChat(td, anchor.threadChatId) {
            // Progressive emit: surface Ready as soon as the first batch lands so the user
            // sees real comments while we keep filling up to `limit` in the background.
            // Without this the screen sits on Loading until all 4× BATCH_SIZE round-trips
            // complete — even when the first 50 already cover the visible viewport.
            //
            // Dedup: TDLib's GetMessageThreadHistory with offset=0 is INCLUSIVE on
            // from_message_id — the first message in page N+1 is the same as the last
            // message in page N. The set tracks ids we've already added (rootId pre-seeded
            // because the channel-post mirror is filtered out everywhere) so the boundary
            // and any other coincidental repeats fold into one entry.
            val live = mutableListOf<TdApi.Message>()
            val seenIds = HashSet<Long>().apply { add(anchor.rootId) }
            var fromId = 0L
            while (live.size < limit) {
                val batch = runCatching {
                    td.send(TdApi.GetMessageThreadHistory(anchor.threadChatId, anchor.rootId, fromId, 0, BATCH_SIZE))
                }.warnUnlessCancelled(TAG, "threadHistory(${anchor.threadChatId})").getOrNull() ?: break
                val msgs = batch.messages.orEmpty()
                if (msgs.isEmpty()) break
                var appended = 0
                for (m in msgs) if (seenIds.add(m.id)) { live += m; appended++ }
                fromId = msgs.last().id
                if (appended > 0) emit(buildReady(live, anchor))
                // No new messages in this page (only the boundary repeat) → end of thread.
                if (appended == 0) break
            }
            // Empty-thread case: nothing was emitted in the loop. Surface a Ready so the
            // overlay leaves Loading instead of hanging on it forever.
            if (live.isEmpty()) emit(buildReady(live, anchor))

            // Single-collector update fan-in. The previous implementation had four
            // launchIn'd collectors all racing on the shared `live` mutable list — a plain
            // bug that just happened not to trip in practice. With one collect we mutate
            // from one coroutine: no Mutex, no surprises.
            //
            // .buffer() decouples this collector from the global TD update bus. buildReady
            // calls buildTree → mapper.toThreadComment, which is suspending and may issue
            // GetUser/GetChat for unseen authors. Without buffering, slow mapper calls on
            // a busy thread would backpressure TdClient's drain coroutine and stall every
            // other subscriber (PostsRepository, MediaCache, ChatFoldersRepository). The
            // capacity is generous because realistic comment-update bursts are tiny (a
            // handful of UpdateMessageInteractionInfo per second) and even an off-by-one
            // accidental flood is bounded.
            td.updates
                .buffer(capacity = UPDATE_BUFFER_CAPACITY)
                .collect { upd ->
                    if (applyUpdate(upd, anchor, live, seenIds)) emit(buildReady(live, anchor))
                }
        }
    }

    private suspend fun ensureAnchor(
        chatId: Long,
        candidateMessageIds: List<Long>,
    ): ResolvedAnchor? {
        val anchorKey = candidateMessageIds.minOrNull() ?: return null
        val key = chatId to anchorKey
        resolvedAnchors[key]?.let { return it }

        // Telegram pins the discussion thread to a single member of an album (typically
        // the one with the caption); GetMessageThread on any other member returns 400.
        // GetMessageProperties is a local capability lookup with no server round-trip,
        // so we probe candidates in order and pick the carrier.
        val anchorId = candidateMessageIds.firstOrNull { id ->
            runCatching { td.send(TdApi.GetMessageProperties(chatId, id)) }
                .warnUnlessCancelled(TAG, "messageProperties($chatId,$id)")
                .getOrNull()
                ?.canGetMessageThread == true
        } ?: return null

        val info = runCatching { td.send(TdApi.GetMessageThread(chatId, anchorId)) }
            .warnUnlessCancelled(TAG, "messageThread($chatId,$anchorId)")
            .getOrNull() ?: return null

        val resolved = ResolvedAnchor(info.chatId, info.messageThreadId)
        return resolvedAnchors.putIfAbsent(key, resolved) ?: resolved
    }

    /**
     * Apply one [TdApi.Update] to the live message list. Returns true iff the list was
     * meaningfully mutated and a fresh snapshot should be emitted.
     *
     * [seenIds] is the same set the bootstrap loop uses for pagination dedup; we add to
     * it on UpdateNewMessage and remove on UpdateDeleteMessages so a re-deliver of the
     * same id (TDLib does occasionally re-emit during reconnects) is a no-op instead of
     * a duplicate row.
     */
    private fun applyUpdate(
        upd: TdApi.Update,
        anchor: ResolvedAnchor,
        live: MutableList<TdApi.Message>,
        seenIds: HashSet<Long>,
    ): Boolean = when (upd) {
        is TdApi.UpdateNewMessage -> {
            val m = upd.message
            if (m.chatId != anchor.threadChatId) false
            else if (!seenIds.add(m.id)) false
            else { live += m; true }
        }
        is TdApi.UpdateMessageInteractionInfo -> {
            if (upd.chatId != anchor.threadChatId) false
            else {
                val idx = live.indexOfFirst { it.id == upd.messageId }
                if (idx == -1) false
                else { live[idx].interactionInfo = upd.interactionInfo; true }
            }
        }
        is TdApi.UpdateMessageContent -> {
            if (upd.chatId != anchor.threadChatId) false
            else {
                val idx = live.indexOfFirst { it.id == upd.messageId }
                if (idx == -1) false
                else { live[idx].content = upd.newContent; true }
            }
        }
        is TdApi.UpdateDeleteMessages -> {
            if (upd.chatId != anchor.threadChatId || !upd.isPermanent) false
            else {
                val ids = upd.messageIds.toHashSet()
                val before = live.size
                live.removeAll { it.id in ids }
                if (live.size != before) {
                    seenIds.removeAll(ids)
                    true
                } else false
            }
        }
        else -> false
    }

    private suspend fun buildReady(
        live: List<TdApi.Message>,
        anchor: ResolvedAnchor,
    ): ThreadState.Ready =
        ThreadState.Ready(buildTree(live, anchor.rootId), anchor.threadChatId)

    suspend fun viewMessages(threadChatId: Long, messageIds: List<Long>) =
        ChatPresence.viewMessages(
            td = td,
            chatId = threadChatId,
            messageIds = messageIds,
            // The user is reading a comments thread overlay — explicit source helps
            // TDLib classify the view (vs. plain chat history scrolling).
            source = TdApi.MessageSourceMessageThreadHistory(),
            // The thread chat is currently opened by the comments overlay
            // (see [observeThread] / [prefetchThread] which both wrap their work in
            // ChatPresence.withOpenChat). With the chat opened, force_read=false is
            // sufficient — TDLib advances read state automatically. force_read=true
            // would be redundant here; keeping it false also leaves the discussion
            // group's per-user read pointer alone for the brief windows when the
            // thread is being prefetched but not yet visibly opened.
            forceRead = false,
        )

    private suspend fun buildTree(messages: List<TdApi.Message>, rootMessageId: Long): List<ThreadRow> {
        if (messages.isEmpty()) return emptyList()

        // Map every message via the shared mapper FIRST — same caching layer as channel
        // posts, so users that already appeared in the feed don't trigger a fresh GetUser.
        val mapped: Map<Long, TimelinePost> = messages.associate { it.id to mapper.toThreadComment(it) }

        // Group by parent. The conversation root (the channel-post mirror) becomes the
        // virtual depth-0 parent; ids that no longer exist in the thread (deleted /
        // out-of-window) collapse under it too.
        val children: Map<Long, List<TimelinePost>> = messages.groupBy { msg ->
            val replyId = (msg.replyTo as? TdApi.MessageReplyToMessage)?.messageId
            if (replyId != null && replyId != rootMessageId && replyId in mapped) replyId else 0L
        }.mapValues { entry -> entry.value.mapNotNull { mapped[it.id] } }

        val rows = mutableListOf<ThreadRow>()
        fun walk(parentId: Long, depth: Int) {
            val siblings = children[parentId].orEmpty().sortedBy { it.date }
            siblings.forEachIndexed { idx, msg ->
                rows += ThreadRow(
                    message = msg,
                    depth = depth.coerceAtMost(MAX_DEPTH),
                    isLastSibling = idx == siblings.lastIndex,
                )
                walk(msg.id, depth + 1)
            }
        }
        walk(0L, 0)

        return rows
    }

    private companion object {
        const val TAG = "CommentsRepository"
        const val MAX_DEPTH = 3
        const val BATCH_SIZE = 50
        const val DEFAULT_LIMIT = 200
        const val STOP_TIMEOUT_MS = 30_000L
        // Hand-picked: typical reading session opens 10–20 unique threads. 64 leaves
        // slack so the LRU only kicks in for power-users; smaller and the cache thrashes.
        const val MAX_CACHED_THREADS = 64
        // Per-thread buffer between the global TD update bus and our slow collect-block.
        // 256 absorbs realistic update bursts (interaction-info storms on a viral thread,
        // brief reconnects re-emitting catch-ups) without ever pinging the upstream.
        // SUSPEND on overflow is intentional — comment updates are not droppable, and a
        // legitimate flood that fills 256 slots already means user-visible jank in the
        // tree render, which the buffer can't mask anyway.
        const val UPDATE_BUFFER_CAPACITY = 256
    }
}
