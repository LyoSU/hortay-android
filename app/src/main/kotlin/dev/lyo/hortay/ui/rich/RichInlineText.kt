package dev.lyo.hortay.ui.rich

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import dev.lyo.hortay.data.rich.RichInline
import dev.lyo.hortay.data.rich.RichPlainText
import dev.lyo.hortay.ui.media.CustomEmojiInlineView
import dev.lyo.hortay.ui.media.LocalCustomEmoji
import dev.lyo.hortay.ui.text.LinkAwareText
import dev.lyo.hortay.ui.text.LinkRange
import dev.lyo.hortay.ui.text.LocalHashtagTap
import dev.lyo.hortay.ui.text.LocalLinkConfirm
import dev.lyo.hortay.ui.text.RenderableText
import dev.lyo.hortay.ui.text.SpoilerGroupInfo
import dev.lyo.hortay.ui.text.Unhandled
import dev.lyo.hortay.ui.text.rememberSpoilerReveal
import dev.lyo.hortay.ui.users.LocalUserProfileOpener
import kotlinx.collections.immutable.toImmutableList

/**
 * In-app dispatch for a rich-message anchor / reference tap
 * ([RichInline.AnchorLink] / [RichInline.ReferenceLink]). The first argument is the
 * normalized target name; the second is the external fallback URL. [RichMessageBody]
 * installs an implementation that scrolls to the target block when the name resolves in
 * the current document and otherwise opens the URL. The default is a no-op so standalone
 * previews / tests don't crash on tap.
 */
internal val LocalRichAnchorTap = staticCompositionLocalOf<(name: String, url: String) -> Unit> {
    { _, _ -> }
}

/**
 * Renders a [RichInline] tree as a link-aware [androidx.compose.material3.Text].
 *
 * The tree is flattened into a [RenderableText] by [rememberRichInline] and drawn through
 * the shared [LinkAwareText], so custom-emoji inline content, the spoiler dot-cloud +
 * reveal, the pressed-link highlight, and the long-press link sheet all behave identically
 * to the [dev.lyo.hortay.data.FormattedText] renderer. Link / mention / hashtag / anchor
 * taps route through the SAME CompositionLocals ([LocalUriHandler], [LocalLinkConfirm],
 * [LocalHashtagTap], [LocalUserProfileOpener], [LocalRichAnchorTap]).
 */
@Composable
internal fun RichInlineText(
    inline: RichInline,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
) {
    LinkAwareText(
        renderable = rememberRichInline(inline),
        style = style,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
    )
}

/**
 * Flattens a [RichInline] tree into a [RenderableText]. Mirrors
 * [dev.lyo.hortay.ui.text.rememberRenderableText] but walks the recursive `RichText` tree
 * (styling nests) instead of a flat span list, and adds the three inline decorations the
 * flat model can't express — marked highlight, subscript, superscript.
 */
@Composable
internal fun rememberRichInline(inline: RichInline): RenderableText {
    val accent = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val markedBg = MaterialTheme.colorScheme.secondaryContainer
    val onMarked = MaterialTheme.colorScheme.onSecondaryContainer
    val uriHandler = LocalUriHandler.current
    val confirmMaskedLink = LocalLinkConfirm.current
    val hashtagTap = LocalHashtagTap.current
    val anchorTap = LocalRichAnchorTap.current
    val userOpener = LocalUserProfileOpener.current
    val customEmoji = LocalCustomEmoji.current

    // Content-stable identity: the plain-text projection changes only when the document's
    // text genuinely changes, so spoiler reveal state + captured layout survive the
    // recompositions that hand this a fresh (equal) tree instance.
    val contentKey = remember(inline) { RichPlainText.of(inline) }
    val spoiler = rememberSpoilerReveal(contentKey)

    val userMentionTap = remember(userOpener) { { uid: Long -> userOpener.open(uid) } }

    val built = remember(
        inline, accent, onSurface, codeBg, markedBg, onMarked,
        spoiler.revealedGroups, confirmMaskedLink, hashtagTap, userMentionTap, anchorTap,
    ) {
        buildRichAnnotated(
            root = inline,
            palette = RichInlinePalette(accent, onSurface, codeBg, markedBg, onMarked),
            uriHandler = uriHandler,
            confirmMaskedLink = confirmMaskedLink,
            hashtagTap = hashtagTap,
            userMentionTap = userMentionTap,
            anchorTap = anchorTap,
            revealedGroups = spoiler.revealedGroups,
            reveal = spoiler.reveal,
        )
    }

    LaunchedEffect(built.emojiIds) {
        if (built.emojiIds.isNotEmpty()) customEmoji.request(built.emojiIds)
    }

    val inlineContent = remember(built.emojiIds, built.coveredEmoji, onSurface, spoiler.reveal) {
        buildMap<String, InlineTextContent> {
            built.emojiIds.forEach { id ->
                put(
                    customEmojiTag(id),
                    inlineEmojiContent { CustomEmojiInlineView(customEmojiId = id, modifier = Modifier.fillMaxSize(), tintColor = onSurface) },
                )
            }
            built.coveredEmoji.forEach { (dstPos, groupId) ->
                put(
                    coveredEmojiTag(dstPos),
                    inlineEmojiContent {
                        val interaction = remember { MutableInteractionSource() }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clickable(interactionSource = interaction, indication = null) { spoiler.reveal(groupId) },
                        )
                    },
                )
            }
        }
    }

    return RenderableText(
        text = built.text,
        inlineContent = inlineContent,
        linkRanges = built.linkRanges,
        spoilerGroups = built.spoilerGroups,
        spoilerDispersion = spoiler.dispersion,
        pressableRanges = built.pressableRanges.toImmutableList(),
        contentKey = contentKey,
    )
}

