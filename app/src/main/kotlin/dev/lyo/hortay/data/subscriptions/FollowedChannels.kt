package dev.lyo.hortay.data.subscriptions

import dev.lyo.hortay.data.TdSender
import dev.lyo.hortay.data.discover.isChannelMember
import dev.lyo.hortay.data.posts.FollowedRefresh
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.suspendRunCatching
import dev.lyo.hortay.data.warnUnlessCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * "Read without joining" for the signed-in account: follow / unfollow a public channel, the
 * cold-start restore of followed channels, and the hand-off to Telegram when the user joins
 * one. The store is [FollowedChannelsStore]; the periodic refresh is
 * [FollowedChannelsRefresher]; the feed side lives in [PostsRepository]. See ARCHITECTURE.md
 * → "Read without joining".
 *
 * The account never calls `JoinChat` here: a followed channel is read through
 * `GetChatHistory`, which works for a public channel the account is not a member of, and
 * the read position is kept locally because Telegram keeps none for a non-member.
 */
class FollowedChannels(
    private val td: TdSender,
    private val store: FollowedChannelsStore,
    private val posts: PostsRepository,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    enum class FollowResult { Followed, AlreadyFollowed, LimitReached, AlreadyMember, NotPublicChannel, Unavailable }

    private val startLock = Any()

    /** The current session's watchers; guarded by [startLock]. */
    private var sessionJob: Job? = null

    /**
     * Follow [chatId]. Only a public CHANNEL the account is not a member of qualifies —
     * checked offline (`GetChat` / `GetSupergroup` are served from TDLib's local store, the
     * same reads as [KnownChannelMembership]). The membership answer comes from
     * `GetSupergroup`, not `GetChat`: a chat object exists for a channel the account left or
     * only previewed.
     *
     * On success the entry is stored, then seeded with ONE user-initiated `GetChatHistory`
     * of [FOLLOWED_SEED_LIMIT] (no window wait — the user is waiting; TdClient still counts
     * it). The seeded history enters the feed AS READ: the read cursor and last-seen id both
     * move to its newest id. A seed that fails offline keeps the entry; the refresher's first
     * successful pass seeds the cursor the same way.
     *
     * @param restoring an entry [unfollow] returned, for an undo: its read cursor is kept,
     *   so posts published since the unfollow show as unread.
     */
    suspend fun follow(chatId: Long, restoring: FollowedChannel? = null): FollowResult {
        val chat = suspendRunCatching { td.send(TdApi.GetChat(chatId)) }.getOrNull()
            ?: return FollowResult.Unavailable
        val type = chat.type as? TdApi.ChatTypeSupergroup
        if (type == null || !type.isChannel) return FollowResult.NotPublicChannel
        val group = suspendRunCatching { td.send(TdApi.GetSupergroup(type.supergroupId)) }.getOrNull()
            ?: return FollowResult.Unavailable
        if (!group.isChannel) return FollowResult.NotPublicChannel
        if (isChannelMember(group.status)) return FollowResult.AlreadyMember
        val username = group.usernames?.activeUsernames?.firstOrNull() ?: return FollowResult.NotPublicChannel

        val entry = restoring?.copy(title = chat.title.orEmpty(), username = username, status = FollowedChannel.Status.Ok)
            ?: FollowedChannel(
                chatId = chatId,
                username = username,
                title = chat.title.orEmpty(),
                addedAtMs = nowMs(),
                readCursor = 0L,
                lastSeenMessageId = 0L,
                status = FollowedChannel.Status.Ok,
            )
        when (store.add(entry)) {
            AddResult.AlreadyFollowed -> return FollowResult.AlreadyFollowed
            AddResult.LimitReached -> return FollowResult.LimitReached
            AddResult.Added -> Unit
        }
        val seed = suspendRunCatching {
            posts.refreshFollowedChannel(chatId, limit = FOLLOWED_SEED_LIMIT, sinceMessageId = entry.lastSeenMessageId)
        }.warnUnlessCancelled(TAG, "seed($chatId)").getOrDefault(FollowedRefresh.Failed)
        when (seed) {
            is FollowedRefresh.NewPosts -> {
                if (entry.readCursor <= 0L) store.advanceReadCursor(chatId, seed.newestId)
                store.markSeen(chatId, seed.newestId)
            }
            FollowedRefresh.Unavailable -> {
                store.remove(chatId)
                return FollowResult.Unavailable
            }
            else -> Unit
        }
        return FollowResult.Followed
    }

    /** Stop following [chatId]; returns the removed entry for an undo ([follow] `restoring`). */
    suspend fun unfollow(chatId: Long): FollowedChannel? = store.remove(chatId)

    /**
     * Start this session's background work in [scope] (the account session scope — a logout
     * cancels it): the offline cold-start restore, the join hand-off and title / username
     * upkeep. Idempotent while the previous start is still running, so a repeated `Ready`
     * edge does not double the watchers.
     */
    fun start(scope: CoroutineScope) {
        synchronized(startLock) {
            if (sessionJob?.isActive == true) return
            sessionJob = scope.launch {
                launch { restoreLocally() }
                launch { watchJoins() }
                launch { watchMeta() }
            }
        }
    }

    /** One `GetMessageLocally` per followed channel — see [PostsRepository.restoreFollowedLocally]. */
    private suspend fun restoreLocally() {
        store.awaitLoaded()
        val lastSeen = store.channels.value.mapValues { it.value.lastSeenMessageId }
        if (lastSeen.isNotEmpty()) posts.restoreFollowedLocally(lastSeen)
    }

    /**
     * Join hand-off. A followed channel showing up in a chat list means the account joined
     * it — in another client, or through a Join button here. Its LOCAL read position is
     * pushed to Telegram first and only then is the entry dropped: the server held no cursor
     * for the account while it wasn't a member, so the joined channel would otherwise open
     * with every post unread. The push is NonCancellable inside the repository.
     */
    private suspend fun watchJoins() {
        combine(posts.mainChatIds, posts.archivedChatIds, store.ids) { main, archived, followed ->
            followed.filterTo(HashSet()) { it in main || it in archived }
        }
            .distinctUntilChanged()
            .filter { it.isNotEmpty() }
            .collect { joined -> joined.forEach { handOff(it) } }
    }

    private suspend fun handOff(chatId: Long) {
        val entry = store.channels.value[chatId] ?: return
        posts.pushFollowedReadPosition(chatId, entry.readCursor)
        store.remove(chatId)
    }

    /**
     * Keeps the stored title and handle current from the update stream, so a list built from
     * the store (the Channels tab) does not show a renamed channel's old name. Cheap:
     * a non-followed chat is one map lookup, and the store writes only on a real change.
     */
    private suspend fun watchMeta() {
        td.updates.collect { update ->
            when (update) {
                is TdApi.UpdateChatTitle -> {
                    val entry = store.channels.value[update.chatId] ?: return@collect
                    store.updateMeta(update.chatId, update.title.orEmpty(), entry.username)
                }
                is TdApi.UpdateSupergroup -> {
                    val group = update.supergroup ?: return@collect
                    val chatId = supergroupChatId(group.id)
                    val entry = store.channels.value[chatId] ?: return@collect
                    store.updateMeta(chatId, entry.title, group.usernames?.activeUsernames?.firstOrNull())
                }
                else -> Unit
            }
        }
    }

    private companion object {
        const val TAG = "FollowedChannels"
    }
}

/** TDLib's chat id for supergroup / channel [supergroupId] (`-10^12 - id`). */
internal fun supergroupChatId(supergroupId: Long): Long = -1_000_000_000_000L - supergroupId

/**
 * History a fresh follow is seeded with: one user-initiated `GetChatHistory`, so the channel
 * lands in the feed with a screenful of context. All of it enters as read.
 */
const val FOLLOWED_SEED_LIMIT = 20
