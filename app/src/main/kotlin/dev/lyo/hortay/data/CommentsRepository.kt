package dev.lyo.hortay.data

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
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
    private val archiveRepository: dev.lyo.hortay.data.archive.ArchiveRepository? = null,
) {

    private val unavailableMsg: String get() = res.getString(dev.lyo.hortay.R.string.comments_unavailable)


    sealed interface ThreadState {
        data object Loading : ThreadState
        data class Ready(val rows: ImmutableList<ThreadRow>, val threadChatId: Long) : ThreadState
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

    /**
     * Optimistic reaction overrides keyed by `(threadChatId, messageId)`. Populated by
     * [applyOptimisticReaction] when the user taps a chip; consulted in [buildTree] so
     * the visible chip reflects the local guess instantly. Cleared on the next
     * authoritative `UpdateMessageInteractionInfo` for that message (see [applyUpdate])
     * — server truth always wins on reconciliation, even if our optimistic arithmetic
     * is slightly off.
     *
     * Survives the [SharingStarted.WhileSubscribed] linger so a scroll-out + scroll-in
     * inside the 30 s window picks the override back up. Cleared by [clear] on logout.
     */
    private val optimisticOverrides = ConcurrentHashMap<Pair<Long, Long>, Reactions>()

    /**
     * Side-channel that nudges the active [threadFlow] to re-emit when an override
     * lands, without going through `td.updates` (those are server-driven and would
     * mean fabricating fake `TdApi.MessageReactions` payloads — needless impedance).
     * Merged into the single-collector update fan-in inside [threadFlow] so the
     * "one writer mutates `live`" invariant still holds.
     */
    private val invalidations = MutableSharedFlow<Pair<Long, Long>>(extraBufferCapacity = 64)

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
     * Fire-and-forget pre-warm of a thread ahead of a navigation push
     * (tap-driven, foreground). Returns immediately — does NOT block the
     * caller.
     *
     * Kicks off [prefetchThread] in this repository's own scope: resolves
     * the anchor and primes TDLib's local DB with one batch of thread
     * history. When [CommentsScreen] mounts and [observeThread] runs its
     * bootstrap, the same `GetMessageThreadHistory` call returns from
     * local cache instead of paying a server round-trip — the screen
     * lands Ready in one frame instead of paging in.
     *
     * `prefetchThread` is self-deduping via [prefetchedAnchors] +
     * [inflightAnchors], so a viewport-stable warm-up already in flight
     * (or already completed) makes this a cheap no-op.
     *
     * Architectural contract: the push happens in parallel with this
     * prefetch, not after it. Whether a loading overlay paints on the
     * destination is the destination's own decision — driven by whether
     * thread state is still Loading when its [SCREEN_MOUNT_GRACE_MS]
     * anti-flicker grace elapses.
     */
    fun primeCommentsForOpen(post: TimelinePost) {
        val candidateIds = post.albumMessageIds.ifEmpty { listOf(post.id) }
        if (candidateIds.isEmpty()) return
        scope.launch { prefetchThread(post.chatId, candidateIds) }
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
            // Bootstrap is one shot: collect ALL pages first, emit Ready ONCE at the end.
            // Per-batch progressive emit looked attractive ("surface fast"), but produced
            // an inverted-feeling load order. TDLib paginates GetMessageThreadHistory
            // id-desc (newest first), and `buildTree` sorts siblings by date asc, so each
            // intermediate emit visually PREPENDED an older page above the previously-
            // visible top — read as "newest comments load, then older ones squeeze in
            // above", the opposite of a chronological reveal. The CommentsScreen's
            // `rememberDeferredLoading` 600 ms grace already gates the spinner so
            // prefetched / hot-LRU threads still feel instant; cold threads now show a
            // single Ready transition instead of a cascade of upward shifts.
            //
            // Dedup: TDLib's GetMessageThreadHistory with offset=0 is INCLUSIVE on
            // from_message_id — the first message in page N+1 is the same as the last
            // message in page N. The set tracks ids we've already added (rootId pre-seeded
            // because the channel-post mirror is filtered out everywhere) so the boundary
            // and any other coincidental repeats fold into one entry.
            val live = mutableListOf<TdApi.Message>()
            val seenIds = HashSet<Long>().apply { add(anchor.rootId) }
            // mediaAlbumId of the root mirror's album when the channel post itself is an
            // album. TDLib mirrors a channel-post album into the linked discussion
            // supergroup as N messages with the same mediaAlbumId, all sharing
            // topicId=MessageTopicThread(rootId). The single-id seenIds.add(anchor.rootId)
            // above only excludes ONE of those N; the remaining (N-1) used to leak into
            // the thread as phantom "comments" (especially visible on photo-album posts).
            // We capture this album id the first time a batch contains the rootId mirror,
            // then exclude all its siblings from `live` for the rest of the session.
            // Stored in an outer-scope `var` (captured by the `collect` lambda below)
            // so live UpdateNewMessage redelivery during a reconnect is also filtered.
            var rootAlbumId = 0L
            var fromId = 0L
            while (live.size < limit) {
                val batch = runCatching {
                    td.send(TdApi.GetMessageThreadHistory(anchor.threadChatId, anchor.rootId, fromId, 0, BATCH_SIZE))
                }.warnUnlessCancelled(TAG, "threadHistory(${anchor.threadChatId})").getOrNull() ?: break
                val msgs = batch.messages.orEmpty()
                if (msgs.isEmpty()) break
                // Capture rootAlbumId lazily — once a batch contains the root mirror
                // with a non-zero mediaAlbumId, every sibling in this page (and every
                // sibling re-delivered later) is filtered. Scanning the whole batch
                // first is required because GetMessageThreadHistory walks id-desc and
                // the root mirror is the LOWEST member of its own album; siblings
                // (higher ids) appear earlier in the batch iteration order.
                if (rootAlbumId == 0L) {
                    msgs.firstOrNull { it.id == anchor.rootId }
                        ?.takeIf { it.mediaAlbumId != 0L }
                        ?.let { rootAlbumId = it.mediaAlbumId }
                }
                var appended = 0
                for (m in msgs) {
                    if (rootAlbumId != 0L && m.mediaAlbumId == rootAlbumId) {
                        // Root mirror or one of its album siblings. Track in seenIds so
                        // a later UpdateNewMessage re-deliver during a reconnect stays a
                        // silent no-op.
                        seenIds.add(m.id)
                        continue
                    }
                    if (seenIds.add(m.id)) { live += m; appended++ }
                }
                fromId = msgs.last().id
                // No new messages in this page (only the boundary repeat) → end of thread.
                if (appended == 0) break
            }
            // Single Ready after the full bootstrap. Covers both the populated case and
            // the empty-thread case (nothing was added), so the overlay leaves Loading
            // exactly once with the full chronological set.
            emit(buildReady(live, anchor))

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
            // Two event streams feed a single mutator coroutine:
            //   • TDLib updates (server-driven new messages, interaction info,
            //     content edits, deletes) — write into `live`.
            //   • Optimistic-override invalidations from [applyOptimisticReaction] —
            //     `live` is unchanged but the override map has a new entry, so we
            //     just need to re-emit so [buildTree] picks the override up.
            // Filtering invalidations by threadChatId scopes each flow to its own
            // thread so a different overlay's tap doesn't ripple here.
            val tdlibEvents: Flow<ThreadEvent> = td.updates.map { ThreadEvent.Tdlib(it) }
            val invalidationEvents: Flow<ThreadEvent> = invalidations
                .filter { it.first == anchor.threadChatId }
                .map { ThreadEvent.OptimisticInvalidate }
            val events: Flow<ThreadEvent> = merge(tdlibEvents, invalidationEvents)
            events
                .buffer(capacity = UPDATE_BUFFER_CAPACITY)
                .collect { ev ->
                    val changed = when (ev) {
                        is ThreadEvent.Tdlib -> applyUpdate(ev.update, anchor, rootAlbumId, live, seenIds)
                        ThreadEvent.OptimisticInvalidate -> true
                    }
                    if (changed) emit(buildReady(live, anchor))
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
        rootAlbumId: Long,
        live: MutableList<TdApi.Message>,
        seenIds: HashSet<Long>,
    ): Boolean = when (upd) {
        is TdApi.UpdateNewMessage -> {
            val m = upd.message
            // Discussion supergroup hosts every thread for the channel in a single
            // chat — every channel post's comments fan out as separate threads
            // inside the same `threadChatId`. Filtering only by chatId would
            // splice a comment authored under post B into the live list of the
            // thread the user is currently reading on post A. TDLib disambiguates
            // by `Message.topicId = MessageTopicThread(messageThreadId)`, where
            // `messageThreadId` equals the discussion-side mirror id (== our
            // `anchor.rootId`). Drop everything that isn't authored under our
            // root.
            if (m.chatId != anchor.threadChatId) false
            else if (!belongsToThread(m, anchor)) false
            // Root-mirror album re-deliver during a reconnect — discard. The
            // pagination loop above already filtered the original deliveries
            // and added every member id to seenIds, but TDLib has been observed
            // to re-emit UpdateNewMessage for previously-seen messages after a
            // socket re-handshake; without the explicit mediaAlbumId match a
            // re-delivered sibling that wasn't yet in seenIds (rare race during
            // the very first batch) would still leak through.
            else if (rootAlbumId != 0L && m.mediaAlbumId == rootAlbumId) {
                seenIds.add(m.id); false
            }
            else if (!seenIds.add(m.id)) false
            else { live += m; true }
        }
        is TdApi.UpdateMessageInteractionInfo -> {
            if (upd.chatId != anchor.threadChatId) false
            else {
                val idx = live.indexOfFirst { it.id == upd.messageId }
                if (idx == -1) false
                else {
                    // Server truth landed for this message — drop any optimistic
                    // override we stamped on it. The next [buildTree] pass will
                    // map the fresh interactionInfo through [MessageContentMapper]
                    // and the chip reflects the canonical state.
                    optimisticOverrides.remove(anchor.threadChatId to upd.messageId)
                    live[idx].interactionInfo = upd.interactionInfo
                    true
                }
            }
        }
        is TdApi.UpdateMessageContent -> {
            if (upd.chatId != anchor.threadChatId) false
            else {
                val idx = live.indexOfFirst { it.id == upd.messageId }
                if (idx == -1) false
                else {
                    // Archive: capture comment edit. Mirror of PostsRepository handling
                    // but with isComment = true so the snapshot is filtered out of the
                    // feed's deleted-post view (still surfaces in the archive screen).
                    if (archiveRepository != null) {
                        scope.launch {
                            archiveRepository.captureTdlibVersion(
                                chat = dev.lyo.hortay.data.archive.ChatRef.tdlib(upd.chatId),
                                messageKey = upd.messageId.toString(),
                                albumKey = null, // comments rarely have albums
                                editedAtMs = null,
                                meta = dev.lyo.hortay.data.archive.TdlibContentMetaExtractor.extract(upd.newContent),
                                isComment = true,
                            )
                        }
                    }
                    live[idx].content = upd.newContent; true
                }
            }
        }
        is TdApi.UpdateDeleteMessages -> {
            if (upd.chatId != anchor.threadChatId || !upd.isPermanent) false
            else {
                // Archive: capture comment deletions before mutating live list.
                if (archiveRepository != null) {
                    val keys = upd.messageIds.map { it.toString() }
                    if (keys.isNotEmpty()) {
                        scope.launch {
                            archiveRepository.captureTdlibDelete(
                                chat = dev.lyo.hortay.data.archive.ChatRef.tdlib(upd.chatId),
                                messageKeys = keys,
                                albumKey = null, // comments don't use album debounce
                                isComment = true,
                            )
                        }
                    }
                }
                // Delete fan-in is naturally thread-safe: the only mutation is
                // `live.removeAll { it.id in ids }`, so messages from foreign
                // threads (different `messageThreadId`, same discussion chat)
                // can never be in `live` in the first place (UpdateNewMessage
                // gates them out above) and are silent no-ops here.
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

    /**
     * Whether [message] belongs to the thread we are currently observing.
     *
     * Canonical signal is `Message.topicId = MessageTopicThread(messageThreadId)` where
     * `messageThreadId` equals the discussion-side mirror id of the channel post — i.e.
     * our [ResolvedAnchor.rootId]. Forum supergroups carry `MessageTopicForum` instead,
     * and Telegram does not let a forum supergroup also be a channel's linked discussion
     * group, so anything that isn't a `MessageTopicThread` matching our root is foreign
     * traffic and must be rejected.
     *
     * Defensive fallback for `topicId == null`: walk the reply chain — every comment in
     * a discussion thread either replies to the mirror root directly or to another
     * comment in the same thread. If the immediate `replyTo` points at our root we
     * accept; otherwise we reject. This branch should only fire on extremely old TDLib
     * cache entries — current TDLib (any 1.8.x+) always populates `topicId` for
     * non-forum supergroup messages.
     */
    private fun belongsToThread(message: TdApi.Message, anchor: ResolvedAnchor): Boolean {
        val topic = message.topicId
        if (topic != null) {
            return (topic as? TdApi.MessageTopicThread)?.messageThreadId == anchor.rootId
        }
        val replyTo = message.replyTo as? TdApi.MessageReplyToMessage ?: return false
        return replyTo.messageId == anchor.rootId
    }

    private suspend fun buildReady(
        live: List<TdApi.Message>,
        anchor: ResolvedAnchor,
    ): ThreadState.Ready =
        ThreadState.Ready(buildTree(live, anchor).toImmutableList(), anchor.threadChatId)

    /**
     * Apply an optimistic reaction toggle on a comment row inside this thread. The
     * caller is the UI's tap handler — it has the live [Reactions] snapshot it just
     * rendered, so it passes that as [current] and the policy here computes the
     * post-tap state without re-reading from TdApi. The override is stored
     * (key = `threadChatId, messageId`) and an invalidation nudges the active
     * thread flow to re-emit; if the flow has no current subscribers, the override
     * is picked up next time someone attaches (within the 30 s linger window) or
     * dropped on logout via [clear]. Server reconciliation in [applyUpdate]
     * removes the override the moment TDLib confirms the change.
     */
    fun applyOptimisticReaction(
        threadChatId: Long,
        messageId: Long,
        current: Reactions,
        kind: ReactionKind,
        nowChosen: Boolean,
    ) {
        val next = ReactionTogglePolicy.apply(current, kind, nowChosen)
        val key = threadChatId to messageId
        optimisticOverrides[key] = next
        invalidations.tryEmit(key)
    }

    /**
     * Drop the override for a single comment without dispatching the RPC. Called
     * from the rollback path when [ChannelActionsRepository.toggleReaction] fails;
     * the next emit reverts the chip to its pre-tap state, and any concurrent
     * `UpdateMessageInteractionInfo` the server happened to send for this
     * message in the meantime still wins because [applyUpdate] already cleared
     * the override.
     */
    fun clearOptimisticReaction(threadChatId: Long, messageId: Long) {
        val key = threadChatId to messageId
        if (optimisticOverrides.remove(key) != null) {
            invalidations.tryEmit(key)
        }
    }

    private sealed interface ThreadEvent {
        data class Tdlib(val update: TdApi.Update) : ThreadEvent
        data object OptimisticInvalidate : ThreadEvent
    }

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

    private suspend fun buildTree(messages: List<TdApi.Message>, anchor: ResolvedAnchor): List<ThreadRow> {
        if (messages.isEmpty()) return emptyList()
        val rootMessageId = anchor.rootId

        // Map every message via the shared mapper FIRST — same caching layer as channel
        // posts, so users that already appeared in the feed don't trigger a fresh GetUser.
        // After mapping, splice in any optimistic reaction override the user just stamped.
        // The override map is consulted (not the message's TdApi reactions) when present so
        // the chip flips instantly on tap and stays flipped until the server's
        // `UpdateMessageInteractionInfo` clears the override.
        val mappedPosts: List<TimelinePost> = messages.map { msg ->
            val base = mapper.toThreadComment(msg)
            val override = optimisticOverrides[anchor.threadChatId to msg.id]
            if (override != null) base.copy(reactions = override) else base
        }

        // Merge album members posted AS COMMENTS via the same helper the feed uses —
        // a user/admin posting an album in the discussion thread produces N
        // TdApi.Messages with the same mediaAlbumId, each mapping to a 1-item
        // PhotoAlbum. Without merging the thread used to render those as N stacked
        // single-photo bubbles, with caption + reactions on only the first member
        // (tdlib/td#2312 — interactionInfo only on the first album id).
        // Standalones (mediaAlbumId == 0) pass through unchanged.
        val merged: List<TimelinePost> = PostFilterStrategy.mergeAlbums(mappedPosts)

        // After merge, the canonical anchor of a multi-member album is the lowest-id
        // member; other member ids no longer appear in `merged`. A reply pointing at
        // a mid-album member id (Telegram-UX reroutes "reply" to the canonical
        // member, but the server-side API accepts any sibling) would fall out of the
        // parent-lookup and collapse to depth 0. Redirect every member id to its
        // canonical merged-anchor id.
        val redirect: Map<Long, Long> = buildMap {
            for (p in merged) {
                if (p.albumMessageIds.isNotEmpty()) {
                    for (memberId in p.albumMessageIds) put(memberId, p.id)
                } else {
                    put(p.id, p.id)
                }
            }
        }

        val mapped: Map<Long, TimelinePost> = merged.associateBy { it.id }

        // Group by parent. The conversation root (the channel-post mirror) becomes the
        // virtual depth-0 parent; ids that no longer exist in the thread (deleted /
        // out-of-window) collapse under it too.
        val children: Map<Long, List<TimelinePost>> = merged.groupBy { post ->
            val rawReplyId = post.parentId
            val canonicalReplyId = rawReplyId?.let { redirect[it] ?: it }
            if (canonicalReplyId != null && canonicalReplyId != rootMessageId && canonicalReplyId in mapped) {
                canonicalReplyId
            } else 0L
        }

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
        optimisticOverrides.clear()
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
