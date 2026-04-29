package dev.lyo.telread.data

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
     * authoritative. Always pair with [closeChat] when focus moves away.
     */
    suspend fun openChat(chatId: Long) {
        runCatching { td.send(TdApi.OpenChat(chatId)) }.warnUnlessCancelled(TAG, "openChat($chatId)")
    }

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

    suspend fun closeChat(chatId: Long) {
        runCatching { td.send(TdApi.CloseChat(chatId)) }.warnUnlessCancelled(TAG, "closeChat($chatId)")
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
                    // unread badge in the official Telegram client stays put. Telread is a
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
            val existingKeys = current.mapTo(mutableSetOf()) { it.chatId to it.id }
            val addition = newPosts.filterNot { (it.chatId to it.id) in existingKeys }
            if (addition.isEmpty()) current
            else PostFilterStrategy.apply(current.addAll(addition)).take(MAX_FEED_SIZE).toPersistentList()
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
        updateOnePost(update.chatId, update.messageId) {
            it.copy(editDate = update.editDate.toLong() * 1000L)
        }
    }

    private fun handleDeleted(update: TdApi.UpdateDeleteMessages) {
        if (!update.isPermanent) return
        val ids = update.messageIds.toHashSet()
        _posts.update { current ->
            current.mutate { list -> list.removeAll { it.chatId == update.chatId && it.id in ids } }
        }
    }

    private fun handleContentChanged(update: TdApi.UpdateMessageContent) {
        val newContent = MessageContentMapper.map(update.newContent)
        updateOnePost(update.chatId, update.messageId) { it.copy(content = newContent) }
    }

    /**
     * Single-post update helper. PersistentList's `set(idx, value)` returns a new
     * snapshot in O(log N) via structural sharing — none of the unchanged entries are
     * copied. Compare with the old `current.toMutableList().also { it[idx] = ... }`
     * which copied the whole array on every event.
     */
    private inline fun updateOnePost(
        chatId: Long,
        messageId: Long,
        crossinline transform: (TimelinePost) -> TimelinePost,
    ) {
        _posts.update { current ->
            val idx = current.indexOfFirst { it.chatId == chatId && it.id == messageId }
            if (idx == -1) current
            else current.set(idx, transform(current[idx]))
        }
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

    private suspend fun refreshLocked(limitPerChannel: Int) {
        runCatching { td.send(TdApi.LoadChats(TdApi.ChatListMain(), CHAT_LIST_HINT)) }

        val chatIds = td.send(TdApi.GetChats(TdApi.ChatListMain(), CHAT_LIST_LIMIT)).chatIds

        // Per-channel fetch was serial — for a user with 200 channels, ~50ms per round-trip
        // adds up to ~10s pull-to-refresh. Bound concurrency at 4 (TDLib's typical
        // simultaneous-download cap) so we don't push the daemon into FLOOD_WAIT while
        // still saturating the network.
        val semaphore = Semaphore(REFRESH_CONCURRENCY)
        val raw = coroutineScope {
            chatIds.map { chatId ->
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

        _posts.value = PostFilterStrategy.apply(raw).toPersistentList()
    }

    private companion object {
        const val TAG = "PostsRepository"
        const val CHAT_LIST_HINT = 200
        const val CHAT_LIST_LIMIT = 500
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
