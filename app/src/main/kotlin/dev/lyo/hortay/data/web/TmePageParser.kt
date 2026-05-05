package dev.lyo.hortay.data.web

import kotlinx.collections.immutable.toPersistentList
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parses HTML from `https://t.me/s/<username>` into a [WebChannelPage].
 *
 * Why pure Jsoup, no Android types: parser is the most fragile part of the web pipeline
 * (Telegram tweaks CSS classes once or twice a year), so it must be cheap to test on the
 * JVM with snapshot HTML and no Robolectric. All Android-aware code lives in
 * [WebTelegramClient] and the UI layer.
 *
 * Defensive contract: a single malformed post must not break the whole page. Each post
 * is parsed inside a `runCatching` so a layout regression on one weird message doesn't
 * bring down the entire feed.
 *
 * Selector evidence: every CSS class used here was confirmed against ≥4 real
 * t.me/s/<channel> snapshots (durov, telegram, nexta_live, disgustingmen, meduzalive,
 * varlamov_news, bbbreaking, tginfouk) committed under app/src/test/resources/tme-samples.
 * If Telegram changes their preview HTML, [TmePageParserTest] fails loudly — that's the
 * whole point of the snapshot suite.
 */
object TmePageParser {

    /**
     * Parse a full t.me/s/<username> HTML page. Returns null when the page is clearly not
     * a channel preview (404 page, /privatechannel redirect, login wall).
     */
    fun parse(html: String, username: String): WebChannelPage? {
        val doc = Jsoup.parse(html)
        val channel = parseChannelInfo(doc, username) ?: return null
        val posts = parsePosts(doc)
        // Cursor is the seq id of the OLDEST post; caller paginates with `?before=<seq>`.
        val olderCursor = posts.minByOrNull { it.seq }?.seq?.toString()
        return WebChannelPage(
            channel = channel,
            posts = posts.toPersistentList(),
            olderCursor = olderCursor,
        )
    }

    private fun parseChannelInfo(doc: Document, fallbackUsername: String): WebChannelInfo? {
        // Real markup: `<div class="tgme_channel_info_header_title"><span dir="auto">…</span></div>`
        val titleSpan = doc.selectFirst(".tgme_channel_info_header_title span")
            ?: doc.selectFirst(".tgme_channel_info_header_title")
            ?: return null
        val title = titleSpan.text().trim().ifEmpty { return null }

        val username = doc.selectFirst(".tgme_channel_info_header_username a")
            ?.text()
            ?.removePrefix("@")
            ?.ifBlank { null }
            ?: fallbackUsername

        val description = doc.selectFirst(".tgme_channel_info_description")?.text().orEmpty()

        // Real avatar element: `<img class="tgme_page_photo_image bgcolor2" src="…">`. The
        // bgcolor* class varies per channel; we match by the stable class.
        val avatarUrl = doc.selectFirst("img.tgme_page_photo_image")?.attr("src")
            ?.ifBlank { null }

        // Counters: each `.tgme_channel_info_counter` has a `.counter_value` and
        // `.counter_type`. The subscriber/member count is always the FIRST counter —
        // verified across 8 real snapshots (durov, telegram, nexta_live, disgustingmen,
        // meduzalive, varlamov_news, bbbreaking, tginfouk), with the trailing counters
        // (photos, videos, files, links) following in a stable order regardless of
        // channel locale. Reading by position avoids a brittle locale-keyword list.
        val subscribers = doc.selectFirst(".tgme_channel_info_counter .counter_value")
            ?.text()
            ?.ifBlank { null }

        // Verified mark: `<i class="verified-icon">`. Telegram occasionally adds a
        // separate Premium/Fragment-username variant under different classes — match
        // any `.verified-icon` descendant.
        val isVerified = doc.selectFirst(".tgme_channel_info_header .verified-icon") != null

        return WebChannelInfo(
            username = username,
            title = title,
            description = description,
            avatarUrl = avatarUrl,
            subscribers = subscribers,
            isVerified = isVerified,
        )
    }

