package dev.lyo.hortay.data

import android.net.Uri
import android.util.LruCache
import org.drinkless.tdlib.TdApi

/**
 * Authoritative parser for "the user just clicked a Telegram link, what should we do?".
 *
 * Two cooperating paths:
 *
 *   1. **TDLib `GetInternalLinkType`** (preferred). Telegram's own canonical parser,
 *      shipped inside the daemon. Knows ~50 typed link variants (public chat, message,
 *      chat invite, bot start, premium features, story, gift, chat folder invite, …)
 *      and updates with every TDLib bump — so adding a new link kind upstream "just
 *      works" without us patching a regex. Marked as `Can be called before
 *      authorization` and `offline method` — safe to call in guest mode, no network /
 *      no FLOOD_WAIT cost.
 *
 *   2. **Local regex fallback**. Covers exactly the three shapes our UI dispatches
 *      natively. Used when TDLib's send fails for any reason (typically the cold-launch
 *      window before [TdClient.start] has finished its JNI handshake — the client is
 *      not "Loading" by then but the native side may still be racing the first request).
 *
 * Results are LRU-cached by raw URL string. `GetInternalLinkType` is cheap (offline JNI
 * + JSON marshal), but the same link is often re-tapped from different surfaces in one
 * session — feed → comments → reply preview → channel filter — and caching saves the
 * cumulative JNI/JSON cost without measurably hurting hit-rate accuracy (Telegram never
 * re-purposes a URL).
 *
 * Result variants:
 *   - [DeepLink.PublicChannel] / [DeepLink.PrivateChannel] / [DeepLink.Message] —
 *     actionable inside Hortay; collected by `MainScaffold` / `WebModeScaffold`.
 *   - [DeepLink.External] — recognised Telegram URL we don't natively support; the
 *     caller forwards it to the OS so the official Telegram client (or browser
 *     fallback) handles it.
 *   - `null` — input wasn't a Telegram URL at all (https://example.com, mailto:, tel:).
 *     Caller treats this as a generic external URL.
 */
class TelegramLinkResolver(private val td: TdSender) {

    private val cache = LruCache<String, DeepLink>(CACHE_CAPACITY)

    suspend fun resolve(uri: Uri): DeepLink? {
        val raw = uri.toString()
        cache[raw]?.let { return it }

        // No scheme pre-filter. The deliberate TDLib contract for "is this a Telegram
        // link" is: hand it to `GetInternalLinkType` — Telegram returns 404 for anything
        // that isn't internal, and the round-trip is offline JNI (microseconds). Doing
        // our own allow-list ahead of that misses canonical aliases (`telegram.me`,
        // `telegram.dog`, future-domains-the-server-issues-as-config) AND bakes in
        // assumptions about which schemes carry Telegram payloads. The LRU cache
        // upstairs makes the cost of "call TDLib for every link including duds"
        // effectively zero on the warm path.
        val result = parseWithTd(raw) ?: parseLocal(uri)
        if (result != null) cache.put(raw, result)
        return result
    }

    /**
     * Ask TDLib to typed-parse [rawUrl]. Returns null on any failure (404 from
     * GetInternalLinkType for non-internal links, native send failure during boot, …)
     * — callers fall through to [parseLocal].
     *
     * For [TdApi.InternalLinkTypeMessage] we make a second offline call to
     * `GetMessageLinkInfo` to extract `(chatId, message.id)`; without that, we'd have a
     * URL but no addressable target for our timeline scroll-to-message dispatcher.
     */
    private suspend fun parseWithTd(rawUrl: String): DeepLink? {
        val type = runCatching {
            td.send(TdApi.GetInternalLinkType(rawUrl))
        }.getOrNull() ?: return null

        return when (type) {
            is TdApi.InternalLinkTypePublicChat ->
                DeepLink.PublicChannel(handle = type.chatUsername, serverPostId = null)
            is TdApi.InternalLinkTypeMessage -> {
                val info = runCatching {
                    td.send(TdApi.GetMessageLinkInfo(type.url))
                }.getOrNull() ?: return DeepLink.External(rawUrl)
                val message = info.message
                if (info.chatId == 0L || message == null) DeepLink.External(rawUrl)
                else DeepLink.Message(chatId = info.chatId, messageId = message.id)
            }
            // Hashtag taps emit `tg://search?query=#foo` and TDLib classifies them as
            // InternalLinkTypeSearch — they're a Telegram-internal feature (global
            // hashtag search) but Hortay doesn't have that UI yet. Routing them to
            // ACTION_VIEW would punt the user out of the app for what reads as an
            // internal tap; route to UnsupportedFeature instead so the scaffold can
            // surface a snackbar that explains why nothing happened.
            is TdApi.InternalLinkTypeSearch ->
                DeepLink.UnsupportedFeature(UnsupportedFeatureKind.HashtagSearch, rawUrl)
            // Invite links (`t.me/+abc...`). Scaffold calls CheckChatInviteLink for a
            // title + member-count preview, then offers a Join confirmation for
            // channel-type invites and runs JoinChatByInviteLink on accept.
            is TdApi.InternalLinkTypeChatInvite ->
                DeepLink.ChatInvite(inviteLink = type.inviteLink)
            // Everything else — bot starts, premium features, gifts,
            // story shares, chat folder invites, … — is a Telegram URL we recognise
            // but don't natively handle. Hand the raw string back so the UI delegates
            // to the OS / Telegram client.
            else -> DeepLink.External(rawUrl)
        }
    }

