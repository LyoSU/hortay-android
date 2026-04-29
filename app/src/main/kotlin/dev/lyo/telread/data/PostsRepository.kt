package dev.lyo.telread.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
 */
class PostsRepository(
    private val td: TdClient,
    private val scope: CoroutineScope,
) {

    private val refreshMutex = Mutex()
    private val chatCache = ConcurrentHashMap<Long, TdApi.Chat>()
    private val mapper = MessageMapper(td)

    // Album coalescing: TDLib emits one UpdateNewMessage per album member with no
    // "album complete" signal (issue tdlib/td#2523). We buffer members per (chatId,
    // mediaAlbumId) and flush once the burst quietens — same approach as the official
    // Telegram client. Without this, an album posts as "1 photo" → "2 photos" → … and
    // can leave a stale single-member card in the feed if later members lag.
    private val albumBuffers = ConcurrentHashMap<Pair<Long, Long>, MutableList<TdApi.Message>>()
    private val albumDebounce = ConcurrentHashMap<Pair<Long, Long>, Job>()

    private val _posts = MutableStateFlow<List<TimelinePost>>(emptyList())
    val posts: StateFlow<List<TimelinePost>> = _posts.asStateFlow()

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

        // Channel renamed → all visible posts of that chat update their channelTitle.
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
        runCatching { refreshLocked(limitPerChannel) }.warnUnlessCancelled("refresh")
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
     */
    suspend fun loadChannelHistory(chatId: Long, limit: Int = 80): Result<Unit> = runCatching {
        val chat = chatCache[chatId] ?: td.send(TdApi.GetChat(chatId)).also { chatCache[chatId] = it }
        if (!chat.isChannel()) return@runCatching

        val history = td.send(TdApi.GetChatHistory(chatId, /* fromMessageId */ 0, 0, limit, false))
        val mapped = history.messages.orEmpty().map { mapper.toTimelinePost(it, chat) }

        _posts.update { current ->
            val seen = current.mapTo(mutableSetOf()) { it.chatId to it.id }
            val merged = current + mapped.filter { (it.chatId to it.id) !in seen }
            PostFilterStrategy.apply(merged).take(MAX_FEED_SIZE)
        }
    }.warnUnlessCancelled(TAG, "loadChannelHistory($chatId)")

    suspend fun closeChat(chatId: Long) {
        runCatching { td.send(TdApi.CloseChat(chatId)) }.warnUnlessCancelled(TAG, "closeChat($chatId)")
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

        val newPosts = messages
            .map { mapper.toTimelinePost(it, chat) }
            .filter { it.content !is PostContent.Unsupported }
        if (newPosts.isEmpty()) return

        _posts.update { current ->
            val existingKeys = current.mapTo(mutableSetOf()) { it.chatId to it.id }
            val addition = newPosts.filterNot { (it.chatId to it.id) in existingKeys }
            if (addition.isEmpty()) current
            else PostFilterStrategy.apply(current + addition).take(MAX_FEED_SIZE)
        }
    }

    private fun handleInteractionInfo(update: TdApi.UpdateMessageInteractionInfo) {
        val info = update.interactionInfo
        updateOnePost(update.chatId, update.messageId) { post ->
            post.copy(
                views = info?.viewCount ?: post.views,
                reactions = info?.reactions?.let(::reactionsFromUpdate) ?: post.reactions,
                // ^ keep current value when interaction info arrives without replyInfo;
                //   fresh `null` only happens at first map() in MessageMapper.
                commentCount = info?.replyInfo?.replyCount ?: post.commentCount,
            )
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
            current.filterNot { it.chatId == update.chatId && it.id in ids }
        }
    }

    private fun handleContentChanged(update: TdApi.UpdateMessageContent) {
        val newContent = MessageContentMapper.map(update.newContent)
        updateOnePost(update.chatId, update.messageId) { it.copy(content = newContent) }
    }

    /**
     * Single-post update helper. UpdateMessageInteractionInfo can fire dozens of times
     * per second on a busy channel; the previous implementation copied the entire feed
     * via `current.map { ... }` for every event — O(N) closure invocations and O(N)
     * allocations per update. indexOfFirst short-circuits on the first match, then we
     * mutate one slot in a fresh list. The list copy is still O(N) but skips per-item
     * lambda dispatch and allocation.
     */
    private inline fun updateOnePost(
        chatId: Long,
        messageId: Long,
        crossinline transform: (TimelinePost) -> TimelinePost,
    ) {
        _posts.update { current ->
            val idx = current.indexOfFirst { it.chatId == chatId && it.id == messageId }
            if (idx == -1) current
            else current.toMutableList().also { it[idx] = transform(current[idx]) }
        }
    }

    private fun handleChatTitle(update: TdApi.UpdateChatTitle) {
        chatCache[update.chatId]?.let { it.title = update.title }
        _posts.update { current ->
            current.map { post ->
                if (post.chatId == update.chatId) post.copy(channelTitle = update.title.orEmpty())
                else post
            }
        }
    }

    private fun handleChatPhoto(update: TdApi.UpdateChatPhoto) {
        chatCache[update.chatId]?.let { it.photo = update.photo }
        val newThumb = update.photo?.minithumbnail?.data
        val newFileId = update.photo?.small?.id
        _posts.update { current ->
            current.map { post ->
                if (post.chatId == update.chatId) {
                    post.copy(avatarThumb = newThumb, avatarFileId = newFileId)
                } else post
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

                        history.messages.orEmpty().map { mapper.toTimelinePost(it, chat) }
                    }
                }
            }.awaitAll().flatten()
        }

        _posts.value = PostFilterStrategy.apply(raw)
    }

    private companion object {
        const val TAG = "PostsRepository"
        const val CHAT_LIST_HINT = 200
        const val CHAT_LIST_LIMIT = 500
        const val MAX_FEED_SIZE = 1_000
        // ~600ms is what the official Telegram client uses to coalesce album bursts.
        // Shorter loses tail members on slow networks; longer makes albums feel laggy.
        const val ALBUM_DEBOUNCE_MS = 600L
        // Aligns with TDLib's default ~4 simultaneous downloads — same shape, same back-
        // pressure profile.
        const val REFRESH_CONCURRENCY = 4
    }
}

private fun TdApi.Chat.isChannel(): Boolean {
    val type = this.type
    return type is TdApi.ChatTypeSupergroup && type.isChannel
}