    private fun parsePosts(doc: Document): List<WebPost> {
        // The pagination "load more" placeholder is itself wrapped in
        // `.tgme_widget_message_wrap.tgme_widget_message_centered` — exclude it so we
        // don't surface it as a post with no data.
        val wrappers = doc.select(
            ".tgme_widget_message_wrap:not(:has(.tgme_widget_message_centered))"
        )
        if (wrappers.isEmpty()) return emptyList()
        return wrappers.mapNotNull { wrapper ->
            runCatching { parseSinglePost(wrapper) }.getOrNull()
        }
    }

    private fun parseSinglePost(wrapper: Element): WebPost? {
        val msg = wrapper.selectFirst(".tgme_widget_message[data-post]") ?: return null
        val dataPost = msg.attr("data-post").ifBlank { return null }
        val seq = dataPost.substringAfterLast('/').toLongOrNull() ?: return null

        val publishedAt = msg.selectFirst(".tgme_widget_message_date time")
            ?.attr("datetime")
            .orEmpty()

        // Inner HTML preserves <b>/<i>/<a>/<tg-spoiler>/<tg-emoji> spans — UI layer
        // interprets them via RichText. We deliberately don't strip to text(): formatting
        // matters for the user experience.
        val textHtml = msg.selectFirst(".tgme_widget_message_text")?.html().orEmpty()

        val media = parseMedia(msg)
        val webPreview = parseWebPreview(msg)
        val forwardedFrom = parseForward(msg)
        val views = msg.selectFirst(".tgme_widget_message_views")?.text()?.ifBlank { null }
        val reactions = parseReactions(msg)

        return WebPost(
            id = dataPost,
            seq = seq,
            publishedAt = publishedAt,
            textHtml = textHtml,
            media = media.toPersistentList(),
            webPreview = webPreview,
            forwardedFrom = forwardedFrom,
            views = views,
            reactions = reactions.toPersistentList(),
        )
    }

    private fun parseMedia(msg: Element): List<WebMedia> {
        val out = mutableListOf<WebMedia>()

        // Photos: rendered as `<a class="tgme_widget_message_photo_wrap …" style="width:Wpx;
        // background-image:url('…');padding-top:H%">`. Album posts contain multiple
        // .tgme_widget_message_photo_wrap inside .tgme_widget_message_grouped_layer. We
        // accept either case — flat list of photos preserves source order.
        msg.select("a.tgme_widget_message_photo_wrap").forEach { photo ->
            val url = extractCssBackgroundUrl(photo.attr("style")) ?: return@forEach
            out += WebMedia(
                kind = WebMedia.Kind.Photo,
                url = url,
                aspectRatio = parsePaddingAspect(photo) ?: parseDataRatio(photo),
                durationSec = null,
                thumbnailUrl = null,
            )
        }

        // Videos: the `<video>` source lives inside `.tgme_widget_message_video_wrap`,
        // while the user-visible thumbnail (and click target) is the sibling/preceding
        // `<a class="tgme_widget_message_video_player">` whose inner `<i class="
        // tgme_widget_message_video_thumb">` carries the poster as a CSS background.
        // Duration is rendered by `<time class="message_video_duration">` (NOT the
        // `tgme_widget_` namespace — confirmed across all four real samples that have
        // videos). Round videos and GIFs share the same wrapper but with a marker class
        // on the player element.
        val videoWraps = msg.select(".tgme_widget_message_video_wrap")
        val videoPlayers = msg.select("a.tgme_widget_message_video_player")
        val videoCount = maxOf(videoWraps.size, videoPlayers.size)
        for (i in 0 until videoCount) {
            val wrap = videoWraps.getOrNull(i)
            val player = videoPlayers.getOrNull(i)
            val src = wrap?.selectFirst("video")?.attr("src")?.ifBlank { null }
                ?: continue // a `_video_player` link with no playable source is just a "view in
                            // Telegram" placeholder — no media to surface.
            val thumb = player?.selectFirst(".tgme_widget_message_video_thumb")
                ?.attr("style")
                ?.let(::extractCssBackgroundUrl)
            val isRound = (player?.classNames()?.any { it.contains("round", ignoreCase = true) } == true)
            val isGif = (player?.classNames()?.any { it.equals("gif", ignoreCase = true) } == true)
            val duration = msg.select(".message_video_duration").getOrNull(i)
                ?.text()
                ?.let(::parseDurationToSeconds)
            out += WebMedia(
                kind = when {
                    isRound -> WebMedia.Kind.RoundVideo
                    isGif -> WebMedia.Kind.Gif
                    else -> WebMedia.Kind.Video
                },
                url = src,
                aspectRatio = parsePaddingAspect(wrap) ?: player?.let(::parseDataRatio),
                durationSec = duration,
                thumbnailUrl = thumb,
            )
        }

        // Voice notes — `.tgme_widget_message_voice` with an `<audio>` source.
        msg.select(".tgme_widget_message_voice").forEach { voice ->
            val src = voice.selectFirst("audio")?.attr("src")
                ?: voice.attr("data-src")
            if (src.isNullOrBlank()) return@forEach
            out += WebMedia(
                kind = WebMedia.Kind.Voice,
                url = src,
                aspectRatio = null,
                durationSec = voice.selectFirst(".message_voice_duration, .tgme_widget_message_voice_duration")
                    ?.text()
                    ?.let(::parseDurationToSeconds),
                thumbnailUrl = null,
            )
        }

        // Documents: link-only entries that we surface as opaque references — the
        // anonymous client cannot actually download these without an auth session.
        msg.select(".tgme_widget_message_document").forEach { doc ->
            val href = doc.attr("href").ifBlank { doc.selectFirst("a")?.attr("href").orEmpty() }
            if (href.isBlank()) return@forEach
            out += WebMedia(
                kind = WebMedia.Kind.Document,
                url = href,
                aspectRatio = null,
                durationSec = null,
                thumbnailUrl = null,
            )
        }

        // Static sticker thumbnails. t.me/s/ never serves the animated payload
        // (TGS/WebM) for stickers, only a WEBP fallback.
        msg.select(".tgme_widget_message_sticker").forEach { sticker ->
            val src = sticker.selectFirst("img")?.attr("src")
                ?: extractCssBackgroundUrl(sticker.attr("style"))
            if (src.isNullOrBlank()) return@forEach
            out += WebMedia(
                kind = WebMedia.Kind.Sticker,
                url = src,
                aspectRatio = null,
                durationSec = null,
                thumbnailUrl = null,
            )
        }

        return out
    }

