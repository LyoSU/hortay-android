package dev.lyo.hortay.data

import dev.lyo.hortay.data.posts.isChannel
import org.drinkless.tdlib.TdApi

/**
 * Read-only channel lookups for screens and deep links: subscriber count, title and
 * avatar for a channel header, `@handle` / chat-id resolution, canonical share links.
 *
 * Split out of `PostsRepository`, which owns the merged feed's mutable state; nothing
 * here touches the feed. Reads go through [ChatRegistry] first, so the steady state is a
 * synchronous map hit and the fallbacks are TDLib's offline `GetChat` /
 * `GetSupergroup` — only [resolvePublicChat] / [resolvePublicHandle] may reach the
 * server (`SearchPublicChat` for a handle TDLib hasn't seen).
 */
class ChannelDirectory(
    private val td: TdSender,
    private val registry: ChatRegistry,
) {

    /**
     * Synchronous subscriber count read for a channel chat. Returns `null` when
     * the [registry] has not seen the chat / supergroup yet, the chat is not a
     * supergroup, or TDLib has not yet delivered a positive count for it.
     *
     * Why a separate function from [channelSubscribers]: this one is called from
     * [ChannelViewModel]`.init` to seed [ChannelViewModel.channelSubscribers]'s
     * [StateFlow] **before** the first composition collects it — which is the
     * only way to make the subtitle paint with the count already populated
     * instead of as a `null` for one frame followed by a recomposition once
     * the suspend variant resolves. The suspend variant is then launched as a
     * fallback for the cold-cache case.
     *
     * For any channel that has surfaced in the merged feed at least once this
     * session (the common case for every non-deep-link entry point) both
     * mirrors are warm and this returns the count immediately.
     */
    fun channelSubscribersCached(chatId: Long): Int? {
        val chat = registry.cachedChat(chatId) ?: return null
        val supergroupId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: return null
        return registry.cachedSupergroup(supergroupId)?.memberCount?.takeIf { it > 0 }
    }

    /**
     * Subscriber count for a channel chat. Returns `null` when the chat is not a
     * supergroup (private 1:1, basic group) or TDLib reports an unknown count.
     *
     * Resolution order, fast → slow:
     *   1. [channelSubscribersCached] — synchronous mirror read; common case
     *      because [TdApi.UpdateNewChat] / [TdApi.UpdateSupergroup] populate
     *      both mirrors eagerly on session warm-up.
     *   2. [TdApi.GetSupergroup] RPC fallback — for the rare cold-cache case
     *      (channel opened via deep-link before any feed refresh; freshly
     *      joined channel whose Supergroup update is still in flight). Served
     *      from TDLib's own local cache in steady state, so even this branch
     *      avoids the network in the common case.
     */
    suspend fun channelSubscribers(chatId: Long): Int? {
        channelSubscribersCached(chatId)?.let { return it }
        // Cold-cache fallback: both lookups are offline TDLib reads, and the registry
        // stores what they return so re-entry hits the synchronous path.
        val chat = registry.chat(chatId) ?: return null
        val supergroupId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: return null
        return registry.supergroup(supergroupId)?.memberCount?.takeIf { it > 0 }
    }

    /**
     * Chat title as TDLib knows it. Cheap: TDLib serves [TdApi.GetChat] from its local
     * cache after the first resolve (SearchPublicChat, JoinChat, or any other chat
     * touch). Used by the channel-filter top bar to show a non-subscribed channel's
     * name before `PostsRepository.loadChannelHistory` populates the merged feed — same UX
     * Telegram-Android offers when you open a public channel preview.
     */
    suspend fun chatTitle(chatId: Long): String? {
        return registry.chat(chatId)?.title?.takeIf { it.isNotBlank() }
    }

    /**
     * Channel avatar source for the [ChannelScreen] header. Returns
     * (smallFileId, minithumbBytes) — both nullable, both fed into [TdAvatar]
     * which paints the 3-tier ladder (minithumb → file → initial letter). Same
     * registry → offline `GetChat` lookup as [chatTitle] / [channelSubscribers]:
     * served from TDLib's local cache after the first touch, no network in
     * steady state. Either field may be null for channels whose [TdApi.Chat.photo]
     * is itself null (rare — channel without a profile photo).
     */
    suspend fun chatAvatar(chatId: Long): Pair<Int?, ByteArray?>? {
        val chat = registry.chat(chatId) ?: return null
        return chat.photo?.small?.id to chat.photo?.minithumbnail?.data
    }

    /**
     * Resolve a Telegram public `@handle` (without the leading `@`) to a TDLib chat id.
     * Used by the deep-link dispatcher (`tg://resolve` / `https://t.me/<handle>`) so a
     * tap on a shared link inside Hortay routes the user to the channel filter — no
     * round-trip through the official Telegram client. TDLib serves the resolved chat
     * from cache when known, otherwise hits the server once and writes through.
     *
     * Returns null when the handle doesn't exist, points at a user/bot we can't surface
     * as a channel filter, or the request fails. Callers fall through to a generic
     * "open external" action in that case.
     */
    suspend fun resolvePublicChat(handle: String): Long? {
        val cleaned = handle.removePrefix("@").trim()
        if (cleaned.isBlank()) return null
        return runCatching { td.send(TdApi.SearchPublicChat(cleaned)).id }
            .warnUnlessCancelled(TAG, "resolvePublicChat($cleaned)")
            .getOrNull()
    }

    /**
     * Typed variant of [resolvePublicChat] used by the deep-link dispatcher. Returns:
     *
     *   - [PublicHandleResult.Channel] — the handle resolves to a broadcast channel
     *     (`ChatTypeSupergroup` with `isChannel = true`). Caller switches the feed
     *     filter to [chatId].
     *
     *   - [PublicHandleResult.User] — the handle resolves to a 1:1 user or bot
     *     (`ChatTypePrivate`). Caller opens the in-app
     *     [dev.lyo.hortay.ui.users.UserProfileSheet] so an `@username` mention lands
     *     on the same surface as an in-text `TextEntityTypeMentionName` tap.
     *
     *   - [PublicHandleResult.Unsupported] — the handle is a real Telegram entity but
     *     not something Hortay can render today (basic group, supergroup that isn't
     *     a channel). Caller surfaces a user-facing message and offers to hand off
     *     via the OS chooser.
     *
     *   - [PublicHandleResult.NotFound] — TDLib couldn't resolve the handle at all
     *     (`SearchPublicChat` 4xx / network failure). Caller treats as silent miss.
     */
    suspend fun resolvePublicHandle(handle: String): PublicHandleResult {
        val cleaned = handle.removePrefix("@").trim()
        if (cleaned.isBlank()) return PublicHandleResult.NotFound
        val chat = runCatching { td.send(TdApi.SearchPublicChat(cleaned)) }
            .warnUnlessCancelled(TAG, "resolvePublicHandle($cleaned)")
            .getOrNull() ?: return PublicHandleResult.NotFound
        return chat.toPublicHandleResult()
    }

    /**
     * Twin of [resolvePublicHandle] keyed on a TDLib chat id rather than a public handle.
     * Used by deep-link variants that already carry an id (`DeepLink.Message`,
     * `DeepLink.PrivateChannel`) so the dispatcher can verify the target IS a channel
     * before flipping `channelFilter`. Without the check, a `t.me/c/.../<msg>` link
     * that points at a basic group / supergroup-chat / user DM would land the user on
     * a channel filter that never paginates (loadChannelHistory short-circuits on
     * `!chat.isChannel()`), reading as a frozen empty view.
     *
     * Returns [PublicHandleResult.NotFound] when TDLib has no record of the chat
     * (private chat the user lost access to, transient network failure on GetChat).
     */
    suspend fun resolveChatKind(chatId: Long): PublicHandleResult {
        val chat = registry.chat(chatId) ?: return PublicHandleResult.NotFound
        return chat.toPublicHandleResult()
    }

    private fun TdApi.Chat.toPublicHandleResult(): PublicHandleResult {
        val t = type
        return when {
            isChannel() -> PublicHandleResult.Channel(id)
            // Private chat = 1:1 user / bot. Carry the userId through so the deep-link
            // dispatcher can route the tap to UserProfileSheet instead of bouncing the
            // user out to the official Telegram client. Reading from `ChatTypePrivate`
            // keeps this correct even if TDLib ever decouples chat.id from user.id.
            t is TdApi.ChatTypePrivate -> PublicHandleResult.User(t.userId)
            t is TdApi.ChatTypeBasicGroup -> PublicHandleResult.Unsupported(PublicHandleKind.Group)
            t is TdApi.ChatTypeSupergroup -> PublicHandleResult.Unsupported(PublicHandleKind.Group)
            else -> PublicHandleResult.Unsupported(PublicHandleKind.Unknown)
        }
    }

    /**
     * Canonical `https://t.me/...` share URL for a post, minted by TDLib's offline
     * `GetMessageLink`. TDLib owns the correct format for albums (`?single=…` markers,
     * forum-topic prefixes, comment-thread suffixes) we'd otherwise re-implement; this
     * one call defers all of that to the daemon.
     *
     * Returns null when:
     *   - The post belongs to a synthesised guest-mode chat ([chatId] below
     *     [dev.lyo.hortay.ui.actions.PostActions]'s guest threshold) — there is no
     *     TDLib chat to query against.
     *   - TDLib refuses (`messageProperties.canGetLink == false`, restricted source).
     *   - The native send fails for any other reason.
     *
     * Callers fall back to a hand-rolled URL in those cases. The method is suspending
     * but `GetMessageLink` is offline (microseconds over JNI), so latency is invisible
     * inside the share / open-in-Telegram flow.
     *
     * For album posts we anchor on the first member id with `forAlbum = true`, matching
     * Telegram-Android's "copy link to album" behaviour.
     */
    suspend fun canonicalShareUrl(post: TimelinePost): String? {
        val anchorId = post.albumMessageIds.firstOrNull() ?: post.id
        val forAlbum = post.albumMessageIds.size > 1
        val query = TdApi.GetMessageLink(post.chatId, anchorId, 0, 0, "", forAlbum, false)
        val response = runCatching { td.send(query) }
            .warnUnlessCancelled(TAG, "canonicalShareUrl(${post.chatId}, ${post.id})")
            .getOrNull()
        return response?.link?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val TAG = "ChannelDirectory"
    }
}

/** Result of [ChannelDirectory.resolvePublicHandle]. See its KDoc for semantics. */
sealed interface PublicHandleResult {
    data class Channel(val chatId: Long) : PublicHandleResult
    /** Handle resolves to a 1:1 user (or bot). Carries TDLib `userId` so callers can
     *  open the in-app [dev.lyo.hortay.ui.users.UserProfileSheet] without re-resolving. */
    data class User(val userId: Long) : PublicHandleResult
    data class Unsupported(val kind: PublicHandleKind) : PublicHandleResult
    data object NotFound : PublicHandleResult
}

/** Kind discriminator carried by [PublicHandleResult.Unsupported]. Users are routed via
 *  [PublicHandleResult.User] directly, so this enum only covers things Hortay surfaces
 *  to the OS / a snackbar (groups, supergroups, unrecognised entities). */
enum class PublicHandleKind { Group, Unknown }
