package dev.lyo.hortay.data.web

import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.ForwardOrigin
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.ReactionItem
import dev.lyo.hortay.data.ReactionKind
import dev.lyo.hortay.data.Reactions
import dev.lyo.hortay.data.TdMedia
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.VideoQualities
import dev.lyo.hortay.data.VideoQuality
import dev.lyo.hortay.data.WebPreview as TdWebPreview
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.time.OffsetDateTime

/**
 * Adapter from the parser-shaped [WebPost] to the UI-shaped [TimelinePost].
 *
 * Why this exists: the user wanted strict 1:1 visual parity between guest and
 * authenticated modes ("саме 1 в 1 все, використати ті самі ux елементи і типи").
 * Building a parallel WebPostCard kept producing visually-similar-but-not-identical
 * cards. The only way to actually share rendering is to share the data type that
 * [dev.lyo.hortay.ui.timeline.PostCard] consumes — [TimelinePost] — so the same
 * Composable, the same Avatar/HeaderRow/PostBody/ActionRow/ReactionChip helpers,
 * the same typography, are exercised in both modes.
 *
 * What we lose by adapting:
 *   - Per-channel TDLib chat ids: synthesised from `username.hashCode().toLong()`.
 *     Stable for the runtime of the process; no semantic meaning beyond "unique
 *     row key". Web-mode interactions never need to round-trip these to TDLib.
 *   - Custom-emoji animation: t.me/s/ exposes the emoji-id as a string, but TDLib
 *     [ReactionKind.CustomEmoji] takes a Long (not all string ids fit). For the
 *     ones that do, we keep the id; the rest become [ReactionKind.Emoji] with the
 *     unicode fallback glyph. The user gets a visible reaction either way.
 *   - View count is rendered as Int on PostCard. We parse strings like "1.5K"
 *     back to ints — lossy but PostCard's `formatViews` rebuilds the same
 *     short form, so visually identical.
 *   - Media: web posts ship CDN URLs, not TDLib file ids. PostCard's media
 *     pipeline ([TdMediaImage]) is fileId-driven. For now we stash media URLs
 *     into a synthetic [TdMedia.fileId = null] and the body text mentions them
 *     via captionPlain — actual image rendering for web mode media is a Phase
 *     2 follow-up that needs PostBody to grow a "url-fallback" path.
 */
object WebPostAdapter {

    /**
     * Build a [TimelinePost] from raw DB row fields. Direct primitive params
     * avoid intermediate data classes — the SQLDelight row mapper plugs straight
     * into this without an adapter object in the middle.
     */
    fun toTimelinePost(
        seq: Long,
        publishedAtIso: String,
        textHtml: String,
        mediaList: List<WebMedia>,
        webPreview: WebPreview?,
        forwarded: WebForwardSource?,
        viewsRaw: String?,
        reactionsList: List<WebReaction>,
        channelUsername: String,
        channelTitle: String,
        channelAvatarUrl: String?,
    ): TimelinePost {
        val publishedMs = parseIso(publishedAtIso)
        val formatted = htmlToFormatted(textHtml)
        val tdWebPreview = webPreview?.let { tdWebPreviewFrom(it) }
        val content = buildContent(formatted, mediaList, tdWebPreview)
        return TimelinePost(
            id = seq,
            chatId = stableChatId(channelUsername),
            mediaAlbumId = 0L,
            senderName = channelTitle,
            senderHandle = "@$channelUsername",
            avatarThumb = null,
            avatarFileId = null,
            avatarUrl = channelAvatarUrl,
            content = content,
            views = viewsRaw?.let { parseShortNumber(it) } ?: 0,
            // TimelinePost.date is milliseconds (MessageMapper does `message.date * 1000`).
            // Web posts already have a millis-precise epoch from the parsed ISO timestamp.
            date = publishedMs,
            editDate = 0L,
            forwardOrigin = forwarded?.let {
                ForwardOrigin.Channel(
                    channelName = it.channelName,
                    authorSignature = null,
                    sourceChatId = stableChatId(it.channelLink.substringAfterLast('/')),
                    sourceHandle = null,
                )
            },
            authorSignature = null,
            reply = null,
            reactions = Reactions(
                totalCount = reactionsList.sumOf { parseShortNumber(it.count) },
                items = reactionsList.map { it.toReactionItem() },
            ),
            commentCount = null,
            albumMessageIds = emptyList(),
            parentId = null,
            isPinned = false,
            verification = null,
            channelContext = null,
        )
    }