    private fun parseWebPreview(msg: Element): WebPreview? {
        val preview = msg.selectFirst(".tgme_widget_message_link_preview") ?: return null
        // The wrapper itself is sometimes `<a>` (single link) and sometimes `<div>`
        // containing nested links. Try both shapes.
        val url = preview.attr("href").ifBlank {
            preview.selectFirst("a")?.attr("href").orEmpty()
        }
        if (url.isBlank()) return null
        val siteName = preview.selectFirst(".link_preview_site_name")?.text()?.ifBlank { null }
        val title = preview.selectFirst(".link_preview_title")?.text()?.ifBlank { null }
        val description = preview.selectFirst(".link_preview_description")?.text()?.ifBlank { null }
        val image = preview.selectFirst(".link_preview_image, .link_preview_video_thumb")
            ?.attr("style")
            ?.let(::extractCssBackgroundUrl)
            ?: preview.selectFirst("img")?.attr("src")?.ifBlank { null }
        return WebPreview(
            url = url,
            siteName = siteName,
            title = title,
            description = description,
            imageUrl = image,
        )
    }

    private fun parseForward(msg: Element): WebForwardSource? {
        // Real markup pattern: an outer `.tgme_widget_message_forwarded_from` wrapper
        // contains an `<a class="tgme_widget_message_forwarded_from_name">` whose
        // text is the source channel's title. The wrapper carries an additional
        // accent_color class so we match by the prefix only.
        val link = msg.selectFirst("a.tgme_widget_message_forwarded_from_name") ?: return null
        return WebForwardSource(
            channelName = link.text().trim(),
            channelLink = link.attr("href"),
        )
    }

