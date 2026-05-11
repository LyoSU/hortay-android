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

    private val _events = Channel<DeepLink>(capacity = Channel.BUFFERED)
    val events: Flow<DeepLink> = _events.receiveAsFlow()

    /** Hand a pre-resolved link off to the UI collector. Returns true on successful enqueue. */
    fun submit(link: DeepLink): Boolean = _events.trySend(link).isSuccess
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
    /** A public channel/user/bot reachable by [handle] (without `@`). */
    data class PublicChannel(val handle: String, val serverPostId: Long?) : DeepLink

    /** A private channel referenced by its TDLib chat id (`-100<raw>`). */
    data class PrivateChannel(val chatId: Long, val serverPostId: Long?) : DeepLink

    /**
     * A specific chat+message pair resolved by TDLib's `GetMessageLinkInfo`. The
     * [messageId] is already in TDLib's internal `(serverId shl 20)` form.
     */
    data class Message(val chatId: Long, val messageId: Long) : DeepLink

    /**
     * Recognised Telegram URL we don't natively support (invites, gifts, bot-start, …).
     * Carries the verbatim original URL so the collector can hand it to the OS for
     * dispatch to the official Telegram client.
     */
    data class External(val rawUrl: String) : DeepLink

    /**
     * Telegram-internal link type we explicitly DON'T want to send to the OS — punting
     * a `tg://` URL to ACTION_VIEW feels external to the user even though it's a
     * Telegram-domain URI ("я тиснув на хештег, а воно відкрило іншу програму").
     * Scaffolds show an in-app snackbar describing the feature instead.
     */
    data class UnsupportedFeature(val feature: UnsupportedFeatureKind, val rawUrl: String) : DeepLink

    /**
     * Telegram chat invite link (`t.me/+abc...`, legacy `t.me/joinchat/xyz`,
     * `tg://join?invite=…`). The scaffold collects this, calls
     * [dev.lyo.hortay.data.ChannelActionsRepository.previewChatInvite] to read title
     * / member count / chat kind via TDLib's `CheckChatInviteLink` (no network if the
     * invite is already cached), and renders a Join confirmation dialog for channels.
     * Non-channel invites surface a snackbar and hand off to the official client —
     * Hortay has no group / direct-chat surface.
     */
    data class ChatInvite(val inviteLink: String) : DeepLink
}

/** Kinds of Telegram links we recognise but render in-app as a snackbar instead of
 *  routing them externally — see [DeepLink.UnsupportedFeature]. */
enum class UnsupportedFeatureKind { HashtagSearch }