    /**
     * Pick the right [PostContent] subtype for the parsed media set. Single-image
     * posts become [PostContent.Photo] (no — actually PhotoAlbum with one item, since
     * PostContent has no standalone Photo) — we always go through PhotoAlbum even for
     * single items so PostBody's gallery rendering is exercised consistently.
     *
     * Each [WebMedia] becomes a [TdMedia] with `fileId=null` and the t.me/s/ CDN
     * URL in [TdMedia.remoteUrl]; [TdMediaImage] now branches on this and routes
     * straight to Coil, bypassing TDLib's MediaCache that has nothing to do here.
     */
    private fun buildContent(
        text: FormattedText,
        media: List<WebMedia>,
        webPreview: TdWebPreview?,
    ): PostContent {
        if (media.isEmpty()) return PostContent.Text(formatted = text, webPreview = webPreview)
        val photoVideoItems = media.mapNotNull { it.toAlbumItem() }
        if (photoVideoItems.isNotEmpty()) {
            return PostContent.PhotoAlbum(items = photoVideoItems, caption = text)
        }
        // Pure non-photo/video media (sticker / voice / document only). Fall back to
        // the text body with a media-kind badge — better than dropping the post.
        val badge = media.joinToString(" ") { mediaPlaceholder(it.kind) }
        val combined = if (text.text.isBlank()) badge else "${text.text}\n\n$badge"
        return PostContent.Text(formatted = FormattedText(combined, text.spans), webPreview = webPreview)
    }

    private fun WebMedia.toAlbumItem(): AlbumItem? = when (kind) {
        WebMedia.Kind.Photo, WebMedia.Kind.Sticker -> AlbumItem.Photo(media = toTdMedia())
        WebMedia.Kind.Video, WebMedia.Kind.RoundVideo -> AlbumItem.Video(
            // media.remoteUrl = poster (thumbnail) for TdMediaImage; the actual
            // video stream lives in remoteVideoUrl below so ExoPlayer doesn't
            // try to play the poster JPEG as video.
            media = toTdMedia(thumbnailFallback = true),
            durationSec = durationSec ?: 0,
            playbackFileId = 0,
            qualities = VideoQualities(
                original = VideoQuality(
                    fileId = 0,
                    width = 0,
                    height = 0,
                    label = "",
                    sizeBytes = 0L,
                ),
            ),
            remoteVideoUrl = url,
        )
        WebMedia.Kind.Gif -> AlbumItem.Animation(
            media = toTdMedia(thumbnailFallback = true),
            playbackFileId = 0,
            remoteVideoUrl = url,
        )
        WebMedia.Kind.Voice, WebMedia.Kind.Document -> null
    }

    /**
     * Build a [TdMedia] for [TdMediaImage]. For video kinds the page only ships a
     * poster URL — fall back to thumbnailUrl when the main url is empty so the
     * gallery placeholder shows the poster, not a broken image.
     */
    private fun WebMedia.toTdMedia(thumbnailFallback: Boolean = false): TdMedia {
        val urlForRender = if (thumbnailFallback) thumbnailUrl ?: url else url
        val w = aspectRatio?.let { (DEFAULT_MEDIA_WIDTH * it).toInt() } ?: DEFAULT_MEDIA_WIDTH
        val h = aspectRatio?.let { DEFAULT_MEDIA_WIDTH } ?: DEFAULT_MEDIA_WIDTH
        return TdMedia(
            fileId = null,
            width = w,
            height = h,
            minithumbBytes = null,
            remoteUrl = urlForRender,
        )
    }

    private const val DEFAULT_MEDIA_WIDTH = 1024

    /**
     * Username → Long chat id. Hash-based, deterministic across runs of the same
     * process. We bias the value to the negative range (channels in TDLib are
     * always negative ids in the format `-100<channelId>`) so when we eventually
     * route deep links by chatId in guest mode there's a clean "is this a
     * synthetic web id?" check via `chatId < -1_000_000_000_000L`.
     */
    fun stableChatId(username: String): Long {
        val base = username.lowercase().hashCode().toLong() and 0xFFFFFFFFL
        return -1_000_000_000_000L - base
    }

    // ---- HTML → FormattedText -------------------------------------------------

