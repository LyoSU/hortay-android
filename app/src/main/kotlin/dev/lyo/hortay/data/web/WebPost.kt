package dev.lyo.hortay.data.web

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * Output of [TmePageParser]. Everything reachable from a single t.me/s/<username> page
 * fetch lives here. Web-mode UI binds against this directly until Phase 2 introduces
 * a [dev.lyo.hortay.data.TimelinePost] adapter — at that point `WebPost` becomes a
 * private intermediate.
 *
 * Why a separate model from [dev.lyo.hortay.data.TimelinePost]: t.me/s/ exposes a
 * strict subset of TdApi.Message — no formatted-text entities (only HTML spans), no
 * sender beyond the owning channel, no message thread ids, no album ids (album items
 * are inlined as siblings on the page). Forcing a TDLib-shaped object here would
 * require fake values for missing fields (silent data loss when we map back) or leak
 * nullables across the entire timeline graph.
 *
 * What we deliberately don't model (because t.me/s/ doesn't surface it):
 *   - Discussion-thread comment counts. The preview page never includes them; only
 *     the per-message embed widget (t.me/<u>/<id>?embed=1) does, and fetching that
 *     for every visible post defeats the scaling argument that conditional GET buys
 *     us. Comments stay TDLib-only.
 *   - Custom-emoji renderings inside reactions. Telegram only embeds the emoji-id
 *     in the static HTML and renders the glyph via JS at runtime — we expose the id
 *     so the UI can either display a generic chip or look it up via TDLib once the
 *     user signs in.
 *
 * `@Immutable` end-to-end so a future direct Compose binding (TimelineScreen rendering
 * web posts) keeps skippability.
 */
@Immutable
data class WebChannelPage(
    val channel: WebChannelInfo,
    val posts: ImmutableList<WebPost>,
    /**
     * Cursor for older posts. Pass to [WebTelegramClient.fetchChannelPage] as `before`
     * to paginate backwards. `null` means we reached the channel's first message.
     */
    val olderCursor: String?,
)

@Immutable
data class WebChannelInfo(
    val username: String,
    val title: String,
    val description: String,
    /** Absolute https:// URL to the channel's avatar. `null` for channels without one. */
    val avatarUrl: String?,
    /**
     * Subscriber count formatted exactly as Telegram itself renders it ("2.5M",
     * "12 345"). We surface the raw string instead of a parsed number because t.me/s/
     * formats it according to the request's Accept-Language and we don't want to
     * re-localize on the client.
     */
    val subscribers: String?,
    val isVerified: Boolean,
)

/**
 * One channel post. [id] is unique within a channel; pair with [WebChannelInfo.username]
 * for global uniqueness. We use a string id (not Long) because t.me/s/ post URLs are
 * "<username>/<seq>" — we never see TDLib's chatId/messageId pair.
 */
@Immutable
data class WebPost(
    /** "<username>/<seq>" — same shape as t.me/s/'s `data-post` attribute. */
    val id: String,
    /** Sequence number within the channel. Monotonically increasing per post. */
    val seq: Long,
    /** ISO-8601 timestamp from `<time datetime="...">`. Parser leaves higher-level parsing to UI. */
    val publishedAt: String,
    /**
     * Inner HTML of `.tgme_widget_message_text`. Spans (`<b>`, `<i>`, `<a>`,
     * `<tg-spoiler>`, `<tg-emoji>`) are preserved as inline tags — UI runs them
     * through [dev.lyo.hortay.ui.text.RichText] (Phase 2). Empty for media-only posts.
     */
    val textHtml: String,
    val media: ImmutableList<WebMedia>,
    val webPreview: WebPreview?,
    /**
     * Forwarded-from header. null when post is original. Web preview only exposes the
     * source channel name + link (much sparser than TDLib's full forward chain).
     */
    val forwardedFrom: WebForwardSource?,
    /**
     * View count as rendered by t.me/s/ ("1.5K", "12K"). null for posts younger than
     * Telegram's view-aggregation window (~10 minutes). String, not int — same locale
     * rationale as [WebChannelInfo.subscribers].
     */
    val views: String?,
    /**
     * Reactions in Telegram's display order. Empty when the post has none or the
     * channel disabled them. Tap on any reaction surfaces the sign-in bottom sheet
     * for anon users (UI layer).
     */
    val reactions: ImmutableList<WebReaction>,
)

@Immutable
data class WebMedia(
    val kind: Kind,
    /** CDN URL extracted from the page. Photos use background-image inline style. */
    val url: String,
    /** Aspect ratio (width / height) when the page provided enough info to compute it. */
    val aspectRatio: Float?,
    /** Duration in seconds for [Kind.Video] / [Kind.Voice] / [Kind.RoundVideo]; null otherwise. */
    val durationSec: Int?,
    /** Poster/thumbnail URL for video kinds. null for photos. */
    val thumbnailUrl: String?,
) {
    enum class Kind {
        Photo,
        Video,
        /** Round video message ("кружок"). t.me/s/ renders it inline as a circular video. */
        RoundVideo,
        /** Voice note. t.me/s/ shows a waveform but we treat it as opaque audio. */
        Voice,
        /** Generic file/document. */
        Document,
        /** Static sticker thumbnail. The animated TGS/WebM payload isn't on t.me/s/. */
        Sticker,
        /** GIF. t.me/s/ inlines as MP4. */
        Gif,
    }
}

@Immutable
data class WebPreview(
    val url: String,
    val siteName: String?,
    val title: String?,
    val description: String?,
    val imageUrl: String?,
)

@Immutable
data class WebForwardSource(
    val channelName: String,
    /** `https://t.me/<channel>(/<msg>)?` link from the forwarded-from header. */
    val channelLink: String,
)

/**
 * One reaction bucket. The static HTML page exposes only what JS needs to bootstrap;
 * the actual rendered glyph is sometimes empty in the static markup (Telegram fetches
 * it client-side from emoji-id). We surface what we have and let the UI decide how to
 * render — see field docs.
 */
@Immutable
data class WebReaction(
    /**
     * The visible glyph if the page embedded one (most often a unicode emoji baked
     * into a `<b>` fallback inside `<tg-emoji>`). Empty when the page only carried
     * the [emojiId] for client-side rendering.
     */
    val glyph: String,
    /**
     * Telegram custom-emoji id when the reaction is a custom emoji. UI can resolve
     * it via TDLib once the user signs in; for anon mode we display [glyph] or fall
     * back to a neutral chip.
     */
    val emojiId: String?,
    /** Localized count string as t.me/s/ rendered it ("44.2K", "12"). */
    val count: String,
    /** True for paid-stars reactions ("⭐") which t.me/s/ marks with `tgme_reaction_paid`. */
    val isPaid: Boolean,
)