    /**
     * Parse the reactions row. Real markup falls into three shapes (ranked by
     * prevalence across our 8 fixture channels):
     *
     *   1. Unicode reaction (~75 % of all reactions surveyed):
     *      `<span class="tgme_reaction"><i class="emoji" style="…"><b>🔥</b></i>N</span>`
     *      The glyph lives in the `<b>` text node — that's our primary source.
     *
     *   2. Custom-emoji reaction (~20 %, concentrated on durov-style large channels):
     *      `<span class="tgme_reaction"><tg-emoji emoji-id="X"></tg-emoji>N</span>`
     *      The static page only carries the id; Telegram paints the glyph at runtime
     *      via JS. We expose the id so the UI can resolve it via TDLib once the user
     *      signs in. For anon mode it falls back to a neutral chip.
     *
     *   3. Paid stars (~5 %):
     *      `<span class="tgme_reaction tgme_reaction_paid"><i class="icon-telegram-stars"/>N</span>`
     *      Marked by the `tgme_reaction_paid` class; the glyph is rendered as an icon
     *      we substitute with a "⭐" string.
     *
     * The count is the trailing text node of the `<span>` in all three shapes — no
     * dedicated count element exists.
     */
    private fun parseReactions(msg: Element): List<WebReaction> {
        val container = msg.selectFirst(".tgme_widget_message_reactions") ?: return emptyList()
        return container.select("span.tgme_reaction").map { span ->
            val isPaid = span.hasClass("tgme_reaction_paid")
            val tgEmoji = span.selectFirst("tg-emoji")
            val emojiId = tgEmoji?.attr("emoji-id")?.ifBlank { null }

            // Glyph resolution: try the unicode `<b>` fallback first (most common),
            // then any plain text inside <tg-emoji> (some legacy layouts), then the
            // paid-star sentinel. Anything that ends up blank means we have only the
            // emoji-id — UI handles that case explicitly.
            val unicodeGlyph = span.selectFirst("i.emoji b")?.text()
                ?: tgEmoji?.selectFirst("b")?.text()
            val glyph = unicodeGlyph?.takeIf { it.isNotBlank() }
                ?: tgEmoji?.ownText()?.takeIf { it.isNotBlank() }
                ?: if (isPaid) PAID_REACTION_GLYPH else ""

            // Count is whatever text remains in the span after the emoji element —
            // a direct text-node join, ignoring text inside child elements (the glyph
            // would otherwise concatenate into the count for unicode reactions).
            val count = span.textNodes()
                .joinToString("") { it.text() }
                .trim()
            WebReaction(
                glyph = glyph,
                emojiId = emojiId,
                count = count,
                isPaid = isPaid,
            )
        }
    }

    private fun extractCssBackgroundUrl(style: String): String? {
        if (style.isBlank()) return null
        val match = BACKGROUND_URL_REGEX.find(style) ?: return null
        return match.groupValues[1].takeIf { it.isNotBlank() }
    }

    /**
     * Telegram preview lays out media with `padding-top:<ratio>%` inside the wrapper
     * to reserve aspect-ratio-correct space before the asset loads. We extract that to
     * size our Compose placeholders identically — without it, posts visibly jump as
     * media materializes.
     */
    private fun parsePaddingAspect(el: Element?): Float? {
        val style = el?.attr("style")?.ifBlank { null } ?: return null
        val match = PADDING_PCT_REGEX.find(style) ?: return null
        val percent = match.groupValues[2].toFloatOrNull() ?: return null
        if (percent <= 0f) return null
        return 100f / percent
    }

    /**
     * Album items also carry `data-ratio` (width/height) on the `_video_player` /
     * `_photo_wrap` element. Used as a fallback when there's no inline padding-top.
     */
    private fun parseDataRatio(el: Element): Float? {
        val raw = el.attr("data-ratio").ifBlank { return null }
        return raw.toFloatOrNull()
    }

    private fun parseDurationToSeconds(text: String): Int? {
        // Format is "M:SS" or "H:MM:SS". Robust to unexpected inputs.
        val parts = text.trim().split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    private val BACKGROUND_URL_REGEX =
        Regex("""background-image\s*:\s*url\(\s*['"]?([^'")]+)['"]?\s*\)""")

    private val PADDING_PCT_REGEX =
        Regex("""padding-(top|bottom)\s*:\s*([\d.]+)%""")

    /** Visible fallback for paid-stars reactions when the static markup omits a glyph. */
    private const val PAID_REACTION_GLYPH = "⭐"
}
