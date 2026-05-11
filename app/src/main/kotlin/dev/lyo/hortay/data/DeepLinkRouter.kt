package dev.lyo.hortay.data

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Sole delivery channel for resolved Telegram deep links. Parsing lives in
 * [TelegramLinkResolver] — this class just buffers typed events and hands them to a
 * single collector.
 *
 * We use a [Channel] rather than a [kotlinx.coroutines.flow.SharedFlow] because:
 *
 *   • **Cold-launch ordering** — [MainActivity.onCreate] submits the URI BEFORE Compose
 *     mounts and subscribes. A SharedFlow with replay = 0 would drop the link on the
 *     floor (no subscriber); replay = 1 would replay the same link on every config
 *     change, re-navigating the user after every rotation. Channel buffers items until
 *     a collector arrives and consumes each exactly once.
 *
 *   • **One-shot semantics** — opening a deep link IS a one-shot side effect. Channel
 *     models that directly; SharedFlow models a topic of multi-cast values.
 */
class DeepLinkRouter {

    // UNLIMITED rather than BUFFERED: every value in this channel represents a discrete
    // user-initiated action (a tap on a link, an Intent arriving via singleTop). The
    // BUFFERED default's ~64-slot capacity silently dropped events on the floor when
    // the collector was momentarily paused (predictive-back animation, comments
    // overlay transition). For one-shot UX events the right backpressure is "buffer
    // everything, drain as fast as we can compose" — losing a tap is worse than
    // briefly holding an extra Pair in memory.
    private val _events = Channel<DeepLink>(capacity = Channel.UNLIMITED)
    val events: Flow<DeepLink> = _events.receiveAsFlow()

    /** Hand a pre-resolved link off to the UI collector. */
    fun submit(link: DeepLink) {
        _events.trySend(link)
    }
}

/**
 * Strongly-typed result of parsing a Telegram URI.
 *
 * The first three variants are the actionable shapes our UI dispatches today; [External]
 * is a catch-all for the long tail of Telegram link types (invite hashes, bot starts,
 * premium-feature pages, gifts, story shares, …) that we recognise as Telegram-owned
 * URLs but have no native surface for. Routing for [External] is to delegate back to the
 * platform [androidx.compose.ui.platform.UriHandler] / `Intent.ACTION_VIEW` so the OS
 * picks the right client (typically the official Telegram app).
 *
 * Note on `serverPostId` units: this is Telegram's *server-side* post number (1, 2, 3…).
 * TDLib internally encodes messages as `serverId shl 20`; the consumer shifts when it
 * needs a TDLib-shaped id. [Message] carries the TDLib-resolved chatId and the already-
 * shifted messageId because [TelegramLinkResolver] resolves it via `GetMessageLinkInfo`
 * — no extra shift needed by the caller.
 */
sealed interface DeepLink {
    /**
     * The original URL string the user (or sharing app) tapped. Carried on every
     * variant so the deep-link collector can fall back to ACTION_VIEW when the typed
     * route turns out to be unsupported — e.g. a `t.me/c/...` message link that
     * resolves to a basic group rather than a channel — without needing to
     * synthesise a URL after the fact.
     */
    val originalUrl: String

    /** A public channel/user/bot reachable by [handle] (without `@`). */
    data class PublicChannel(
        val handle: String,
        val serverPostId: Long?,
        override val originalUrl: String,
    ) : DeepLink

    /** A private channel referenced by its TDLib chat id (`-100<raw>`). */
    data class PrivateChannel(
        val chatId: Long,
        val serverPostId: Long?,
        override val originalUrl: String,
    ) : DeepLink

    /**
     * A specific chat+message pair resolved by TDLib's `GetMessageLinkInfo`. The
     * [messageId] is already in TDLib's internal `(serverId shl 20)` form.
     */
    data class Message(
        val chatId: Long,
        val messageId: Long,
        override val originalUrl: String,
    ) : DeepLink

    /**
     * Recognised Telegram URL we don't natively support (invites, gifts, bot-start, …).
     * Carries the verbatim original URL so the collector can hand it to the OS for
     * dispatch to the official Telegram client.
     */
    data class External(override val originalUrl: String) : DeepLink

    /**
     * Hashtag (or cashtag) search. Three converging sources:
     *
     *   1. **Inline `#foo` tap inside a post body.** The renderer routes through
     *      [dev.lyo.hortay.ui.text.LocalHashtagTap]; the PostBody composable installs
     *      a scoped tap that captures the channel handle the post belongs to, so the
     *      resulting [channelHandle] is the channel the user was reading when they
     *      tapped — matching Telegram-Android's "tap #foo, search inside this channel".
     *
     *   2. **`#tag@channel` text-entity form.** Per TDLib's
     *      `TextEntityTypeHashtag` docs, a hashtag entity *"optionally [contains] a
     *      chat username at the end"*. We split the trailing `@channel` off in the
     *      tap dispatcher: the carrier text becomes [tag] (with leading `#`) and the
     *      suffix becomes [channelHandle] — overriding any scope captured from
     *      composition, since the entity is self-describing.
     *
     *   3. **External URLs.** [TelegramLinkResolver] surfaces `tg://search?query=%23foo`
     *      (TDLib `InternalLinkTypeSearch`) and the undocumented but in-the-wild
     *      `tg://resolve?domain=<channel>&hashtag=<tag>` / `t.me/<channel>?q=%23<tag>`
     *      shapes as this variant. URLs from non-typed entry points always carry
     *      [originalUrl] for diagnostic and fall-through-to-OS use.
     *
     * Hortay has no hashtag-search UI yet — both scaffold collectors surface a
     * snackbar describing the scope ("Пошук #foo у @channel" or "Пошук #foo") so
     * the user understands what was attempted. When in-app search ships, dispatch
     * here changes once and the renderer + scaffolds don't need to be touched.
     *
     * [tag] always carries the leading `#` for consistency with what Telegram-Android
     * shows to the user; the future search call site strips it before
     * `searchPublicMessagesByTag` / `searchChatMessages`.
     */
    data class HashtagSearch(
        val tag: String,
        val channelHandle: String?,
        override val originalUrl: String,
    ) : DeepLink

    /**
     * Telegram chat invite link (`t.me/+abc...`, legacy `t.me/joinchat/xyz`,
     * `tg://join?invite=…`). The scaffold collects this, calls
     * [dev.lyo.hortay.data.ChannelActionsRepository.previewChatInvite] to read title
     * / member count / chat kind via TDLib's `CheckChatInviteLink` (no network if the
     * invite is already cached), and renders a Join confirmation dialog for channels.
     * Non-channel invites surface a snackbar and hand off to the official client —
     * Hortay has no group / direct-chat surface.
     */
    data class ChatInvite(val inviteLink: String, override val originalUrl: String) : DeepLink
}
