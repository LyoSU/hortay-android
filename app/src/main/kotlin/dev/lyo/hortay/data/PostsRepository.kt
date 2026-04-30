package dev.lyo.hortay.data

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Twitter-style chronological feed merged from every channel chat the user follows.
 *
 * Pull model:
 *   1. [TdApi.LoadChats] hints the daemon to fetch chat list pages.
 *   2. [TdApi.GetChats] returns cached chat IDs (local-only, fast).
 *   3. For each *channel* chat, [TdApi.GetChatHistory] fetches the latest N messages.
 *   4. Raw messages → [MessageMapper] → [PostFilterStrategy] → [posts].
 *
 * Concurrency: a single [Mutex] guards refreshes so that pull-to-refresh + incremental
 * updates from TDLib never interleave and produce phantom duplicates.
 *
 * Storage: the live feed is held in a [PersistentList]. The hot path
 * (UpdateMessageInteractionInfo) fires dozens of times per second on busy news days, and
 * a plain `List` makes us copy the whole 1000-entry feed on every event. PersistentList's
 * structural sharing turns the per-event mutation into O(log N) — a few KB of allocation
 * instead of ~50KB.
 */
class PostsRepository(
    private val td: TdSender,
    private val mapper: MessageMapper,
    private val scope: CoroutineScope,
) {

    private val refreshMutex = Mutex()
    private val chatCache = ConcurrentHashMap<Long, TdApi.Chat>()

    // Stamped on successful refreshLocked completion. refreshIfStale uses it to skip
    // re-opening the app from hammering 200+ TDLib calls when the feed is still warm.
    @Volatile
    private var lastRefreshAtMs: Long = 0L

    // Album coalescing: TDLib emits one UpdateNewMessage per album member with no
    // "album complete" signal (issue tdlib/td#2523). We buffer members per (chatId,
    // mediaAlbumId) and flush once the burst quietens — same approach as the official
    // Telegram client. Without this, an album posts as "1 photo" → "2 photos" → … and
    // can leave a stale single-member card in the feed if later members lag.
    private val albumBuffers = ConcurrentHashMap<Pair<Long, Long>, MutableList<TdApi.Message>>()
    private val albumDebounce = ConcurrentHashMap<Pair<Long, Long>, Job>()

    // Single-flight + cooldown for deep channel-history loads. Re-entering the same
    // channel filter within DEEP_LOAD_COOLDOWN_MS reuses the previous load (no second
    // GetChatHistory(80) round-trip). Failed loads do NOT mark cooldown, so transient
    // network blips don't lock a channel out for a full minute.
    private val deepLoadJobs = ConcurrentHashMap<Long, Deferred<Result<Unit>>>()
    private val deepLoadCooldownUntilMs = ConcurrentHashMap<Long, Long>()

    // Coalescing buffer for UpdateMessageInteractionInfo. On busy days these arrive in
    // dozens-per-second bursts for *every* channel in the user's list (not just visible
    // ones). Each event used to fan out one O(N) `_posts.update` — at 1000 posts and 50
    // events/sec that's ~50MB/sec of garbage. Buffer per-message updates and flush all
    // pending mutations in a single `mutate {}` block every INTERACTION_INFO_COALESCE_MS.
    // Non-nullable values: ConcurrentHashMap forbids null. TDLib does occasionally emit
    // UpdateMessageInteractionInfo with null `interactionInfo` (which the original
    // per-field-fallback handler treated as a no-op), so we drop those at the entry.
    private val pendingInteractionInfo =
        ConcurrentHashMap<Pair<Long, Long>, TdApi.MessageInteractionInfo>()
    private val interactionFlushScheduled = AtomicBoolean(false)

    private val _posts = MutableStateFlow<PersistentList<TimelinePost>>(persistentListOf())
    val posts: StateFlow<PersistentList<TimelinePost>> = _posts.asStateFlow()

    init {
        // Live feed: any new channel post arrives via UpdateNewMessage and is folded in.
        td.updates.filterIsInstance<TdApi.UpdateNewMessage>()
            .onEach { update -> handleNewMessage(update.message) }
            .launchIn(scope)

        // Server-side counter sync: views, reactions, comment counts.
        td.updates.filterIsInstance<TdApi.UpdateMessageInteractionInfo>()
            .onEach { update -> handleInteractionInfo(update) }
            .launchIn(scope)

        // Edits surface as a new editDate; we just stamp it onto the post.
        td.updates.filterIsInstance<TdApi.UpdateMessageEdited>()
            .onEach { update -> handleEdited(update) }
            .launchIn(scope)

        // Mods can delete posts; drop them from the timeline immediately.
        td.updates.filterIsInstance<TdApi.UpdateDeleteMessages>()
            .onEach { update -> handleDeleted(update) }
            .launchIn(scope)

        // Channel admins edit the body of a post — swap the rendered content in place.
        td.updates.filterIsInstance<TdApi.UpdateMessageContent>()
            .onEach { update -> handleContentChanged(update) }
            .launchIn(scope)

        // Pin / unpin badge changes on a channel post.
        td.updates.filterIsInstance<TdApi.UpdateMessageIsPinned>()
            .onEach { update -> handleIsPinnedChanged(update) }
            .launchIn(scope)

        // Channel renamed → all visible posts of that chat update their senderName.
        td.updates.filterIsInstance<TdApi.UpdateChatTitle>()
            .onEach { update -> handleChatTitle(update) }
            .launchIn(scope)

        // Channel avatar changed → all visible posts of that chat refresh avatar.
        td.updates.filterIsInstance<TdApi.UpdateChatPhoto>()
            .onEach { update -> handleChatPhoto(update) }
            .launchIn(scope)

        // User profile changed (rename, avatar). Drop our resolver cache so future renders
        // re-fetch — most posts already have their author baked in, so this is rare hot-path.
        td.updates.filterIsInstance<TdApi.UpdateUser>()
            .onEach { mapper.invalidateUser(it.user.id) }
            .launchIn(scope)

        // Supergroup metadata changed (handle/username) — invalidate so resolveChannelHandle
        // refetches next call.
        td.updates.filterIsInstance<TdApi.UpdateSupergroup>()
            .onEach { mapper.invalidateSupergroup(it.supergroup.id) }
            .launchIn(scope)

        // Brand-new chat appeared. We deliberately DO NOT issue GetChatHistory here:
        //   - On startup TDLib re-emits the entire chat list as UpdateNewChat; auto-loading
        //     each one 429-rate-limits the server.
        //   - Many of those chats are private / archived / DM — every one is a guaranteed
        //     [400] Can't access the chat warning.
        // Posts from a freshly-joined channel reach us anyway via UpdateNewMessage; the user
        // can pull-to-refresh for back-history. Just cache the metadata.
        td.updates.filterIsInstance<TdApi.UpdateNewChat>()
            .onEach { update -> chatCache[update.chat.id] = update.chat }
            .launchIn(scope)

        // Keep [archivedChatIds] live: TDLib fires UpdateChatAddedToList /
        // UpdateChatRemovedFromList whenever the user archives/unarchives a channel in
        // ANY client. Without these the "Усі" tab leaks a freshly-archived channel until
        // the next pull-to-refresh.
        td.updates.filterIsInstance<TdApi.UpdateChatAddedToList>()
            .onEach { update ->
                if (update.chatList is TdApi.ChatListArchive) {
                    _archivedChatIds.update { it + update.chatId }
                }
            }
            .launchIn(scope)

        td.updates.filterIsInstance<TdApi.UpdateChatRemovedFromList>()
            .onEach { update ->
                if (update.chatList is TdApi.ChatListArchive) {
                    _archivedChatIds.update { it - update.chatId }
                }
            }
            .launchIn(scope)
    }

    // 30 (not 20) so an album sitting on the limit boundary doesn't get split: most
    // albums are 2–6 members, so 30 is enough headroom for the latest few channel
    // posts to arrive whole. GetChatHistory itself returns members one-per-message, so
    // a 5-photo album consumes 5 of the 30 slots.
    suspend fun refresh(limitPerChannel: Int = 30): Result<Unit> = refreshMutex.withLock {
        runCatching { refreshLocked(limitPerChannel) }
            .onSuccess { lastRefreshAtMs = System.currentTimeMillis() }
            .warnUnlessCancelled("refresh")
    }

    /**
     * Refresh only if the last successful refresh is older than [maxAgeMs]. Reuses the same
     * mutex as [refresh] so a concurrent pull-to-refresh isn't stomped. A skip returns
     * [Result.success] — callers shouldn't treat "still fresh" as a failure.
     */
    suspend fun refreshIfStale(maxAgeMs: Long = 60_000L): Result<Unit> = refreshMutex.withLock {
        if (System.currentTimeMillis() - lastRefreshAtMs <= maxAgeMs) return@withLock Result.success(Unit)
        runCatching { refreshLocked(REFRESH_DEFAULT_LIMIT) }
            .onSuccess { lastRefreshAtMs = System.currentTimeMillis() }
            .warnUnlessCancelled("refreshIfStale")
    }

    /**
     * Tells TDLib the user is actively focused on [chatId]. The daemon prioritises updates
     * for this chat, prefetches history and treats subsequent [viewMessages] calls as
     * authoritative. Always pair with [closeChat] when focus moves away. Internally a
     * thin proxy to [ChatPresence] so all OpenChat/CloseChat traffic in the app flows
     * through one place.
     */
    suspend fun openChat(chatId: Long) = ChatPresence.openChat(td, chatId)

    /**
     * Loads up to [limit] additional history entries for [chatId] and folds them into the
     * shared feed. Used when the user filters to a single channel — a global refresh only
     * fetches a few latest posts per channel, so deep browsing one channel needs more.
     *
     * Single-flight: if a deep load is already in flight for [chatId], all callers await
     * the same [Deferred]. Cooldown: a successful load suppresses re-fetches for
     * [DEEP_LOAD_COOLDOWN_MS] — entering and leaving the channel filter back-to-back no
     * longer triggers a fresh GetChatHistory each time. Failed loads skip the cooldown
     * mark so the next entry retries.
     */
    suspend fun loadChannelHistory(chatId: Long, limit: Int = 80): Result<Unit> {
        val now = System.currentTimeMillis()
        deepLoadCooldownUntilMs[chatId]?.let { until ->
            if (now < until) return Result.success(Unit)
        }
        val deferred = deepLoadJobs.computeIfAbsent(chatId) {
            scope.async { runCatching { loadChannelHistoryLocked(chatId, limit) } }
        }
        val result = deferred.await()
        deepLoadJobs.remove(chatId, deferred)
        if (result.isSuccess) {
            deepLoadCooldownUntilMs[chatId] = System.currentTimeMillis() + DEEP_LOAD_COOLDOWN_MS
        }
        return result.warnUnlessCancelled(TAG, "loadChannelHistory($chatId)")
    }

    private suspend fun loadChannelHistoryLocked(chatId: Long, limit: Int) {
        val chat = chatCache[chatId] ?: td.send(TdApi.GetChat(chatId)).also { chatCache[chatId] = it }
        if (!chat.isChannel()) return

        val history = td.send(TdApi.GetChatHistory(chatId, /* fromMessageId */ 0, 0, limit, false))
        val raw = coalesceAlbumFragments(chatId, history.messages.orEmpty().toList())
        val mapped = raw.map { mapper.toChannelPost(it, chat) }

        _posts.update { current ->
            val seen = current.mapTo(mutableSetOf()) { it.chatId to it.id }
            val merged = current.addAll(mapped.filter { (it.chatId to it.id) !in seen })
            PostFilterStrategy.apply(merged).take(MAX_FEED_SIZE).toPersistentList()
        }
    }

    suspend fun closeChat(chatId: Long) = ChatPresence.closeChat(td, chatId)

    /** Per-channel "we already paginated to the bottom of TDLib's local store" sentinel. */
    private val pageEnded = ConcurrentHashMap.newKeySet<Long>()
    private val pageJobs = ConcurrentHashMap<Long, Deferred<Result<Int>>>()

    /**
     * Pull older posts for a channel, anchored on the oldest post we currently render.
     * Used by the timeline when the user scrolls near the bottom of a single-channel
     * feed and wants to read further back.
     *
     * Returns the number of newly added posts. Single-flight + sticky end-of-history flag
     * so an over-eager scroll listener can't fan out duplicate round-trips and won't keep
     * pinging TDLib once we've already learnt the channel has nothing older to give.
     */
    suspend fun loadOlder(chatId: Long, limit: Int = 30): Int {
        if (chatId in pageEnded) return 0
        val deferred = pageJobs.computeIfAbsent(chatId) {
            scope.async {
                runCatching { loadOlderLocked(chatId, limit) }
            }
        }
        val result = deferred.await()
        pageJobs.remove(chatId, deferred)
        return result.warnUnlessCancelled(TAG, "loadOlder($chatId)").getOrDefault(0)
    }

    private suspend fun loadOlderLocked(chatId: Long, limit: Int): Int {
        val oldestId = _posts.value
            .filter { it.chatId == chatId }
            .minOfOrNull { it.id }
            ?: return 0
        val chat = chatCache[chatId] ?: td.send(TdApi.GetChat(chatId)).also { chatCache[chatId] = it }
        if (!chat.isChannel()) return 0

        val history = td.send(
            TdApi.GetChatHistory(
                chatId,
                /* fromMessageId */ oldestId,
                /* offset */ 0,
                limit,
                /* onlyLocal */ false,
            ),
        )
        val raw = history.messages.orEmpty().toList()
        // TDLib returns an empty page once we've walked off the end of its locally-stored
        // history. Mark the sentinel so the scroll listener stops pinging.
        if (raw.isEmpty()) {
            pageEnded += chatId
            return 0
        }
        val coalesced = coalesceAlbumFragments(chatId, raw)
        val mapped = coalesced.map { mapper.toChannelPost(it, chat) }

        var added = 0
        _posts.update { current ->
            val seen = current.mapTo(mutableSetOf()) { it.chatId to it.id }
            val fresh = mapped.filter { (it.chatId to it.id) !in seen }
            added = fresh.size
            val merged = current.addAll(fresh)
            PostFilterStrategy.apply(merged).take(MAX_FEED_SIZE).toPersistentList()
        }
        // GetChatHistory(fromMessageId, offset=0, …) is INCLUSIVE on the boundary
        // message — at end-of-history TDLib still returns the single anchor message
        // back to us in a non-empty page. Without this guard the scroll listener would
        // re-fire loadOlder forever once the user reaches the channel's first post,
        // each time hitting GetChatHistory and getting the same one-message page. Treat
        // a zero-fresh batch as the sentinel and stop pinging.
        if (added == 0) pageEnded += chatId
        return added
    }

    /**
     * Full-text search inside a single channel. Returns mapped posts ordered newest-first,
     * which is how `SearchChatMessages` itself returns them — TDLib already paginates with
     * the offset/limit we pass, so this method is a single round-trip.
     *
     * Coalesces fragments from the same media album just like the regular timeline pipeline,
     * so a hit on a caption-bearing photo doesn't appear without its sibling photos.
     */
    suspend fun searchInChannel(chatId: Long, query: String, limit: Int = 50): List<TimelinePost> {
        if (query.isBlank()) return emptyList()
        val chat = chatCache[chatId] ?: runCatching { td.send(TdApi.GetChat(chatId)) }
            .warnUnlessCancelled(TAG, "searchInChannel/getChat")
            .getOrNull()?.also { chatCache[chatId] = it } ?: return emptyList()
        val result = runCatching {
            td.send(
                TdApi.SearchChatMessages(
                    chatId,
                    /* topicId */ null,
                    query,
                    /* senderId */ null,
                    /* fromMessageId */ 0,
                    /* offset */ 0,
                    limit,
                    /* filter */ null,
                ),
            )
        }.warnUnlessCancelled(TAG, "searchInChannel").getOrNull() ?: return emptyList()

        val raw = result.messages.orEmpty().toList()
        val coalesced = coalesceAlbumFragments(chatId, raw)
        val mapped = coalesced.map { mapper.toChannelPost(it, chat) }
        return PostFilterStrategy.apply(mapped)
    }

    /**
     * Subscriber count for a channel chat. Returns null when the chat is not a supergroup
     * (private 1:1, basic group) or TDLib reports an unknown count. Cheap — TDLib serves
     * this from its local supergroup cache; no network round-trip in steady state.
     */
    suspend fun channelSubscribers(chatId: Long): Int? {
        val chat = runCatching { td.send(TdApi.GetChat(chatId)) }
            .warnUnlessCancelled(TAG, "channelSubscribers/getChat")
            .getOrNull() ?: return null
        val supergroupId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: return null
        val sg = runCatching { td.send(TdApi.GetSupergroup(supergroupId)) }
            .warnUnlessCancelled(TAG, "channelSubscribers/getSupergroup")
            .getOrNull() ?: return null
        return sg.memberCount.takeIf { it > 0 }
    }

    /**
     * Registers that the user has seen the given messages in [chatId]. This is what bumps
     * channel view counters server-side; without it, the user's "view" never lands.
     */
    suspend fun viewMessages(chatId: Long, messageIds: List<Long>) {
        if (messageIds.isEmpty()) return
        runCatching {
            td.send(
                TdApi.ViewMessages(
                    chatId,
                    messageIds.toLongArray(),
                    /* source */ null,
                    // forceRead=false: bumps the server-side view counter (we still want
                    // that), but does NOT advance lastReadInboxMessageId, so the channel's
                    // unread badge in the official Telegram client stays put. Hortay is a
                    // read-only browser — silently clearing badges in another app would
                    // surprise the user.
                    /* forceRead */ false,
                ),
            )
        }.warnUnlessCancelled(TAG, "viewMessages($chatId)")
    }

    private fun handleNewMessage(message: TdApi.Message) {
        if (message.mediaAlbumId == 0L) {
            scope.launch { ingest(message.chatId, listOf(message)) }
            return
        }
        // Album member: stash in the per-album buffer and (re)arm a short debounce.
        // Each subsequent sibling resets the timer; once the burst quietens we flush
        // every accumulated member in a single _posts.update so PostFilterStrategy
        // sees them as one group.
        val key = message.chatId to message.mediaAlbumId
        albumBuffers.compute(key) { _, existing ->
            (existing ?: mutableListOf()).also { it += message }
        }
        albumDebounce[key]?.cancel()
        albumDebounce[key] = scope.launch {
            delay(ALBUM_DEBOUNCE_MS)
            albumDebounce.remove(key)
            val batch = albumBuffers.remove(key) ?: return@launch
            ingest(key.first, batch)
        }
    }

    private suspend fun ingest(chatId: Long, messages: List<TdApi.Message>) {
        val chat = chatCache[chatId] ?: runCatching { td.send(TdApi.GetChat(chatId)) }
            .getOrNull()
            ?.also { chatCache[it.id] = it }
            ?: return
        if (!chat.isChannel()) return

        // If a real-time burst still left an album fragmented (e.g. members spread across
        // >600 ms by upstream), probe the chat for the missing siblings before mapping.
        // Cheap if there are no fragments — early-returns immediately.
        val full = coalesceAlbumFragments(chatId, messages)

        val newPosts = full
            .map { mapper.toChannelPost(it, chat) }
            .filter { it.content !is PostContent.Unsupported }
        if (newPosts.isEmpty()) return

        _posts.update { current ->
            // When an incoming batch contains album members, [coalesceAlbumFragments] has
            // already fetched the full sibling set for each affected mediaAlbumId, so the
            // raw newPosts are authoritative for those albums. Drop any existing entry
            // (merged anchor or solo) participating in those album groups before re-running
            // PostFilterStrategy. Without this prune, a late sibling stacks the
            // already-merged anchor's items on top of the raw siblings and mergeAlbumMembers
            // duplicates the overlapping media inside one card.
            val incomingAlbumKeys = newPosts
                .filter { it.mediaAlbumId != 0L }
                .mapTo(HashSet()) { it.chatId to it.mediaAlbumId }
            val pruned = if (incomingAlbumKeys.isEmpty()) current
                else current.mutate { list ->
                    list.removeAll { p -> p.mediaAlbumId != 0L && (p.chatId to p.mediaAlbumId) in incomingAlbumKeys }
                }
            val existingKeys = pruned.mapTo(mutableSetOf()) { it.chatId to it.id }
            val addition = newPosts.filterNot { (it.chatId to it.id) in existingKeys }
            if (addition.isEmpty() && pruned === current) current
            else PostFilterStrategy.apply(pruned.addAll(addition)).take(MAX_FEED_SIZE).toPersistentList()
        }
    }

    /**
     * GetChatHistory returns N latest messages — when an album crosses the window edge,
     * only some of its members are inside. This pass detects single-member groups with a
     * non-zero mediaAlbumId (Telegram never emits a real album of size 1) and queries a
     * small window around the fragment to pick up the missing siblings.
     *
     * Concurrency: each fragment fires a parallel GetChatHistory; results are merged
     * synchronously after [awaitAll] so the seen-set has no race. Bounded by the number of
     * distinct album ids in the input — typically 0..2 per refresh batch, so cost is low.
     */
    private suspend fun coalesceAlbumFragments(
        chatId: Long,
        messages: List<TdApi.Message>,
    ): List<TdApi.Message> {
        val fragments = messages
            .filter { it.mediaAlbumId != 0L }
            .groupBy { it.mediaAlbumId }
            .values
            .filter { it.size == 1 }
            .map { it.single() }
        if (fragments.isEmpty()) return messages

        val seen = messages.mapTo(hashSetOf()) { it.id }
        val extras = coroutineScope {
            fragments.map { fragment ->
                async {
                    val resp = runCatching {
                        // fromMessageId in the middle of a 10-msg window: offset=-5 means
                        // "give me 5 newer + the anchor + 4 older". Albums are at most 10
                        // members so this almost always covers the whole group.
                        td.send(
                            TdApi.GetChatHistory(
                                chatId,
                                /* fromMessageId */ fragment.id,
                                /* offset */ -5,
                                /* limit */ 10,
                                /* onlyLocal */ false,
                            ),
                        )
                    }.warnUnlessCancelled(TAG, "coalesceAlbum($chatId,${fragment.id})").getOrNull()
                    resp?.messages.orEmpty().filter { it.mediaAlbumId == fragment.mediaAlbumId }
                }
            }.awaitAll()
        }

        if (extras.all { it.isEmpty() }) return messages
        val merged = messages.toMutableList()
        for (group in extras) {
            for (m in group) if (seen.add(m.id)) merged += m
        }
        return merged
    }

    /**
     * Buffer the update; if a flush isn't already scheduled, schedule one. A single
     * coroutine drains the buffer after [INTERACTION_INFO_COALESCE_MS] and writes all
     * pending mutations in one `_posts.update { mutate { ... } }` call — the persistent
     * list builds the new snapshot once, regardless of how many keys we touch.
     */
    private fun handleInteractionInfo(update: TdApi.UpdateMessageInteractionInfo) {
        // null interactionInfo: original handler resolved every field to its current value
        // (effectively no-op). Drop here so the buffer stays non-null for ConcurrentHashMap.
        val info = update.interactionInfo ?: return
        pendingInteractionInfo[update.chatId to update.messageId] = info
        if (interactionFlushScheduled.compareAndSet(false, true)) {
            scope.launch {
                delay(INTERACTION_INFO_COALESCE_MS)
                interactionFlushScheduled.set(false)
                flushPendingInteractionInfo()
            }
        }
    }

    private fun flushPendingInteractionInfo() {
        if (pendingInteractionInfo.isEmpty()) return
        // Single-pass drain: snapshot what's there now, atomically remove only those
        // entries. Updates that arrive *after* the snapshot stay in the map and trip the
        // next compareAndSet, so we don't lose any.
        val drained = HashMap<Pair<Long, Long>, TdApi.MessageInteractionInfo>()
        val it = pendingInteractionInfo.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            drained[e.key] = e.value
            it.remove()
        }
        if (drained.isEmpty()) return

        _posts.update { current ->
            current.mutate { list ->
                for ((key, info) in drained) {
                    val (chatId, messageId) = key
                    val idx = list.indexOfFirst { it.chatId == chatId && it.id == messageId }
                    if (idx == -1) continue
                    val cur = list[idx]
                    list[idx] = cur.copy(
                        views = info.viewCount,
                        // Preserve current reactions/comments when the inner field is null —
                        // TDLib often omits sub-fields it hasn't recomputed.
                        reactions = info.reactions?.let(::reactionsFromUpdate) ?: cur.reactions,
                        commentCount = info.replyInfo?.replyCount ?: cur.commentCount,
                    )
                }
            }
        }
    }

    private fun handleEdited(update: TdApi.UpdateMessageEdited) {
        // For an album the edit (almost always a caption tweak) targets one specific
        // sub-message id, but our merged anchor's id may be a different sibling. Stamp
        // editDate on the anchor whose albumMessageIds contains the touched id so the
        // "edited" badge refreshes regardless of which member the edit landed on.
        updateOnePostByAnyMemberId(update.chatId, update.messageId) {
            it.copy(editDate = update.editDate.toLong() * 1000L)
        }
    }

    private fun handleDeleted(update: TdApi.UpdateDeleteMessages) {
        if (!update.isPermanent) return
        val ids = update.messageIds.toHashSet()
        _posts.update { current ->
            current.mutate { list ->
                val toRemove = mutableListOf<Int>()
                for (i in list.indices) {
                    val post = list[i]
                    if (post.chatId != update.chatId) continue
                    val albumIds = post.albumMessageIds
                    if (albumIds.isEmpty()) {
                        if (post.id in ids) toRemove += i
                        continue
                    }
                    // Album: trim deleted members from items[] (mergeAlbumMembers builds
                    // items in albumMessageIds order, so they correspond by index). Drop
                    // the whole post if every member was deleted.
                    val survivedIds = albumIds.filterNot { it in ids }
                    if (survivedIds.size == albumIds.size) continue
                    if (survivedIds.isEmpty()) {
                        toRemove += i
                        continue
                    }
                    val keepIdx = albumIds.withIndex()
                        .filter { (_, id) -> id !in ids }
                        .map { (idx, _) -> idx }
                        .toSet()
                    val content = post.content
                    if (content is PostContent.PhotoAlbum) {
                        val newItems = content.items.filterIndexed { idx, _ -> idx in keepIdx }
                        list[i] = post.copy(
                            content = content.copy(items = newItems),
                            albumMessageIds = survivedIds,
                        )
                    } else {
                        // Album with non-PhotoAlbum content (shouldn't happen given how
                        // mergeAlbumMembers builds groups, but guard anyway). Drop it.
                        toRemove += i
                    }
                }
                for (idx in toRemove.asReversed()) list.removeAt(idx)
            }
        }
    }

    private fun handleContentChanged(update: TdApi.UpdateMessageContent) {
        // The post might be an already-merged album whose anchor id ≠ update.messageId
        // (the edited member is one of the siblings). Re-fetch + re-coalesce the whole
        // album: that's the only way to keep the merged caption + items in sync given
        // we don't track which item belongs to which member id at content level.
        // For solo posts the fast path simply replaces the content in place.
        val solo = updateOnePost(update.chatId, update.messageId) {
            it.copy(content = MessageContentMapper.map(update.newContent))
        }
        if (solo) return
        val anchor = _posts.value.firstOrNull {
            it.chatId == update.chatId && update.messageId in it.albumMessageIds
        } ?: return
        // Re-ingest: GetMessage for the touched id, push through the album debounce so
        // coalesceAlbumFragments fetches the rest and the regular ingest dedup logic
        // replaces the merged anchor cleanly.
        scope.launch {
            val msg = runCatching { td.send(TdApi.GetMessage(update.chatId, update.messageId)) }
                .warnUnlessCancelled(TAG, "getMessage(${update.chatId},${update.messageId})")
                .getOrNull() ?: return@launch
            handleNewMessage(msg)
        }
        // Bump editDate on the anchor so the "edited" indicator surfaces immediately
        // even before the re-ingest completes.
        updateOnePostByAnyMemberId(update.chatId, update.messageId) { post ->
            // Preserve existing editDate semantics if TDLib didn't pair an Edited update.
            if (post.editDate == anchor.editDate) post else post
        }
    }

    private fun handleIsPinnedChanged(update: TdApi.UpdateMessageIsPinned) {
        // Pin badge: TDLib pins one specific message; for an album in our timeline that
        // can be any sibling (Telegram typically pins the caption-carrier, but admins
        // can pin any). Match by either anchor id or any album member id and set the
        // anchor's isPinned to the update's value.
        updateOnePostByAnyMemberId(update.chatId, update.messageId) {
            it.copy(isPinned = update.isPinned)
        }
    }

    /**
     * Single-post update helper. PersistentList's `set(idx, value)` returns a new
     * snapshot in O(log N) via structural sharing — none of the unchanged entries are
     * copied. Compare with the old `current.toMutableList().also { it[idx] = ... }`
     * which copied the whole array on every event.
     *
     * Returns true iff a matching post was found and updated, so callers can chain a
     * fallback (e.g. album-aware lookup) without re-walking the list.
     */
    private inline fun updateOnePost(
        chatId: Long,
        messageId: Long,
        crossinline transform: (TimelinePost) -> TimelinePost,
    ): Boolean {
        var hit = false
        _posts.update { current ->
            val idx = current.indexOfFirst { it.chatId == chatId && it.id == messageId }
            if (idx == -1) current
            else { hit = true; current.set(idx, transform(current[idx])) }
        }
        return hit
    }

    /**
     * Same as [updateOnePost] but matches by anchor id OR any of the post's
     * [TimelinePost.albumMessageIds]. Use this for events whose [messageId] can refer
     * to any member of an already-merged album (UpdateMessageEdited,
     * UpdateMessageIsPinned, UpdateMessageContent on a non-anchor sibling).
     */
    private inline fun updateOnePostByAnyMemberId(
        chatId: Long,
        messageId: Long,
        crossinline transform: (TimelinePost) -> TimelinePost,
    ): Boolean {
        var hit = false
        _posts.update { current ->
            val idx = current.indexOfFirst { post ->
                post.chatId == chatId &&
                    (post.id == messageId || messageId in post.albumMessageIds)
            }
            if (idx == -1) current
            else { hit = true; current.set(idx, transform(current[idx])) }
        }
        return hit
    }

    private fun handleChatTitle(update: TdApi.UpdateChatTitle) {
        chatCache[update.chatId]?.let { it.title = update.title }
        _posts.update { current ->
            current.mutate { list ->
                for (i in list.indices) {
                    val post = list[i]
                    if (post.chatId == update.chatId) {
                        list[i] = post.copy(senderName = update.title.orEmpty())
                    }
                }
            }
        }
    }

    private fun handleChatPhoto(update: TdApi.UpdateChatPhoto) {
        chatCache[update.chatId]?.let { it.photo = update.photo }
        val newThumb = update.photo?.minithumbnail?.data
        val newFileId = update.photo?.small?.id
        _posts.update { current ->
            current.mutate { list ->
                for (i in list.indices) {
                    val post = list[i]
                    if (post.chatId == update.chatId) {
                        list[i] = post.copy(avatarThumb = newThumb, avatarFileId = newFileId)
                    }
                }
            }
        }
    }

    private fun reactionsFromUpdate(reactions: TdApi.MessageReactions): Reactions =
        MessageContentMapper.mapReactions(reactions)

    /**
     * Set of chatIds the user has archived in Telegram. Populated alongside the main
     * refresh so the feed UI can surface a dedicated "Архів" tab without a separate query.
     * Stays a [StateFlow] so the tab can show / hide based on whether the user actually
     * has anything archived.
     */
    private val _archivedChatIds = MutableStateFlow<Set<Long>>(emptySet())
    val archivedChatIds: StateFlow<Set<Long>> = _archivedChatIds.asStateFlow()

    private suspend fun refreshLocked(limitPerChannel: Int) {
        // Prime BOTH the main list and the archive — TDLib serves "have we seen these
        // chatIds yet" per ChatList, and a chat archived in Telegram is invisible to
        // GetChats(ChatListMain). Loading archive separately is what surfaces it for the
        // "Архів" tab.
        //
        // Drain LoadChats until TDLib reports no more pages for each list. The previous
        // implementation called LoadChats once and went straight to GetChats — on a cold
        // start (where TDLib's local DB is still hydrating) that returned an empty/short
        // list and the user saw a blank feed for a couple of seconds until UpdateNewChat
        // events caught up. Looping until LoadChats fails (TDLib signals "no more chats"
        // with [400] Chat list is empty) is the canonical way per TDLib docs.
        drainChatList(TdApi.ChatListMain())
        drainChatList(TdApi.ChatListArchive())

        val mainIds = td.send(TdApi.GetChats(TdApi.ChatListMain(), CHAT_LIST_LIMIT)).chatIds
        val archiveIds = runCatching {
            td.send(TdApi.GetChats(TdApi.ChatListArchive(), CHAT_LIST_LIMIT)).chatIds.toList()
        }.getOrElse { emptyList() }
        _archivedChatIds.value = archiveIds.toSet()

        // Single channel set — duplicates collapse via toSet, then we go back to a List so
        // we can preserve ordering for the per-chat fetch.
        val chatIds = (mainIds.toList() + archiveIds).distinct().toLongArray()

        // Per-channel fetch was serial — for a user with 200 channels, ~50ms per round-trip
        // adds up to ~10s pull-to-refresh. Bound concurrency at 4 (TDLib's typical
        // simultaneous-download cap) so we don't push the daemon into FLOOD_WAIT while
        // still saturating the network.
        val semaphore = Semaphore(REFRESH_CONCURRENCY)
        val raw = coroutineScope {
            chatIds.toList().map { chatId ->
                async {
                    semaphore.withPermit {
                        val chat = runCatching { td.send(TdApi.GetChat(chatId)) }.getOrNull()
                            ?: return@withPermit emptyList()
                        chatCache[chatId] = chat
                        if (!chat.isChannel()) return@withPermit emptyList()

                        val history = runCatching {
                            td.send(TdApi.GetChatHistory(chatId, /* fromMessageId */ 0, 0, limitPerChannel, false))
                        }.getOrNull() ?: return@withPermit emptyList()

                        val raw = history.messages.orEmpty().toList()
                        // Plug album fragments left by the window boundary (e.g. a 6-photo
                        // album whose first 5 members fell outside the latest-N slice).
                        coalesceAlbumFragments(chatId, raw).map { mapper.toChannelPost(it, chat) }
                    }
                }
            }.awaitAll().flatten()
        }

        // Atomic merge instead of an outright `_posts.value = ...` overwrite. A refresh
        // takes seconds: while the chat-by-chat fetches run, concurrent TD updates
        // (UpdateNewMessage, UpdateMessageInteractionInfo, UpdateMessageEdited,
        // UpdateMessageContent, UpdateDeleteMessages) flow through their handlers and
        // mutate `_posts` via `.update {}`. A non-atomic final assignment would clobber
        // every one of those edits — most visibly losing brand-new posts that landed
        // mid-refresh and were correctly ingested but absent from the snapshot we took
        // before they arrived.
        //
        // Merge policy: `raw` is authoritative for any (chatId, id) it carries (TDLib
        // just told us this is the current state). For everything else in `current`
        // (older posts paginated via loadOlder, posts ingested concurrently after our
        // GetChatHistory snapshot) we keep the existing entry. PostFilterStrategy then
        // re-merges albums and re-sorts.
        _posts.update { current ->
            val freshKeys = raw.mapTo(HashSet()) { it.chatId to it.id }
            val keptOld = current.filterNot { post ->
                val keys = post.albumMessageIds.ifEmpty { listOf(post.id) }
                keys.any { id -> (post.chatId to id) in freshKeys }
            }
            PostFilterStrategy.apply(raw + keptOld).take(MAX_FEED_SIZE).toPersistentList()
        }
    }

    /**
     * Repeatedly call [TdApi.LoadChats] until TDLib runs out of pages for [list]. TDLib
     * signals "no more chats to load" with a `404 Not Found` error specifically — any
     * other error (network blip, auth race) is transient and would historically have
     * caused an early exit and a partially-populated chat list on cold start. Distinguish
     * the two: 404 → terminate (we're done). Other errors → swallow this iteration and
     * try the next; a transient blip should not strand the user with half a feed.
     *
     * Bounded by [MAX_LOAD_CHATS_PAGES] — 10 pages × 200 hint = up to 2000 chats per list,
     * which is well past the realistic ceiling and protects against a TDLib bug ever
     * returning success indefinitely.
     */
    private suspend fun drainChatList(list: TdApi.ChatList) {
        repeat(MAX_LOAD_CHATS_PAGES) {
            val res = runCatching { td.send(TdApi.LoadChats(list, CHAT_LIST_HINT)) }
            val err = res.exceptionOrNull()
            if (err is TdClient.TdException && err.code == 404) return
            // Any other failure: retry on the next iteration; the bounded loop guards
            // against an infinite retry storm if TDLib stays unhealthy.
        }
    }

    private companion object {
        const val TAG = "PostsRepository"
        const val CHAT_LIST_HINT = 200
        const val CHAT_LIST_LIMIT = 500
        const val MAX_LOAD_CHATS_PAGES = 10
        const val MAX_FEED_SIZE = 1_000
        const val REFRESH_DEFAULT_LIMIT = 30
        // ~600ms is what the official Telegram client uses to coalesce album bursts.
        // Shorter loses tail members on slow networks; longer makes albums feel laggy.
        const val ALBUM_DEBOUNCE_MS = 600L
        // Aligns with TDLib's default ~4 simultaneous downloads — same shape, same back-
        // pressure profile.
        const val REFRESH_CONCURRENCY = 4
        // 60s is long enough that quick back-and-forth between channels reuses the cached
        // history, short enough that a deliberate "refresh by re-entering" still works
        // within a normal browsing session.
        const val DEEP_LOAD_COOLDOWN_MS = 60_000L
        // 200ms balances perceived latency (counters update fast enough to feel live)
        // against burst suppression. Telegram's official Android client coalesces in a
        // similar window.
        const val INTERACTION_INFO_COALESCE_MS = 200L
    }
}

private fun TdApi.Chat.isChannel(): Boolean {
    val type = this.type
    return type is TdApi.ChatTypeSupergroup && type.isChannel
}