private fun inlineEmojiContent(content: @Composable (String) -> Unit): InlineTextContent =
    InlineTextContent(
        placeholder = Placeholder(
            width = 1.2.em,
            height = 1.2.em,
            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
        ),
        children = content,
    )

internal fun customEmojiTag(id: Long): String = "ce-$id"
private fun coveredEmojiTag(dstPos: Int): String = "rich-spoiler-emoji@$dstPos"

/** Inline placeholder codepoint an [InlineTextContent] slot replaces. */
private const val INLINE_PLACEHOLDER = "￼"

private class RichInlinePalette(
    val accent: Color,
    val onSurface: Color,
    val codeBg: Color,
    val markedBg: Color,
    val onMarked: Color,
)

private class BuiltRich(
    val text: AnnotatedString,
    val linkRanges: List<LinkRange>,
    val pressableRanges: List<IntRange>,
    val spoilerGroups: List<SpoilerGroupInfo>,
    val emojiIds: Set<Long>,
    /** (dst placeholder position, spoiler group id) for each custom emoji under an
     *  unrevealed cover — rendered as a blank tap-to-reveal box. */
    val coveredEmoji: List<Pair<Int, Int>>,
)

/**
 * Mutable accumulator threaded through the recursive [walkRich]. Holds the resolved palette
 * + link listeners, the current reveal set, and the side outputs the walk fills in
 * (link / pressable ranges, custom-emoji ids, spoiler groups). [spoilerCounter] assigns each
 * [RichInline.Spoiler] a stable group id in document order.
 */
private class RichBuildContext(
    val palette: RichInlinePalette,
    val linkStyle: TextLinkStyles,
    val safeOpen: LinkInteractionListener,
    val maskedOpen: LinkInteractionListener,
    val hashtagTap: (String) -> Unit,
    val userMentionTap: (Long) -> Unit,
    val anchorTap: (String, String) -> Unit,
    val revealedGroups: Set<Int>,
    val reveal: (Int) -> Unit,
    val linkRanges: MutableList<LinkRange> = mutableListOf(),
    val pressableRanges: MutableList<IntRange> = mutableListOf(),
    val emojiIds: MutableSet<Long> = mutableSetOf(),
    val coveredEmoji: MutableList<Pair<Int, Int>> = mutableListOf(),
    val spoilerRanges: MutableList<SpoilerGroupInfo> = mutableListOf(),
) {
    var spoilerCounter = 0
}