    /**
     * Local regex fallback. Mirrors the historical hand-rolled parser, narrowed to the
     * three shapes the UI actually dispatches. Triggers when TDLib send is unavailable
     * (typically the first ~100 ms of process boot, before the native side is wired).
     */
    private fun parseLocal(uri: Uri): DeepLink? = when (uri.scheme?.lowercase()) {
        "tg" -> parseTgLocal(uri)
        "http", "https" -> {
            // Critical host gate: without this we'd treat ANY https URL as a Telegram
            // channel handle — `https://github.com/foo` would become
            // DeepLink.PublicChannel(handle = "foo"), the router would call
            // SearchPublicChat("foo"), and the user would land on a (probably empty)
            // Telegram channel instead of opening GitHub. The TDLib parser path
            // (GetInternalLinkType) gates this correctly upstream; this fallback only
            // runs when TDLib isn't reachable (cold-launch race), so we must reproduce
            // the same gate ourselves.
            val host = uri.host?.lowercase()?.removePrefix("www.")
            if (host in TELEGRAM_DOMAINS) parseTMeLocal(uri) else null
        }
        else -> null
    }

    private fun parseTgLocal(uri: Uri): DeepLink? {
        val action = uri.host?.lowercase() ?: return null
        return when (action) {
            "resolve" -> {
                val domain = uri.getQueryParameter("domain")?.takeIf { it.isNotBlank() } ?: return null
                val post = uri.getQueryParameter("post")?.toLongOrNull()
                DeepLink.PublicChannel(handle = domain.removePrefix("@"), serverPostId = post)
            }
            "privatepost" -> {
                val raw = uri.getQueryParameter("channel")?.toLongOrNull() ?: return null
                val post = uri.getQueryParameter("post")?.toLongOrNull()
                DeepLink.PrivateChannel(
                    chatId = "-100$raw".toLongOrNull() ?: return null,
                    serverPostId = post,
                )
            }
            else -> null
        }
    }

    private fun parseTMeLocal(uri: Uri): DeepLink? {
        val segments = uri.pathSegments.filter { it.isNotBlank() }
        return when {
            segments.isEmpty() -> null
            segments[0] == "c" && segments.size >= 2 -> {
                val raw = segments[1].toLongOrNull() ?: return null
                val msg = segments.getOrNull(2)?.toLongOrNull()
                DeepLink.PrivateChannel(
                    chatId = "-100$raw".toLongOrNull() ?: return null,
                    serverPostId = msg,
                )
            }
            // `t.me/+inviteHash` (and the legacy `t.me/joinchat/<hash>`) carry an
            // invite token, not a public handle. SearchPublicChat would 404; the OS /
            // Telegram client owns the join flow. Same logic for share/proxy/addtheme
            // pages.
            segments[0].startsWith("+") || segments[0] in BLOCKED_PUBLIC_HOSTS -> null
            else -> {
                val handle = segments[0]
                val msg = segments.getOrNull(1)?.toLongOrNull()
                DeepLink.PublicChannel(
                    handle = handle.removePrefix("@"),
                    serverPostId = msg,
                )
            }
        }
    }

    private companion object {
        const val CACHE_CAPACITY = 256
        val BLOCKED_PUBLIC_HOSTS = setOf("joinchat", "addstickers", "share", "addtheme", "proxy", "socks")
        // Canonical Telegram link hosts. `telegram.me` / `telegram.dog` are server-side
        // aliases for `t.me`; including them in the fallback parser keeps cold-launch
        // resolution accurate without waiting for TDLib's GetInternalLinkType.
        val TELEGRAM_DOMAINS = setOf("t.me", "telegram.me", "telegram.dog")
    }
}
