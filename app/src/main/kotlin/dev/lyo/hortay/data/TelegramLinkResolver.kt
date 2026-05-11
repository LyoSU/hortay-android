package dev.lyo.hortay.data

import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
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

    /**
     * Subscribe the resolver to a logout fan-out flow. The cache holds typed [DeepLink]
     * entries that reference TDLib chat ids; those ids belong to the active session and
     * shift on the next login. Wiring this in [AppGraph] guarantees the cache is wiped
     * before the new session's chats start arriving — without it, a re-tapped URL would
     * return a [DeepLink.Message] / [DeepLink.PublicChannel] pointing at the previous
     * account's chat, which is both a privacy regression (referrer-style identifier
     * leak across accounts) and a correctness one (silent miss / wrong-chat open).
     */
    fun bindLogoutClear(loggedOut: SharedFlow<Unit>, scope: CoroutineScope) {
        scope.launch {
            loggedOut.collect { cache.evictAll() }
        }
    }

    suspend fun resolve(uri: Uri): DeepLink? {
        val raw = uri.toString()
        cache[raw]?.let { return it }

        // No scheme pre-filter. The deliberate TDLib contract for "is this a Telegram
        // link" is: hand it to `GetInternalLinkType` — Telegram returns 404 for anything
        // that isn't internal, and the round-trip is offline JNI (microseconds). Doing
        // our own allow-list ahead of that misses canonical aliases (`telegram.me`,
        // `telegram.dog`, future-domains-the-server-issues-as-config) AND bakes in
        // assumptions about which schemes carry Telegram payloads.
        val result = parseWithTd(raw) ?: parseLocal(uri, raw)
        // Cache only the typed results that translate to a stable in-app destination.
        // [DeepLink.External] means "TDLib didn't recognise this as an internal link
        // right now" — which can also be the cold-launch race ("TDLib not yet wired,
        // SearchPublicChat for a public handle would have worked once the daemon
        // boots"). Caching External would freeze the wrong answer for the rest of the
        // session; re-resolving from scratch costs one microseconds-scale JNI call.
        if (result != null && result !is DeepLink.External) cache.put(raw, result)
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
                DeepLink.PublicChannel(
                    handle = type.chatUsername,
                    serverPostId = null,
                    originalUrl = rawUrl,
                )
            is TdApi.InternalLinkTypeMessage -> {
                val info = runCatching {
                    td.send(TdApi.GetMessageLinkInfo(type.url))
                }.getOrNull() ?: return DeepLink.External(rawUrl)
                val message = info.message
                if (info.chatId == 0L || message == null) DeepLink.External(rawUrl)
                else DeepLink.Message(
                    chatId = info.chatId,
                    messageId = message.id,
                    originalUrl = rawUrl,
                )
            }
            // Hashtag search arriving as an external link (e.g. clipboard paste from
            // the official client, or a t.me/s/.../?q= URL someone shared). Our own
            // renderer no longer fabricates a `tg://search?query=...` URL for inline
            // `#foo` taps — those go through `LocalHashtagTap` directly — but the
            // resolver still has to recognise this shape because TDLib does classify
            // it and we don't want to ACTION_VIEW-punt an internal-feature link.
            // Hortay has no hashtag-search UI yet; route to UnsupportedFeature so the
            // scaffold collector surfaces the snackbar. When in-app search lands,
            // introduce a typed [DeepLink.HashtagSearch] variant and dispatch here.
            is TdApi.InternalLinkTypeSearch ->
                DeepLink.UnsupportedFeature(UnsupportedFeatureKind.HashtagSearch, rawUrl)
            // Invite links (`t.me/+abc...`). Scaffold calls CheckChatInviteLink for a
            // title + member-count preview, then offers a Join confirmation for
            // channel-type invites and runs JoinChatByInviteLink on accept.
            is TdApi.InternalLinkTypeChatInvite ->
                DeepLink.ChatInvite(inviteLink = type.inviteLink, originalUrl = rawUrl)
            // Everything else — bot starts, premium features, gifts,
            // story shares, chat folder invites, … — is a Telegram URL we recognise
            // but don't natively handle. Hand the raw string back so the UI delegates
            // to the OS / Telegram client.
            else -> DeepLink.External(rawUrl)
        }
    }

    /**
     * Local regex fallback. Mirrors the historical hand-rolled parser, narrowed to the
     * shapes the UI dispatches natively. Triggers when TDLib send is unavailable
     * (typically the first ~100 ms of process boot, before the native side is wired).
     *
     * Thin Uri → primitives adapter; the actual parsing logic lives in
     * [parseLocalFromParts] which takes only JVM types so it's unit-testable on the
     * JVM without Robolectric. The Android [Uri] class is final, JNI-bound, and
     * stubbed-to-throw in pure-JVM tests — keeping the parser primitive-only is the
     * one barrier between "tested in isolation" and "needs Robolectric overhead".
     */
    private fun parseLocal(uri: Uri, rawUrl: String): DeepLink? {
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removePrefix("www.")
        val pathSegments = uri.pathSegments.orEmpty().filter { it.isNotBlank() }
        // Materialise queryParameters once into a Map — [Uri.getQueryParameter] is a
        // linear scan of the raw query each call, and the parser reads a couple of
        // keys per case anyway.
        val queryParams = uri.queryParameterNames.orEmpty().associateWith {
            uri.getQueryParameter(it).orEmpty()
        }
        return parseLocalFromParts(
            scheme = scheme,
            host = host,
            pathSegments = pathSegments,
            queryParams = queryParams,
            rawUrl = rawUrl,
        )
    }

    companion object {
        private const val CACHE_CAPACITY = 256
        // Path-prefix routes that look like a public handle to a naive parser but in
        // fact mark non-handle features (legacy invite path, sticker pack, proxy
        // settings, theme apply). `t.me/joinchat/<hash>` is the legacy invite shape;
        // newer Telegram emits `t.me/+<hash>` for the same purpose — both gated below.
        private val BLOCKED_PUBLIC_HOSTS = setOf(
            "joinchat", "addstickers", "share", "addtheme", "proxy", "socks",
        )
        // Canonical Telegram link hosts. `telegram.me` / `telegram.dog` are server-side
        // aliases for `t.me`; including them in the fallback parser keeps cold-launch
        // resolution accurate without waiting for TDLib's GetInternalLinkType.
        private val TELEGRAM_DOMAINS = setOf("t.me", "telegram.me", "telegram.dog")

        /**
         * Pure parser primitive — given the destructured components of a Telegram URI,
         * returns the typed [DeepLink] our scaffolds dispatch, or null if the input
         * doesn't match a known internal shape. JVM-only types in the signature so
         * unit tests can drive every case without Robolectric.
         *
         * Mirrors what TDLib's `GetInternalLinkType` returns for the same input,
         * narrowed to the four UI surfaces that have a native landing target
         * (PublicChannel, PrivateChannel, ChatInvite, UnsupportedFeature). Anything
         * else returns null so the caller can defer to TDLib or the OS handler.
         *
         * `t.me/+<hash>` and the legacy `t.me/joinchat/<hash>` now produce a typed
         * [DeepLink.ChatInvite] in the fallback path too — previously the fallback
         * returned null for these and the caller punted to OS, while the warm-path
         * (TDLib) produced ChatInvite. The inconsistency made cold-launched invite
         * taps skip the in-app preview dialog. Now both paths agree.
         */
        internal fun parseLocalFromParts(
            scheme: String?,
            host: String?,
            pathSegments: List<String>,
            queryParams: Map<String, String>,
            rawUrl: String,
        ): DeepLink? = when (scheme) {
            "tg" -> parseTgScheme(host, queryParams, rawUrl)
            "http", "https" ->
                if (host in TELEGRAM_DOMAINS) parseTMePath(pathSegments, rawUrl) else null
            else -> null
        }

        private fun parseTgScheme(
            host: String?,
            queryParams: Map<String, String>,
            rawUrl: String,
        ): DeepLink? = when (host) {
            "resolve" -> {
                val domain = queryParams["domain"]?.takeIf { it.isNotBlank() }
                val post = queryParams["post"]?.toLongOrNull()
                domain?.let {
                    DeepLink.PublicChannel(
                        handle = it.removePrefix("@"),
                        serverPostId = post,
                        originalUrl = rawUrl,
                    )
                }
            }
            "privatepost" -> {
                val raw = queryParams["channel"]?.toLongOrNull()
                val post = queryParams["post"]?.toLongOrNull()
                raw?.let {
                    val chatId = "-100$it".toLongOrNull() ?: return@let null
                    DeepLink.PrivateChannel(
                        chatId = chatId,
                        serverPostId = post,
                        originalUrl = rawUrl,
                    )
                }
            }
            // `tg://join?invite=<hash>` — `tg://` flavour of an invite link. Both
            // canonical and legacy Android shares emit this format.
            "join" -> {
                val invite = queryParams["invite"]?.takeIf { it.isNotBlank() }
                invite?.let {
                    DeepLink.ChatInvite(inviteLink = "https://t.me/+$it", originalUrl = rawUrl)
                }
            }
            else -> null
        }

        private fun parseTMePath(
            segments: List<String>,
            rawUrl: String,
        ): DeepLink? = when {
            segments.isEmpty() -> null
            segments[0] == "c" && segments.size >= 2 -> {
                val raw = segments[1].toLongOrNull()
                if (raw == null) null else {
                    val chatId = "-100$raw".toLongOrNull()
                    val msg = segments.getOrNull(2)?.toLongOrNull()
                    chatId?.let {
                        DeepLink.PrivateChannel(
                            chatId = it,
                            serverPostId = msg,
                            originalUrl = rawUrl,
                        )
                    }
                }
            }
            // `t.me/+<hash>` — canonical invite shape. The leading '+' is the invite
            // sentinel; the rest is the opaque token TDLib's CheckChatInviteLink
            // accepts. Bare `t.me/+` carries no token and is rejected (TDLib would
            // 404). We pass the FULL URL through to TDLib (it re-parses server-side).
            segments[0].startsWith("+") ->
                if (segments[0].length > 1)
                    DeepLink.ChatInvite(inviteLink = rawUrl, originalUrl = rawUrl)
                else null
            // `t.me/joinchat/<hash>` — legacy invite shape. TDLib still accepts the
            // canonical form; reconstruct it so the warm path through
            // CheckChatInviteLink doesn't 404.
            segments[0] == "joinchat" ->
                if (segments.size >= 2)
                    DeepLink.ChatInvite(
                        inviteLink = "https://t.me/+${segments[1]}",
                        originalUrl = rawUrl,
                    )
                else null
            segments[0] in BLOCKED_PUBLIC_HOSTS -> null
            else -> {
                val handle = segments[0]
                val msg = segments.getOrNull(1)?.toLongOrNull()
                DeepLink.PublicChannel(
                    handle = handle.removePrefix("@"),
                    serverPostId = msg,
                    originalUrl = rawUrl,
                )
            }
        }
    }
}