    /**
     * Walk the Jsoup-parsed body and build a [FormattedText] with spans matching
     * the TDLib entity model. Spans are emitted at the precise character offset
     * of the assembled plain text — same contract as TDLib's `TextEntity`.
     */
    private fun htmlToFormatted(html: String): FormattedText {
        if (html.isBlank()) return FormattedText.Empty
        val doc = Jsoup.parseBodyFragment(html)
        val text = StringBuilder()
        val spans = mutableListOf<FormattedText.Span>()

        fun walk(node: Node) {
            when (node) {
                is TextNode -> text.append(node.text())
                is Element -> {
                    val start = text.length
                    when (node.normalName()) {
                        "br" -> text.append('\n')
                        "div", "p" -> {
                            if (text.isNotEmpty() && text.last() != '\n') text.append('\n')
                            node.childNodes().forEach { walk(it) }
                        }
                        "blockquote" -> {
                            if (text.isNotEmpty() && text.last() != '\n') text.append('\n')
                            node.childNodes().forEach { walk(it) }
                            spans += FormattedText.Span(start, text.length, FormattedText.Style.BlockQuote)
                        }
                        "b", "strong" -> {
                            node.childNodes().forEach { walk(it) }
                            spans += FormattedText.Span(start, text.length, FormattedText.Style.Bold)
                        }
                        "i", "em" -> {
                            // <i class="emoji"> wraps just a unicode glyph — emit text only.
                            if (node.normalName() == "i" && node.hasClass("emoji")) {
                                val glyph = node.selectFirst("b")?.text() ?: node.ownText()
                                if (glyph.isNotEmpty()) text.append(glyph)
                            } else {
                                node.childNodes().forEach { walk(it) }
                                spans += FormattedText.Span(start, text.length, FormattedText.Style.Italic)
                            }
                        }
                        "u" -> {
                            node.childNodes().forEach { walk(it) }
                            spans += FormattedText.Span(start, text.length, FormattedText.Style.Underline)
                        }
                        "s", "del", "strike" -> {
                            node.childNodes().forEach { walk(it) }
                            spans += FormattedText.Span(start, text.length, FormattedText.Style.Strikethrough)
                        }
                        "code" -> {
                            node.childNodes().forEach { walk(it) }
                            spans += FormattedText.Span(start, text.length, FormattedText.Style.Code)
                        }
                        "pre" -> {
                            node.childNodes().forEach { walk(it) }
                            spans += FormattedText.Span(
                                start,
                                text.length,
                                FormattedText.Style.Pre(language = null),
                            )
                        }
                        "a" -> {
                            val href = node.attr("href").trim()
                            if (node.childNodeSize() == 0 && href.isNotEmpty()) {
                                text.append(href)
                            } else {
                                node.childNodes().forEach { walk(it) }
                            }
                            if (href.isNotEmpty()) {
                                spans += FormattedText.Span(
                                    start,
                                    text.length,
                                    FormattedText.Style.TextUrl(href),
                                )
                            }
                        }
                        "tg-spoiler" -> {
                            node.childNodes().forEach { walk(it) }
                            spans += FormattedText.Span(start, text.length, FormattedText.Style.Spoiler)
                        }
                        "tg-emoji" -> {
                            // Render unicode fallback glyph as text. TDLib's CustomEmoji
                            // span takes a Long id; t.me/s/ ids fit a Long when present
                            // so we attach the span when parseable, otherwise plain text.
                            val fallback = node.selectFirst("b")?.text()
                                ?: node.selectFirst("i.emoji")?.text()
                                ?: ""
                            if (fallback.isNotEmpty()) text.append(fallback)
                            val emojiIdLong = node.attr("emoji-id").trim().toLongOrNull()
                            if (emojiIdLong != null && fallback.isNotEmpty()) {
                                spans += FormattedText.Span(
                                    start,
                                    text.length,
                                    FormattedText.Style.CustomEmoji(emojiIdLong),
                                )
                            }
                        }
                        else -> node.childNodes().forEach { walk(it) }
                    }
                }
                else -> Unit
            }
        }

        doc.body().childNodes().forEach { walk(it) }
        return FormattedText(text.toString().trim(), spans)
    }

    // ---- Helpers --------------------------------------------------------------

    private fun parseIso(iso: String): Long = runCatching {
        OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    }.getOrElse { 0L }

    private fun tdWebPreviewFrom(p: WebPreview): TdWebPreview = TdWebPreview(
        url = p.url,
        siteName = p.siteName.orEmpty(),
        title = p.title.orEmpty(),
        description = p.description.orEmpty(),
        image = p.imageUrl?.let { TdMedia(fileId = null, width = 0, height = 0) },
    )

    private fun WebReaction.toReactionItem(): ReactionItem {
        val kind = when {
            emojiId != null && emojiId.toLongOrNull() != null ->
                ReactionKind.CustomEmoji(emojiId.toLong())
            glyph.isNotBlank() -> ReactionKind.Emoji(glyph)
            else -> ReactionKind.Emoji("·")
        }
        return ReactionItem(kind = kind, count = parseShortNumber(count), isChosen = false)
    }

    /**
     * Parse strings like `12`, `1.5K`, `44.2K`, `2.1M`, `1 234`, `12,345` into
     * an Int. Lossy on suffixed forms but preserves rough magnitude — PostCard's
     * own `formatViews` rebuilds the K/M short form on the way back out, so
     * visually identical. Any unparseable input → 0.
     */
    private fun parseShortNumber(s: String): Int {
        val cleaned = s.trim().replace(" ", " ")
        if (cleaned.isEmpty()) return 0
        val suffix = cleaned.last()
        val multiplier = when (suffix.uppercaseChar()) {
            'K' -> 1_000.0
            'M' -> 1_000_000.0
            'B' -> 1_000_000_000.0
            else -> 1.0
        }
        val numeric = if (multiplier == 1.0) cleaned.replace(Regex("[^0-9.]"), "")
        else cleaned.dropLast(1).replace(Regex("[^0-9.]"), "")
        val value = numeric.toDoubleOrNull() ?: return 0
        return (value * multiplier).toInt()
    }

    private fun mediaPlaceholder(kind: WebMedia.Kind): String = when (kind) {
        WebMedia.Kind.Photo -> "[зображення]"
        WebMedia.Kind.Video -> "[відео]"
        WebMedia.Kind.RoundVideo -> "[відеокружок]"
        WebMedia.Kind.Voice -> "[голосове]"
        WebMedia.Kind.Document -> "[документ]"
        WebMedia.Kind.Sticker -> "[стікер]"
        WebMedia.Kind.Gif -> "[GIF]"
    }
}