private fun buildRichAnnotated(
    root: RichInline,
    palette: RichInlinePalette,
    uriHandler: UriHandler,
    confirmMaskedLink: (String) -> Unit,
    hashtagTap: (String) -> Unit,
    userMentionTap: (Long) -> Unit,
    anchorTap: (String, String) -> Unit,
    revealedGroups: Set<Int>,
    reveal: (Int) -> Unit,
): BuiltRich {
    val safeOpen = LinkInteractionListener { link ->
        if (link is LinkAnnotation.Url) runCatching { uriHandler.openUri(link.url) }
    }
    val maskedOpen = LinkInteractionListener { link ->
        if (link !is LinkAnnotation.Url) return@LinkInteractionListener
        if (confirmMaskedLink === Unhandled) runCatching { uriHandler.openUri(link.url) } else confirmMaskedLink(link.url)
    }
    val ctx = RichBuildContext(
        palette = palette,
        linkStyle = TextLinkStyles(SpanStyle(color = palette.accent)),
        safeOpen = safeOpen,
        maskedOpen = maskedOpen,
        hashtagTap = hashtagTap,
        userMentionTap = userMentionTap,
        anchorTap = anchorTap,
        revealedGroups = revealedGroups,
        reveal = reveal,
    )

    val text = buildAnnotatedString { walkRich(root, ctx, covering = null, canLink = true) }

    return BuiltRich(
        text = text,
        linkRanges = ctx.linkRanges,
        pressableRanges = ctx.pressableRanges,
        spoilerGroups = ctx.spoilerRanges,
        emojiIds = ctx.emojiIds,
        coveredEmoji = ctx.coveredEmoji,
    )
}

/**
 * Walks the [RichInline] tree, appending styled runs. `covering` is the group id of the
 * nearest UNREVEALED spoiler ancestor (custom emoji inside it render as blank tap-to-reveal
 * boxes); `canLink` is false once a link or spoiler cover is already open, so we never emit
 * overlapping LinkAnnotations (Compose rejects them) — a covered / nested link degrades to
 * accent-styled text.
 */
private fun AnnotatedString.Builder.walkRich(node: RichInline, ctx: RichBuildContext, covering: Int?, canLink: Boolean) {
    val palette = ctx.palette
    when (node) {
        is RichInline.Plain -> append(node.text)
        is RichInline.Unknown -> append(node.plainText)
        is RichInline.Sequence -> node.parts.forEach { walkRich(it, ctx, covering, canLink) }

        is RichInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { walkRich(node.child, ctx, covering, canLink) }
        is RichInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { walkRich(node.child, ctx, covering, canLink) }
        is RichInline.Underline -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { walkRich(node.child, ctx, covering, canLink) }
        is RichInline.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { walkRich(node.child, ctx, covering, canLink) }
        is RichInline.Fixed -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = palette.codeBg)) { walkRich(node.child, ctx, covering, canLink) }
        is RichInline.Marked -> withStyle(SpanStyle(background = palette.markedBg, color = palette.onMarked)) { walkRich(node.child, ctx, covering, canLink) }
        is RichInline.Subscript -> withStyle(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = SCRIPT_SCALE)) { walkRich(node.child, ctx, covering, canLink) }
        is RichInline.Superscript -> withStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = SCRIPT_SCALE)) { walkRich(node.child, ctx, covering, canLink) }

        // Server pre-renders the timestamp glyphs into `child`; render them verbatim.
        is RichInline.DateTime -> walkRich(node.child, ctx, covering, canLink)

        is RichInline.CustomEmoji -> {
            ctx.emojiIds += node.customEmojiId
            if (covering != null) {
                ctx.coveredEmoji += length to covering
                appendInlineContent(coveredEmojiTag(length), INLINE_PLACEHOLDER)
            } else {
                appendInlineContent(customEmojiTag(node.customEmojiId), INLINE_PLACEHOLDER)
            }
        }
        // Inline math: monospace fallback of the raw expression (no LaTeX layout).
        is RichInline.Math -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = palette.codeBg)) { append(node.expression) }

        is RichInline.Url -> richLink(ctx, node.url, node.child, covering, canLink, masked = true)
        is RichInline.EmailAddress -> richLink(ctx, "mailto:${node.email}", node.child, covering, canLink, masked = false)
        is RichInline.PhoneNumber -> richLink(ctx, "tel:${node.phoneNumber}", node.child, covering, canLink, masked = false)
        is RichInline.Mention -> richLink(ctx, "https://t.me/${node.username.trimStart('@')}", node.child, covering, canLink, masked = false)

        is RichInline.MentionName -> richClickable(ctx, canLink, "rich-mention-${node.userId}", palette.accent, { ctx.userMentionTap(node.userId) }) { walkRich(node.child, ctx, covering, canLink = false) }
        is RichInline.Hashtag -> richClickable(ctx, canLink, "rich-hashtag-${node.hashtag}", palette.accent, { ctx.hashtagTap(node.hashtag) }) { walkRich(node.child, ctx, covering, canLink = false) }
        is RichInline.ReferenceLink -> richClickable(ctx, canLink, "rich-ref-${node.referenceName}", palette.accent, { ctx.anchorTap(node.referenceName, node.url) }) { walkRich(node.child, ctx, covering, canLink = false) }
        is RichInline.AnchorLink -> richClickable(ctx, canLink, "rich-anchor-${node.anchorName}", palette.accent, { ctx.anchorTap(node.anchorName, node.url) }) { walkRich(node.child, ctx, covering, canLink = false) }

        // Bot commands are accent-coloured but not tappable (no in-app command dispatch),
        // matching the FormattedText renderer.
        is RichInline.BotCommand -> withStyle(SpanStyle(color = palette.accent)) { walkRich(node.child, ctx, covering, canLink) }
        // Reference is the footnote TARGET (registered by RichMessageBody), not a link;
        // cashtag / bank-card have no in-app handler → plain text.
        is RichInline.Reference -> walkRich(node.child, ctx, covering, canLink)
        is RichInline.Cashtag -> walkRich(node.child, ctx, covering, canLink)
        is RichInline.BankCardNumber -> walkRich(node.child, ctx, covering, canLink)
        is RichInline.Anchor -> Unit // invisible scroll target, renders nothing

        is RichInline.Spoiler -> {
            val groupId = ctx.spoilerCounter++
            val start = length
            when {
                groupId in ctx.revealedGroups ->
                    withStyle(SpanStyle(color = palette.onSurface)) { walkRich(node.child, ctx, covering = null, canLink) }
                canLink -> {
                    pushLink(
                        LinkAnnotation.Clickable(
                            "rich-spoiler-$groupId",
                            TextLinkStyles(SpanStyle(color = Color.Transparent)),
                            LinkInteractionListener { ctx.reveal(groupId) },
                        ),
                    )
                    withStyle(SpanStyle(color = Color.Transparent)) { walkRich(node.child, ctx, covering = groupId, canLink = false) }
                    pop()
                }
                // Already inside a link → can't add the reveal clickable; keep the cover
                // shimmer, tap falls through to the enclosing link.
                else -> withStyle(SpanStyle(color = Color.Transparent)) { walkRich(node.child, ctx, covering = groupId, canLink = false) }
            }
            val end = length
            if (end > start) {
                ctx.spoilerRanges += SpoilerGroupInfo(
                    groupId = groupId,
                    seed = groupId * 31 + start,
                    ranges = listOf(start until end),
                )
            }
        }
    }
}

