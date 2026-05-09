package dev.lyo.hortay.data

import kotlinx.coroutines.CompletableDeferred
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

    /**
     * Outcome of [ensureAnchor] for a `(chatId, anchorKey)` query. The negative
     * variant is just as important to cache as the positive one: a channel post
     * with no linked discussion group always answers `400 Message has no thread`,
     * and without negative caching every viewport-stable burst (debounced 700 ms)
     * + every tap-driven [observeThread] re-fired the same RPC — burning RTTs
     * for a permanently-fixed result and contributing to per-method rate limits
     * that surface as comments-load latency for *other* threads. Live logcat
     * confirmed this: same `(chatId, msgId)` queried twice in the same
     * millisecond by racing prefetch + observe paths, both unaware of each
     * other's in-flight call.
     */
    private sealed interface AnchorResolution {
        data class Resolved(val anchor: ResolvedAnchor) : AnchorResolution
        /** Server reported `Message has no thread` (or the album walk found no thread carrier). */
        data object NoThread : AnchorResolution
    }

    // Permanent for the lifetime of the repo. ~24 bytes per entry; even with
    // thousands of unique posts viewed in a session this is negligible. Both
    // positive (Resolved) and negative (NoThread) outcomes are cached so a
    // post that genuinely has no comments doesn't keep re-firing
    // GetMessageProperties + GetMessageThread on every viewport stable.
    private val resolvedAnchors = ConcurrentHashMap<Pair<Long, Long>, AnchorResolution>()

    /**
     * In-flight [ensureAnchor] requests. Concurrent callers (e.g. [prefetchThread]
     * fired by viewport-stable racing [observeThread] from a user tap) used to
     * both check `resolvedAnchors` (miss), both fire `GetMessageThread`, both
     * receive the same answer — doubling the per-method rate-limit pressure
     * for free. The deferred lets the second caller `await` the first caller's
     * result; the entry is removed once the answer is cached.
     */
    private val inflightAnchors = ConcurrentHashMap<Pair<Long, Long>, CompletableDeferred<AnchorResolution?>>()

    // Anchors we already warmed in this session via [prefetchThread]. Without this
    // dedup the same thread re-runs `GetMessageThreadHistory` every time the
    // viewport stabilises around the corresponding post (debounced 700 ms); on a
    // feed where the user dwells, scrolls, returns — the same chat racks up
    // dozens of identical history requests per minute and Telegram rate-limits
    // it (live logcat caught a "[429] retry after 31" on prefetchHistory).
    // Permanent for the lifetime of the repo: history doesn't decay during a
    // session, and a tap-driven [observeThread] still fetches fresh history if
    // the prefetched batch doesn't cover the viewport. ~16 bytes per entry, on
    // par with [resolvedAnchors].
    private val prefetchedAnchors = ConcurrentHashMap.newKeySet<Pair<Long, Long>>()

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
        val anchorKey = anchor.threadChatId to anchor.rootId
        // Skip if we've already warmed this exact thread in this session.
        // [Set.add] is atomic — only the first caller proceeds; concurrent
        // viewport-stable triggers for the same post are no-ops.
        if (!prefetchedAnchors.add(anchorKey)) return
        ChatPresence.withOpenChat(td, anchor.threadChatId) {
            runCatching {
                td.send(TdApi.GetMessageThreadHistory(anchor.threadChatId, anchor.rootId, 0, 0, BATCH_SIZE))
            }.warnUnlessCancelled(TAG, "prefetchHistory(${anchor.threadChatId})")
                // If we hit a transient failure (rate limit, network blip), drop
                // the dedup mark so a future viewport-stable can try again — the
                // history payload never landed, the user still benefits from a
                // retry. Permanent failures (chat deleted, unauthorised) won't
                // re-succeed either way; we accept the small cost of a single
                // retry-after-error per affected anchor.
                .onFailure { prefetchedAnchors.remove(anchorKey) }
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

        // Cached result — positive or negative.
        when (val cached = resolvedAnchors[key]) {
            is AnchorResolution.Resolved -> return cached.anchor
            is AnchorResolution.NoThread -> return null
            null -> Unit
        }

        // In-flight dedup. `putIfAbsent` is the atomic "claim or join" primitive:
        //   • Returns null if we are the first caller — we go fetch and complete
        //     the deferred for everyone awaiting.
        //   • Returns the existing deferred if another caller is already
        //     fetching — we just await its result.
        // The fetcher always completes the deferred (with the resolution or
        // null on cancellation/exception) and removes its in-flight entry, so
        // subsequent callers see the final cached value via [resolvedAnchors].
        val ours = CompletableDeferred<AnchorResolution?>()
        val existing = inflightAnchors.putIfAbsent(key, ours)
        if (existing != null) {
            return when (val r = existing.await()) {
                is AnchorResolution.Resolved -> r.anchor
                AnchorResolution.NoThread, null -> null
            }
        }

        try {
            // Standalone post (non-album): GetMessageThread succeeds against the
            // single id directly, so we skip the GetMessageProperties probe — a
            // free saving of one JNI hop per first-time comments open. The probe
            // exists exclusively for album disambiguation: Telegram pins the
            // discussion thread to a single album member (per tdlib/td#2312, the
            // first/oldest one), and calling GetMessageThread on any other
            // sibling returns "Message has no thread".
            val anchorId = if (candidateMessageIds.size == 1) {
                candidateMessageIds.single()
            } else {
                // Album: probe candidates in ascending-id order until we find
                // the thread carrier. PostFilterStrategy already builds
                // albumMessageIds sorted ascending, so the first candidate is
                // the oldest member — which per tdlib/td#2312 is the canonical
                // thread carrier — and this loop normally exits on the first
                // iteration. The fallback walk still exists for the rare case
                // where Telegram pins the thread to a different album member.
                //
                // [allProbesAuthoritative] tracks whether every probe answered
                // definitively (success OR a permanent 400). If a transient
                // failure (network / 429 / 5xx) silenced any probe, we DO NOT
                // cache NoThread — the album might genuinely have a thread we
                // just couldn't reach. Caching here would mask comments for
                // the entire session after a single network blip.
                var allProbesAuthoritative = true
                val carrier = candidateMessageIds.firstOrNull { id ->
                    val probeResult = runCatching { td.send(TdApi.GetMessageProperties(chatId, id)) }
                        .warnUnlessCancelled(TAG, "messageProperties($chatId,$id)")
                    probeResult.fold(
                        onSuccess = { it.canGetMessageThread == true },
                        onFailure = { err ->
                            val code = (err as? TdClient.TdException)?.code ?: 0
                            if (code != 400) allProbesAuthoritative = false
                            false
                        },
                    )
                }
                if (carrier == null) {
                    if (allProbesAuthoritative) {
                        // Every probe answered: the album really has no thread carrier.
                        resolvedAnchors[key] = AnchorResolution.NoThread
                        ours.complete(AnchorResolution.NoThread)
                    } else {
                        // At least one probe was transient — leave the cache
                        // empty so the next observe gets a fresh chance.
                        ours.complete(null)
                    }
                    return null
                }
                carrier
            }

            val result = runCatching { td.send(TdApi.GetMessageThread(chatId, anchorId)) }
                .warnUnlessCancelled(TAG, "messageThread($chatId,$anchorId)")

            result.fold(
                onSuccess = { info ->
                    val resolution = AnchorResolution.Resolved(ResolvedAnchor(info.chatId, info.messageThreadId))
                    resolvedAnchors[key] = resolution
                    ours.complete(resolution)
                    return resolution.anchor
                },
                onFailure = { err ->
                    // Discriminate permanent-NoThread (cache it) from transient
                    // failures (must NOT poison the cache). The original code
                    // returned null on every failure without caching, which was
                    // safe but caused repeat-fires that drove rate limits; my
                    // first pass cached every null which was the inverse bug —
                    // a single 429 / network blip would mask the thread for the
                    // rest of the session.
                    //
                    // Decision: cache as NoThread only for code 400 (Bad
                    // Request). Per Telegram's MTProto error semantics, 400 is
                    // the caller-error class — it does not transition to OK on
                    // retry. The canonical answer for a channel without a
                    // linked discussion group is `[400] Message has no thread`,
                    // and adjacent permanent 400s ("MSG_ID_INVALID",
                    // "MESSAGE_ID_INVALID", "CHANNEL_PRIVATE") are equally
                    // permanent for the message lifetime. Codes 401/403/404
                    // are *also* permanent but rare on this RPC; conservatively
                    // we cache only 400 — false-positive caching on a rare
                    // 401/403 just leaks the same retry budget the old code
                    // had. 429 / 500-599 / 0 (network) — never cache.
                    val code = (err as? TdClient.TdException)?.code ?: 0
                    if (code == 400) {
                        resolvedAnchors[key] = AnchorResolution.NoThread
                        ours.complete(AnchorResolution.NoThread)
                    } else {
                        // Transient: don't poison the cache. Wake any
                        // concurrent waiter with null so it bails this call;
                        // the next call after this one races a fresh fetch.
                        ours.complete(null)
                    }
                    return null
                },
            )
        } catch (t: Throwable) {
            // Don't poison the cache on cancellation — the next observer
            // should be free to retry. But complete the deferred so any
            // already-awaiting caller wakes up cleanly with null.
            ours.complete(null)
            throw t
        } finally {
            inflightAnchors.remove(key, ours)
        }
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

    /**
     * Drop the per-account anchor cache + cached SharedFlow streams. Called
     * from [AppGraph] on logout so a thread anchor resolved for account A
     * (which lives forever for the repo's lifetime to skip
     * GetMessageProperties + GetMessageThread on subsequent opens) can't be
     * served to account B's UI.
     *
     * Active subscribers will see their upstream upstream cancel naturally
     * (their WhileSubscribed scope dies with the synthetic empty stream we
     * leave in the LRU). Sign-in to a new account starts every thread fresh.
     */
    fun clear() {
        resolvedAnchors.clear()
        synchronized(streams) { streams.clear() }
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
