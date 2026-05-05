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

    /**
     * Test-visible hook around [htmlToFormatted]. The walker is private (it owns
     * [trimWithSpans] and a few other helpers); exposing it here keeps the
     * surface narrow but lets [WebPostAdapterFormattingTest] guard the offset
     * contract against future regressions.
     */
    @androidx.annotation.VisibleForTesting
    internal fun htmlToFormattedForTest(html: String): FormattedText = htmlToFormatted(html)

    // ---- HTML → FormattedText -------------------------------------------------

    /**
     * Two-phase HTML → FormattedText conversion. The phases are deliberately
     * isolated so the contract of each is small enough to test exhaustively:
     *
     *   Phase 1 — [emitVerbatim]: walk the Jsoup tree and emit plain text
     *     plus span markers WITHOUT any whitespace normalisation. Block
     *     elements emit a single `'\n'` marker before AND after their
     *     content; `<br>` emits exactly one `'\n'`; TextNodes emit their
     *     normalized text verbatim. The walker is purely structural — it
     *     never decides what whitespace is "phantom" or "real".
     *
     *   Phase 2 — [normaliseWhitespace]: a single linear pass over the raw
     *     buffer that applies all whitespace rules in one place:
     *       (a) collapse runs of inline whitespace to one ' ',
     *       (b) strip inline whitespace adjacent to '\n',
     *       (c) cap consecutive '\n' at 2 (one blank line max),
     *       (d) drop leading + trailing whitespace.
     *     Every source character maps to a destination position via an
     *     explicit `srcToDst` table; spans are re-anchored through that
     *     table so offsets stay correct under every collapse / drop above.
     *
     * Why this shape: the previous implementation interleaved emission with
     * normalisation (boundary checks in TextNode, `ensureLineBreak` helpers,
     * trailing-whitespace trims inside the walker). Each rule lived in two
     * places, edge cases needed cross-references between them, and a single
     * Telegram-markup quirk could expose the gap. The 2-phase split lets the
     * walker stay dumb and the normaliser stay total — every input string
     * produces a fully-canonicalised output regardless of how the walker
     * arrived at it.
     */
    private fun htmlToFormatted(html: String): FormattedText {
        if (html.isBlank()) return FormattedText.Empty
        val (rawText, rawSpans) = emitVerbatim(html)
        return normaliseWhitespace(rawText, rawSpans)
    }

    /** Phase 1 output: untrimmed, un-collapsed text + spans referencing it. */
    private data class RawWalk(val text: String, val spans: List<FormattedText.Span>)

    /** Phase 1 — see [htmlToFormatted]. */
    private fun emitVerbatim(html: String): RawWalk {
        val doc = Jsoup.parseBodyFragment(html)
        val text = StringBuilder()
        val spans = mutableListOf<FormattedText.Span>()

        fun walk(node: Node) {
            when (node) {
                is TextNode -> text.append(node.text())
                is Element -> {
                    val name = node.normalName()
                    when (name) {
                        "br" -> text.append('\n')

                        // Block-level wrappers. Emit a paragraph break (2 newlines)
                        // on BOTH sides — visually one blank line above and below,
                        // matching native HTML rendering of `<div>` / `<p>`. The
                        // normaliser caps consecutive newlines at 2, so wrapping
                        // every block this way stays correct regardless of neighbour
                        // shape (TextNode whitespace, another block, `<br><br>`,
                        // etc). `<br>` keeps emitting a single `'\n'` so that
                        // `<br><br>` lands on the same paragraph-break footprint.
                        "div", "p" -> {
                            text.append("\n\n")
                            node.childNodes().forEach { walk(it) }
                            text.append("\n\n")
                        }
                        "blockquote" -> {
                            text.append("\n\n")
                            val start = text.length
                            node.childNodes().forEach { walk(it) }
                            spans += FormattedText.Span(start, text.length, FormattedText.Style.BlockQuote)
                            text.append("\n\n")
                        }
                        "pre" -> {
                            text.append("\n\n")
                            val start = text.length
                            node.childNodes().forEach { walk(it) }
                            spans += FormattedText.Span(
                                start,
                                text.length,
                                FormattedText.Style.Pre(language = null),
                            )
                            text.append("\n\n")
                        }

                        // Inline styling.
                        "b", "strong" -> {
                            val start = text.length
                            node.childNodes().forEach { walk(it) }
                            spans += FormattedText.Span(start, text.length, FormattedText.Style.Bold)
                        }
                        "i", "em" -> {
                            if (name == "i" && node.hasClass("emoji")) {
                                // <i class="emoji"> is a Telegram-specific unicode
                                // wrapper — emit just the inner glyph, no italic.
                                val glyph = node.selectFirst("b")?.text() ?: node.ownText()
                                if (glyph.isNotEmpty()) text.append(glyph)
                            } else {
                                val start = text.length
                                node.childNodes().forEach { walk(it) }
                                spans += FormattedText.Span(start, text.length, FormattedText.Style.Italic)
                            }
                        }
                        "u" -> {
                            val start = text.length
                            node.childNodes().forEach { walk(it) }
                            spans += FormattedText.Span(start, text.length, FormattedText.Style.Underline)
                        }
                        "s", "del", "strike" -> {
                            val start = text.length
                            node.childNodes().forEach { walk(it) }
                            spans += FormattedText.Span(start, text.length, FormattedText.Style.Strikethrough)
                        }
                        "code" -> {
                            val start = text.length
                            node.childNodes().forEach { walk(it) }
                            spans += FormattedText.Span(start, text.length, FormattedText.Style.Code)
                        }
                        "a" -> {
                            val href = node.attr("href").trim()
                            val start = text.length
                            if (node.childNodeSize() == 0 && href.isNotEmpty()) {
                                text.append(href)
                            } else {
                                node.childNodes().forEach { walk(it) }
                            }
                            if (href.isNotEmpty() && text.length > start) {
                                spans += FormattedText.Span(
                                    start,
                                    text.length,
                                    FormattedText.Style.TextUrl(href),
                                )
                            }
                        }
                        "tg-spoiler" -> {
                            val start = text.length
                            node.childNodes().forEach { walk(it) }
                            spans += FormattedText.Span(start, text.length, FormattedText.Style.Spoiler)
                        }
                        "tg-emoji" -> {
                            // Append the unicode fallback glyph; attach a CustomEmoji
                            // span over its range so the renderer can swap in an
                            // inline content placeholder. The span MUST start at
                            // text.length BEFORE the glyph is appended.
                            val fallback = node.selectFirst("b")?.text()
                                ?: node.selectFirst("i.emoji")?.text()
                                ?: ""
                            val start = text.length
                            if (fallback.isNotEmpty()) text.append(fallback)
                            val emojiIdLong = node.attr("emoji-id").trim().toLongOrNull()
                            if (emojiIdLong != null && text.length > start) {
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
                else -> Unit // comments, doctype etc.
            }
        }

        doc.body().childNodes().forEach { walk(it) }
        return RawWalk(text.toString(), spans)
    }

    /**
     * Phase 2 — single linear pass that canonicalises whitespace and
     * re-anchors every span through an explicit src→dst position map.
     *
     * Rules, applied left-to-right with a one-character lookbehind on the
     * destination buffer:
     *   1. `'\n'` (newline) — strip any inline whitespace already emitted
     *      to `out` (so we never produce `" \n"`), then count consecutive
     *      newlines already at the tail of `out`. Append `'\n'` only if
     *      we have fewer than two there yet (cap at one blank line). Drop
     *      newlines entirely while `out` is empty (leading-whitespace trim).
     *   2. inline whitespace — drop while `out` is empty, drops directly
     *      after `'\n'`, drop after a space already in `out` (collapse
     *      runs). Otherwise emit a single `' '`.
     *   3. any other character — emit verbatim.
     * After the pass, strip trailing whitespace from `out`.
     *
     * Each input position records `srcToDst[i] = current_out_length` either
     * before or at the moment we decide whether to emit. Positions inside a
     * collapsed run share the same destination index — span endpoints land
     * cleanly on character boundaries either way.
     *
     * Idempotent: re-running on already-normalised text is a no-op.
     */
    private fun normaliseWhitespace(
        text: String,
        spans: List<FormattedText.Span>,
    ): FormattedText {
        if (text.isEmpty()) return FormattedText.Empty
        val n = text.length
        val out = StringBuilder(n)
        // srcToDst[i] = position in `out` corresponding to source index i.
        // srcToDst[n] is the destination length at end-of-input — used by spans
        // whose `end` is exclusive at the source's last character.
        val srcToDst = IntArray(n + 1)

        for (i in 0 until n) {
            val c = text[i]
            when {
                c == '\n' -> {
                    // Strip dangling inline whitespace before the newline.
                    while (out.isNotEmpty() && out.last() != '\n' && out.last().isWhitespace()) {
                        out.deleteCharAt(out.length - 1)
                    }
                    val trailingNewlines = countTrailingNewlines(out)
                    srcToDst[i] = out.length
                    if (out.isNotEmpty() && trailingNewlines < 2) out.append('\n')
                    // out empty → leading newlines drop; trailingNewlines ≥ 2 → cap.
                }
                c.isWhitespace() -> {
                    val skip = out.isEmpty() || out.last() == '\n' || out.last() == ' '
                    srcToDst[i] = out.length
                    if (!skip) out.append(' ')
                }
                else -> {
                    srcToDst[i] = out.length
                    out.append(c)
                }
            }
        }
        // Trailing whitespace.
        while (out.isNotEmpty() && out.last().isWhitespace()) {
            out.deleteCharAt(out.length - 1)
        }
        srcToDst[n] = out.length

        val finalText = out.toString()
        val finalLen = finalText.length
        val remappedSpans = spans.mapNotNull { sp ->
            val s = sp.start.coerceIn(0, n)
            val e = sp.end.coerceIn(s, n)
            val ns = srcToDst[s].coerceIn(0, finalLen)
            val ne = srcToDst[e].coerceIn(ns, finalLen)
            if (ne == ns) null else FormattedText.Span(ns, ne, sp.style)
        }
        return FormattedText(finalText, remappedSpans)
    }

    private fun countTrailingNewlines(buf: StringBuilder): Int {
        var k = buf.length - 1
        var count = 0
        while (k >= 0 && buf[k] == '\n') { count++; k-- }
        return count
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
        // The previous implementation dropped `it` (the parsed URL) and returned
        // a TdMedia with no fileId AND no remoteUrl — Coil had nothing to fetch
        // and `WebPreviewCard` rendered an empty 72dp box where the thumbnail
        // should have been. Pass the URL through so [TdMediaImage]'s remote-URL
        // fast path can serve the image.
        image = p.imageUrl?.let { url ->
            TdMedia(fileId = null, width = 0, height = 0, remoteUrl = url)
        },
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
