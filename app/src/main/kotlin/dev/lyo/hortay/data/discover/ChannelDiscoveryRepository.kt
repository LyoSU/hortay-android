package dev.lyo.hortay.data.discover

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.TdSender
import dev.lyo.hortay.data.warnUnlessCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * TDLib-side channel discovery for authenticated mode: resolve a curated handle to
 * a live card, search public channels by name, all served without ever touching
 * `t.me/s` (the privacy boundary — authenticated mode talks to Telegram only).
 *
 * Throttling: [SearchPublicChat]/[SearchPublicChats] hit the server when the chat
 * isn't already cached. Curated hydration fans out across ~20 handles on sheet
 * open, so resolves are serialized behind [throttle] at ~1/sec — the same ceiling
 * the guest→TDLib subscription migration uses (tdlib/td#743 flood-control). Already
 * resolved handles short-circuit from [resolveCache] with no RPC, so a re-opened
 * sheet paints instantly.
 *
 * Session-scoped: [resolveCache] carries `isMember`, which is account-specific, so
 * [clear] is registered with `AppGraph.session.onEnd`. [backgroundScope] is the current
 * session's scope, read at call time (the session replaces it on logout).
 */
class ChannelDiscoveryRepository(
    private val td: TdSender,
    private val backgroundScope: () -> CoroutineScope,
) {
    private val resolveCache = ConcurrentHashMap<String, DiscoverChannel>()
    private val similarCache = ConcurrentHashMap<Long, List<DiscoverChannel>>()
    private val throttle = Mutex()

    @Volatile
    private var lastCallAt = 0L

    /** Resolve a public `@handle` (no leading @) to a live card, or null if it can't. */
    suspend fun resolve(username: String): DiscoverChannel? {
        val key = username.lowercase()
        resolveCache[key]?.let { return it }
        val chat = throttled {
            runCatching { td.send(TdApi.SearchPublicChat(username.removePrefix("@").trim())) }
                .warnUnlessCancelled(TAG, "resolve($username)")
                .getOrNull()
        } ?: return null
        return toCard(chat)?.also { resolveCache[key] = it }
    }

    /** Free-text public-channel search by name. Returns up to TDLib's page (~50). */
    suspend fun search(query: String): List<DiscoverChannel> {
        val cleaned = query.trim()
        if (cleaned.isBlank()) return emptyList()
        // TDLib 1.8.66 added a typeFilter arg to SearchPublicChats; null keeps the prior
        // behaviour (search every chat type, then narrow to channels in toCard).
        val chats = runCatching { td.send(TdApi.SearchPublicChats(cleaned, null)) }
            .warnUnlessCancelled(TAG, "search($cleaned)")
            .getOrNull() ?: return emptyList()
        // GetChat is local after SearchPublicChats populated TDLib's cache, so this
        // fan-out is cheap (no per-result server round-trip). Subscriber counts are
        // left null for search hits — fetching GetSupergroup per row would be a real
        // RPC burst; the curated path (which resolves a known small set) carries subs.
        val out = ArrayList<DiscoverChannel>(chats.chatIds.size)
        for (id in chats.chatIds) {
            val chat = runCatching { td.send(TdApi.GetChat(id)) }
                .warnUnlessCancelled(TAG, "search/getChat")
                .getOrNull() ?: continue
            toCard(chat, withSubscribers = false)?.let(out::add)
        }
        return out
    }

    /**
     * Channels Telegram recommends next to [chatId] (td_api.tl `getChatSimilarChats`:
     * "Returns a list of chats similar to the given chat"), minus ones the user already
     * follows. One RPC per channel per session — cached, since the list is server-curated
     * and changes slowly.
     */
    suspend fun similar(chatId: Long): List<DiscoverChannel> {
        similarCache[chatId]?.let { cached -> return cached.filterNot { it.isMember } }
        val chats = runCatching { td.send(TdApi.GetChatSimilarChats(chatId)) }
            .warnUnlessCancelled(TAG, "similar($chatId)")
            .getOrNull() ?: return emptyList()
        val out = ArrayList<DiscoverChannel>(chats.chatIds.size)
        for (id in chats.chatIds) {
            // Local after getChatSimilarChats returned the chats.
            val chat = runCatching { td.send(TdApi.GetChat(id)) }
                .warnUnlessCancelled(TAG, "similar/getChat")
                .getOrNull() ?: continue
            toCard(chat)?.let(out::add)
        }
        similarCache[chatId] = out
        return out.filterNot { it.isMember }
    }

    /**
     * Card for a chat TDLib already knows (a similar-chats id, a cached channel) from local
     * calls only — `GetChat` + `GetSupergroup` are both served from TDLib's database. Null
     * for non-channels and chats TDLib has no record of. Never `GetSupergroupFullInfo`:
     * that is a server call on the tightest bucket and must not run per candidate.
     */
    suspend fun offlineCard(chatId: Long): DiscoverChannel? {
        val chat = runCatching { td.send(TdApi.GetChat(chatId)) }
            .warnUnlessCancelled(TAG, "offlineCard/getChat")
            .getOrNull() ?: return null
        return toCard(chat)
    }

    /**
     * Tell TDLib the user opened [openedChatId] from [sourceChatId]'s similar-channels list
     * (td_api.tl `openChatSimilarChat`: "Informs TDLib that a chat was opened from the list
     * of similar chats"). Fire-and-forget in the session scope — a failure changes nothing
     * the user can see, so it is logged and dropped.
     */
    fun reportSimilarOpened(sourceChatId: Long, openedChatId: Long) {
        if (sourceChatId == 0L || openedChatId == 0L) return
        backgroundScope().launch {
            runCatching { td.send(TdApi.OpenChatSimilarChat(sourceChatId, openedChatId)) }
                .warnUnlessCancelled(TAG, "openChatSimilarChat")
        }
    }

    /**
     * Pace a server-bound call behind the same ~1/s gate the curated resolves use, so the
     * recommended-channels scan and the curated hydration never stack their bursts.
     */
    internal suspend fun <T> paced(block: suspend () -> T): T = throttled(block)

    fun clear() {
        resolveCache.clear()
        similarCache.clear()
    }

    /**
     * Keep cached cards' [DiscoverChannel.isMember] true to a join / leave that just
     * succeeded. Without it a re-opened sheet painted the pre-join membership from
     * [resolveCache] (offering Subscribe for a joined channel, or hiding a left one)
     * for the rest of the session.
     */
    fun updateMembership(chatId: Long, isMember: Boolean) {
        for ((key, card) in resolveCache) {
            if (card.chatId == chatId && card.isMember != isMember) {
                resolveCache.replace(key, card, card.copy(isMember = isMember))
            }
        }
        for ((key, list) in similarCache) {
            if (list.any { it.chatId == chatId && it.isMember != isMember }) {
                similarCache.replace(key, list, list.map { if (it.chatId == chatId) it.copy(isMember = isMember) else it })
            }
        }
    }

    private suspend fun toCard(chat: TdApi.Chat, withSubscribers: Boolean = true): DiscoverChannel? {
        val supergroupId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId
        // Channels only — a public group/user resolved by an exact handle isn't a
        // feed channel, so skip it rather than offer a non-channel suggestion.
        val supergroup = supergroupId?.let {
            runCatching { td.send(TdApi.GetSupergroup(it)) }
                .warnUnlessCancelled(TAG, "toCard/getSupergroup")
                .getOrNull()
        } ?: return null
        if (!supergroup.isChannel) return null
        return DiscoverChannel(
            chatId = chat.id,
            username = supergroup.usernames?.activeUsernames?.firstOrNull(),
            title = chat.title.orEmpty(),
            subscribers = if (withSubscribers) supergroup.memberCount.takeIf { it > 0 } else null,
            avatarThumb = chat.photo?.minithumbnail?.data,
            avatarFileId = chat.photo?.small?.id,
            isMember = isChannelMember(supergroup.status),
        )
    }

    private suspend fun <T> throttled(block: suspend () -> T): T = throttle.withLock {
        val now = System.currentTimeMillis()
        val wait = THROTTLE_MS - (now - lastCallAt)
        if (wait > 0) delay(wait)
        try {
            block()
        } finally {
            lastCallAt = System.currentTimeMillis()
        }
    }

    companion object {
        private const val TAG = "ChannelDiscovery"
        private const val THROTTLE_MS = 1_000L
    }
}

/**
 * Whether [status] means the current user is in the channel. `Restricted` and `Creator`
 * carry an explicit `isMember` flag (a restricted user can have left; the owner can be
 * outside their own channel) — treating every non-Left/Banned status as membership
 * mislabelled those as subscribed.
 */
internal fun isChannelMember(status: TdApi.ChatMemberStatus?): Boolean = when (status) {
    null, is TdApi.ChatMemberStatusLeft, is TdApi.ChatMemberStatusBanned -> false
    is TdApi.ChatMemberStatusRestricted -> status.isMember
    is TdApi.ChatMemberStatusCreator -> status.isMember
    else -> true
}

/**
 * A live, TDLib-resolved channel card. [avatarThumb]/[avatarFileId] feed
 * `TdAvatar`'s minithumb→file ladder; [subscribers] is the raw count (null for
 * search hits, which skip the extra RPC). [isMember] drives the Subscribe/Open
 * action label.
 */
@Immutable
data class DiscoverChannel(
    val chatId: Long,
    val username: String?,
    val title: String,
    val subscribers: Int?,
    val avatarThumb: ByteArray?,
    val avatarFileId: Int?,
    val isMember: Boolean,
)

/**
 * Mode-agnostic card payload the suggestion/search rows render. Guest mode fills
 * [avatarUrl] (a CDN URL parsed from t.me/s); authenticated mode fills
 * [avatarThumb]/[avatarFileId] (TDLib). [subscribersText] is pre-formatted so the
 * shared row never has to know which mode produced it.
 */
@Immutable
data class ChannelCardData(
    val title: String,
    val subscribersText: String? = null,
    val avatarThumb: ByteArray? = null,
    val avatarFileId: Int? = null,
    val avatarUrl: String? = null,
    val isMember: Boolean = false,
)