/** Adds a URL-backed link (or accent text when covered / nested). */
private fun AnnotatedString.Builder.richLink(
    ctx: RichBuildContext,
    url: String,
    child: RichInline,
    covering: Int?,
    canLink: Boolean,
    masked: Boolean,
) {
    if (!canLink) {
        withStyle(SpanStyle(color = ctx.palette.accent)) { walkRich(child, ctx, covering, canLink = false) }
        return
    }
    val start = length
    pushLink(LinkAnnotation.Url(url, ctx.linkStyle, if (masked) ctx.maskedOpen else ctx.safeOpen))
    walkRich(child, ctx, covering, canLink = false)
    pop()
    val end = length
    if (end > start) {
        ctx.linkRanges += LinkRange(start, end, url)
        ctx.pressableRanges += start until end
    }
}

/** Adds a Clickable-backed tappable entity (or accent text when covered / nested). */
private fun AnnotatedString.Builder.richClickable(
    ctx: RichBuildContext,
    canLink: Boolean,
    tag: String,
    color: Color,
    onTap: () -> Unit,
    body: AnnotatedString.Builder.() -> Unit,
) {
    if (!canLink) {
        withStyle(SpanStyle(color = color)) { body() }
        return
    }
    val start = length
    pushLink(LinkAnnotation.Clickable(tag, TextLinkStyles(SpanStyle(color = color)), LinkInteractionListener { onTap() }))
    body()
    pop()
    val end = length
    if (end > start) ctx.pressableRanges += start until end
}

/** Subscript / superscript glyph scale, relative to the surrounding run. */
private val SCRIPT_SCALE = 0.75.em
