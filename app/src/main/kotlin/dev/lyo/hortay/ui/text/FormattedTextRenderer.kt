package dev.lyo.hortay.ui.text

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.ui.media.CustomEmojiInlineView
import dev.lyo.hortay.ui.media.LocalCustomEmoji
import dev.lyo.hortay.ui.util.rememberReducedMotion
import kotlinx.coroutines.launch

/**
 * Renderable view of a [FormattedText]: the [AnnotatedString] to hand to a `Text` plus
 * the [InlineTextContent] map and link-range / spoiler-group metadata.
 *
 * Spoiler model: TDLib commonly emits the *same* visual cover as MULTIPLE Spoiler
 * entities — for example `||hidden 🎉 phrase||` becomes Spoiler[0..7] + Spoiler[8..15]
 * with the CustomEmoji entity living in the 1-char gap at index 7. Treating each entity
 * as its own click target makes the cover reveal in halves, which the user reads as
 * "the emoji and the text separate weirdly".
 *
 * To match Telegram's single-cover UX we group spoiler entities by transitive
 * adjacency (gap ≤ 1 source character — covers the codepoint-of-CustomEmoji case) at
 * build time. Each group renders as ONE shimmer over the union of its dst ranges with
 * a single shared seed, and reveals atomically with one [Animatable] for the whole
 * group. Genuinely distant spoilers — different paragraphs, different hidden phrases
 * — fall into separate groups and still reveal independently.
 */
@Immutable
data class RenderableText(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
    val linkRanges: List<LinkRange> = emptyList(),
    val spoilerGroups: List<SpoilerGroupInfo> = emptyList(),
    val spoilerDispersion: (Int) -> Float? = { _ -> null },
    /**
     * Paragraph-level block elements (block quotes, code blocks) in dst coordinates.
     * Drawn as a tinted box + accent bar BEHIND the single [Text] by [LinkAwareText]
     * (see its KDoc). Kept as metadata rather than separate composables so the whole
     * body stays one [Text] — the `maxLines` clamp and "Показати більше" toggle keep
     * working, and every surface (feed / channel / comments / detail) renders the block
     * identically regardless of the clamp.
     */
    val blockDecorations: List<BlockDecoration> = emptyList(),
    /**
     * Dst ranges of every tappable entity (URL / @mention / inline mention / hashtag).
     * [LinkAwareText] paints a padded rounded highlight behind whichever one is currently
     * pressed, so tapping any link kind gives the same Telegram-style press feedback.
     */
    val pressableRanges: List<IntRange> = emptyList(),
    /**
     * Identity that downstream composables should pass to `remember(...)` instead of
     * the full [RenderableText] or [text]. Stable across:
     *  * `spoilerDispersion` lambda churn (data-class equality of the wrapping
     *    RenderableText fails every recomposition because of the lambda).
     *  * Spoiler reveal flips (the underlying AnnotatedString changes colour spans
     *    when a spoiler is revealed; keying on `text` would reset expand-state).
     *  * Reactions / view counts / read-cursor updates that hand the parent a fresh
     *    FormattedText instance whose source content is unchanged.
     * Derived from the FormattedText's source text only — if the author edits the
     * post, this key flips and downstream state (expand toggle, long-press sheet)
     * legitimately resets.
     */
    val contentKey: Any = text,
) {
    companion object {
        val Empty = RenderableText(AnnotatedString(""), emptyMap())
    }
}

/** Dst-coordinate URL range for hit-testing long-press gestures. */
@Immutable
data class LinkRange(val start: Int, val end: Int, val url: String)

/**
 * A paragraph-level block element to paint behind the text. [start]/[end] are dst
 * (AnnotatedString) coordinates; [language] is the optional code-block language label
 * (only meaningful for [Kind.Code], from `TextEntityTypePreCode`).
 */
@Immutable
data class BlockDecoration(
    val start: Int,
    val end: Int,
    val kind: Kind,
    val language: String? = null,
) {
    enum class Kind { Quote, Code }
}

/**
 * One logical spoiler cover (group of adjacent TDLib Spoiler entities that visually
 * read as a single block). [seed] is shared across all ranges so the dot pattern is
 * continuous across entity boundaries. [groupId] is the index into
 * [RenderableText.spoilerGroups] — passed to `spoilerDispersion`. [ranges] are
 * dst-coordinate IntRanges (start..end-1) to be unioned into a Path for clipping.
 */
@Immutable
data class SpoilerGroupInfo(
    val groupId: Int,
    val seed: Int,
    val ranges: List<IntRange>,
)

/**
 * Convert a [FormattedText] into a Compose [AnnotatedString], inline-content map for
 * custom emoji, link metadata, and spoiler-group metadata.
 *
 * Spoiler grouping (see [RenderableText] doc): we project spoiler entities into source
 * ranges, then run a union-find / connected-components pass over transitive adjacency
 * (gap ≤ 1 char). Each group gets one shared [Animatable]; tapping any glyph or
 * inline-emoji stub inside a group triggers dispersion for the whole group at once.
 */
@Composable
fun rememberRenderableText(formatted: FormattedText): RenderableText {
    val accent = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val mute = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val uriHandler = LocalUriHandler.current
    val confirmMaskedLink = LocalLinkConfirm.current
    val hashtagTap = LocalHashtagTap.current
    val customEmoji = LocalCustomEmoji.current

    // Pre-compute spoiler grouping (source-coordinate). Stable per formatted.
    val spoilerSrc = remember(formatted) { computeSpoilerSrcGrouping(formatted) }

    // Content-stable identity used as the remember key for ALL spoiler-reveal state.
    // We cannot key on `formatted` itself: reactions / edits / translations / scroll-
    // and-return cause the parent to hand us a new FormattedText instance whose data
    // class equality is fragile (span re-ordering by the mapper, fresh inline-emoji
    // entities arriving asynchronously, etc.). A stable hash over (text, spoiler
    // positions) survives every update that doesn't actually change *what* is hidden.
    // If the post text is genuinely edited, this hash flips and the covers come back
    // — which is correct, because the old reveal index may no longer point at the
    // same hidden phrase.
    val spoilerStateKey = remember(formatted) { spoilerContentKey(formatted) }

    val revealedGroups = remember(spoilerStateKey) { mutableStateOf(emptySet<Int>()) }
    val dispersing = remember(spoilerStateKey) {
        mutableStateMapOf<Int, Animatable<Float, AnimationVector1D>>()
    }
    val scope = rememberCoroutineScope()
    // Reduced motion (system "Remove animations"): snap the cover straight to the
    // revealed state with no dispersal sweep instead of the multi-hundred-ms reveal.
    val reducedMotion = rememberReducedMotion()

    val revealGroup: (Int) -> Unit = remember(spoilerStateKey, reducedMotion) {
        { groupId ->
            if (groupId !in revealedGroups.value) {
                revealedGroups.value = revealedGroups.value + groupId
                val anim = Animatable(0f)
                dispersing[groupId] = anim
                scope.launch {
                    if (reducedMotion) {
                        anim.snapTo(1f)
                    } else {
                        anim.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = SPOILER_REVEAL_MS,
                                easing = SpoilerEaseInQuad,
                            ),
                        )
                    }
                    dispersing.remove(groupId)
                }
            }
        }
    }

    val revealAtSrcPos: (Int) -> Unit = remember(spoilerStateKey, spoilerSrc) {
        { srcPos ->
            spoilerSrc.groupAtSrcPos(srcPos)?.let { revealGroup(it) }
        }
    }

    val customEmojiIds = remember(formatted) {
        formatted.spans.asSequence()
            .mapNotNull { (it.style as? FormattedText.Style.CustomEmoji)?.emojiId }
            .filter { it != 0L }
            .toSet()
    }

    LaunchedEffect(customEmojiIds) {
        if (customEmojiIds.isNotEmpty()) customEmoji.request(customEmojiIds)
    }

    val userOpener = dev.lyo.hortay.ui.users.LocalUserProfileOpener.current
    val userMentionTap = remember(userOpener) { { uid: Long -> userOpener.open(uid) } }
    val built = remember(
        formatted, spoilerSrc, accent, codeBg, mute, onSurface,
        revealedGroups.value, confirmMaskedLink, hashtagTap, userMentionTap,
    ) {
        buildFromFormatted(
            formatted = formatted,
            spoilerSrc = spoilerSrc,
            accent = accent,
            codeBg = codeBg,
            mute = mute,
            onSurface = onSurface,
            uriHandler = uriHandler,
            confirmMaskedLink = confirmMaskedLink,
            hashtagTap = hashtagTap,
            userMentionTap = userMentionTap,
            revealedGroups = revealedGroups.value,
            revealAtSrcPos = revealAtSrcPos,
        )
    }

    val spoilerDispersion: (Int) -> Float? = remember(spoilerStateKey) {
        { groupId ->
            val anim = dispersing[groupId]
            when {
                anim != null -> anim.value           // scattering
                groupId in revealedGroups.value -> null   // fully revealed
                else -> 0f                           // covered, idle shimmer
            }
        }
    }

    val inlineContent = remember(customEmojiIds, onSurface, built.emojiCoverSrcPositions, revealAtSrcPos) {
        if (customEmojiIds.isEmpty()) {
            emptyMap()
        } else {
            buildMap {
                customEmojiIds.forEach { id ->
                    put(
                        customEmojiTag(id),
                        InlineTextContent(
                            placeholder = Placeholder(
                                width = 1.2.em,
                                height = 1.2.em,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                            ),
                        ) { _ ->
                            CustomEmojiInlineView(
                                customEmojiId = id,
                                modifier = Modifier.fillMaxSize(),
                                tintColor = onSurface,
                                contentDescription = null,
                            )
                        },
                    )
                }
                built.emojiCoverSrcPositions.forEach { srcPos ->
                    put(
                        coveredEmojiTag(srcPos),
                        InlineTextContent(
                            placeholder = Placeholder(
                                width = 1.2.em,
                                height = 1.2.em,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                            ),
                        ) { _ ->
                            val interaction = remember { MutableInteractionSource() }
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .clickable(
                                        interactionSource = interaction,
                                        indication = null,
                                        onClick = { revealAtSrcPos(srcPos) },
                                    ),
                            )
                        },
                    )
                }
            }
        }
    }

    return RenderableText(
        text = built.text,
        inlineContent = inlineContent,
        linkRanges = built.linkRanges,
        spoilerGroups = built.spoilerGroups,
        spoilerDispersion = spoilerDispersion,
        blockDecorations = built.blockDecorations,
        pressableRanges = built.pressableRanges,
        contentKey = formatted.text,
    )
}

private const val SPOILER_REVEAL_MS = 1100
private val SpoilerEaseInQuad: Easing = Easing { t -> t * t }
private const val SPOILER_ADJACENCY_GAP = 1

/**
 * Content-stable hash for keying spoiler reveal state. Considers only the things that
 * actually determine *what* a spoiler covers: the message text and the (start, end)
 * positions of every Spoiler entity. Sensitive to:
 *  * Text edit by the author — reveal state should reset (the old reveal could now
 *    point at unrelated content).
 *  * Spoiler entity moved / added / removed by the author — reset, same reasoning.
 * Insensitive to:
 *  * Reactions, view counts, edit-flag, translation arriving — no change to hash.
 *  * Span list re-ordering — Spoiler positions are folded in sorted order.
 *  * New CustomEmoji entities — they're not in the hash; the corresponding spoiler
 *    grouping derives them from positions anyway.
 */
private fun spoilerContentKey(formatted: FormattedText): Long {
    var h = formatted.text.hashCode().toLong()
    val spoilerSpans = formatted.spans
        .asSequence()
        .filter { it.style == FormattedText.Style.Spoiler }
        .map { it.start to it.end }
        .sortedWith(compareBy({ it.first }, { it.second }))
    for ((start, end) in spoilerSpans) {
        h = h * 1_000_003L + start
        h = h * 1_000_003L + end
    }
    return h
}

private fun customEmojiTag(id: Long): String = "ce-$id"
private fun coveredEmojiTag(srcPos: Int): String = "spoiler-emoji-cover@$srcPos"

/** Result of transitive-adjacency grouping of TDLib Spoiler entities in source space. */
private class SpoilerSrcGrouping(
    /** For each TDLib spoiler-span index, the groupId it belongs to. */
    private val spanIdxToGroup: Map<Int, Int>,
    /** Source-position ranges per group, used for hit-testing taps. */
    private val groupSrcRanges: Map<Int, List<IntRange>>,
) {
    fun groupOf(spanIdx: Int): Int? = spanIdxToGroup[spanIdx]
    fun groupAtSrcPos(srcPos: Int): Int? {
        for ((groupId, ranges) in groupSrcRanges) {
            if (ranges.any { srcPos in it }) return groupId
        }
        return null
    }
    val groupIds: Set<Int> get() = groupSrcRanges.keys
}

private fun computeSpoilerSrcGrouping(formatted: FormattedText): SpoilerSrcGrouping {
    val srcLen = formatted.text.length
    data class Entry(val spanIdx: Int, val start: Int, val end: Int)
    val entries = formatted.spans.withIndex()
        .filter { (_, sp) -> sp.style == FormattedText.Style.Spoiler }
        .map { (idx, sp) ->
            val s = sp.start.coerceIn(0, srcLen)
            val e = sp.end.coerceIn(s, srcLen)
            Entry(idx, s, e)
        }
        .filter { it.start < it.end }
        .sortedBy { it.start }

    if (entries.isEmpty()) {
        return SpoilerSrcGrouping(emptyMap(), emptyMap())
    }

    // Linear sweep: a new group starts when the gap from the previous entry's end is
    // greater than SPOILER_ADJACENCY_GAP source characters. The +1 tolerance covers the
    // common case where a CustomEmoji codepoint splits one logical spoiler into two
    // TDLib entities; longer gaps mean the spoilers are visually distinct.
    val spanIdxToGroup = HashMap<Int, Int>()
    val groupRanges = HashMap<Int, MutableList<IntRange>>()
    var currentGroup = 0
    var currentEnd = entries[0].end
    spanIdxToGroup[entries[0].spanIdx] = 0
    groupRanges.getOrPut(0) { mutableListOf() } += (entries[0].start until entries[0].end)
    for (i in 1 until entries.size) {
        val e = entries[i]
        if (e.start - currentEnd > SPOILER_ADJACENCY_GAP) {
            currentGroup++
        }
        spanIdxToGroup[e.spanIdx] = currentGroup
        groupRanges.getOrPut(currentGroup) { mutableListOf() } += (e.start until e.end)
        currentEnd = maxOf(currentEnd, e.end)
    }
    return SpoilerSrcGrouping(spanIdxToGroup, groupRanges)
}

private data class BuiltAnnotated(
    val text: AnnotatedString,
    val linkRanges: List<LinkRange>,
    val spoilerGroups: List<SpoilerGroupInfo>,
    val emojiCoverSrcPositions: Set<Int>,
    val blockDecorations: List<BlockDecoration>,
    val pressableRanges: List<IntRange>,
)

// Left text indent (in sp so it tracks font scale) reserving room for the quote bar /
// the code box padding. Mirrors the dp geometry drawn in [LinkAwareText]; they're
// calibrated to line up at the default font scale.
private val QUOTE_TEXT_INDENT = 18.sp
private val CODE_TEXT_INDENT = 14.sp

private data class CustomEmojiRange(val start: Int, val end: Int, val emojiId: Long)

private fun buildFromFormatted(
    formatted: FormattedText,
    spoilerSrc: SpoilerSrcGrouping,
    accent: Color,
    codeBg: Color,
    mute: Color,
    onSurface: Color,
    uriHandler: UriHandler,
    confirmMaskedLink: (String) -> Unit,
    hashtagTap: (String) -> Unit,
    /** Open the user-profile sheet for a `TextEntityTypeMentionName` (non-@username
     *  inline mention with a resolved TDLib user id). `@handle` mentions still route
     *  through the URL pipeline so the link resolver can fall back to t.me/<handle>. */
    userMentionTap: (Long) -> Unit,
    revealedGroups: Set<Int>,
    revealAtSrcPos: (Int) -> Unit,
): BuiltAnnotated {
    val linkRanges = mutableListOf<LinkRange>()
    // Every tappable entity's dst range (URL / textUrl / @mention / inline mention /
    // hashtag) — drives the padded pressed-highlight in [LinkAwareText]. Superset of
    // [linkRanges], which is URL-only because the long-press sheet only fits URLs.
    val pressableRanges = mutableListOf<IntRange>()
    val emojiCoverSrcPositions = mutableSetOf<Int>()
    val groupDstRanges = HashMap<Int, MutableList<IntRange>>()
    val blockDecorations = mutableListOf<BlockDecoration>()
    val annotated = buildAnnotatedString {
    val srcText = formatted.text
    val srcLen = srcText.length

    val customEmojiRanges = formatted.spans
        .mapNotNull { sp ->
            (sp.style as? FormattedText.Style.CustomEmoji)?.let { ce ->
                CustomEmojiRange(
                    start = sp.start.coerceIn(0, srcLen),
                    end = sp.end.coerceIn(0, srcLen),
                    emojiId = ce.emojiId,
                )
            }
        }
        .filter { it.start < it.end }
        .sortedBy { it.start }

    fun emojiUnderCoveredGroup(srcPos: Int): Boolean {
        val groupId = spoilerSrc.groupAtSrcPos(srcPos) ?: return false
        return groupId !in revealedGroups
    }

    fun emojiUnderAnyGroup(srcPos: Int): Boolean =
        spoilerSrc.groupAtSrcPos(srcPos) != null

    val positionMap = IntArray(srcLen + 1)
    var dst = 0
    var src = 0
    var rangeIdx = 0
    while (src < srcLen) {
        if (rangeIdx < customEmojiRanges.size && src == customEmojiRanges[rangeIdx].start) {
            val r = customEmojiRanges[rangeIdx]
            for (i in r.start until r.end) positionMap[i] = dst
            if (emojiUnderAnyGroup(r.start)) emojiCoverSrcPositions += r.start
            val tag = if (emojiUnderCoveredGroup(r.start)) coveredEmojiTag(r.start)
                else customEmojiTag(r.emojiId)
            appendInlineContent(tag, "￼")
            dst += 1
            src = r.end
            rangeIdx++
        } else {
            positionMap[src] = dst
            append(srcText[src])
            src++; dst++
        }
    }
    positionMap[srcLen] = dst

    val linkStyle = TextLinkStyles(SpanStyle(color = accent))
    val mentionStyle = TextLinkStyles(SpanStyle(color = accent))
    val safeOpen = LinkInteractionListener { link ->
        if (link is LinkAnnotation.Url) runCatching { uriHandler.openUri(link.url) }
    }
    val maskedOpen = LinkInteractionListener { link ->
        if (link !is LinkAnnotation.Url) return@LinkInteractionListener
        if (confirmMaskedLink === Unhandled) {
            runCatching { uriHandler.openUri(link.url) }
        } else {
            confirmMaskedLink(link.url)
        }
    }

    // Block-level entities (quote / code) become ParagraphStyles, which CANNOT overlap
    // each other — Compose throws at layout time (NOT compile time). Telegram allows
    // nesting (code inside a quote) and can emit duplicate/adjacent block entities, so
    // greedily pick a non-overlapping set (outer-first): only those get a ParagraphStyle
    // indent + a painted box. Nested / overlapping blocks degrade to inline styling.
    val acceptedBlockIdx = HashSet<Int>()
    run {
        val blocks = formatted.spans.withIndex()
            .mapNotNull { (i, sp) ->
                val isBlock = sp.style is FormattedText.Style.BlockQuote ||
                    sp.style is FormattedText.Style.Pre
                if (!isBlock) return@mapNotNull null
                val st = sp.start.coerceIn(0, srcLen)
                val en = sp.end.coerceIn(st, srcLen)
                if (st >= en) null else Triple(i, st, en)
            }
            .sortedWith(compareBy({ it.second }, { -(it.third - it.second) }))
        var lastEnd = -1
        for ((i, st, en) in blocks) {
            if (st >= lastEnd) {
                acceptedBlockIdx += i
                lastEnd = en
            }
        }
    }

    formatted.spans.forEachIndexed { idx, span ->
        if (span.style is FormattedText.Style.CustomEmoji) return@forEachIndexed

        var srcStart = span.start.coerceIn(0, srcLen)
        var srcEnd = span.end.coerceIn(srcStart, srcLen)
        // Trim whitespace off the entity's edges so its styling — inline-code background,
        // underline / strikethrough, spoiler cover, link tap target + press highlight,
        // and the block box — covers only the visible text, not leading / trailing blanks.
        while (srcStart < srcEnd && srcText[srcStart].isWhitespace()) srcStart++
        while (srcEnd > srcStart && srcText[srcEnd - 1].isWhitespace()) srcEnd--
        val start = positionMap[srcStart]
        val end = positionMap[srcEnd]
        if (end == start) return@forEachIndexed
        when (val s = span.style) {
            is FormattedText.Style.TextUrl -> {
                addLink(LinkAnnotation.Url(s.url, linkStyle, maskedOpen), start, end)
                linkRanges += LinkRange(start, end, s.url)
                pressableRanges += (start until end)
            }
            FormattedText.Style.Url -> {
                val url = normalizeUrl(srcText.substring(srcStart, srcEnd))
                addLink(LinkAnnotation.Url(url, linkStyle, safeOpen), start, end)
                linkRanges += LinkRange(start, end, url)
                pressableRanges += (start until end)
            }
            FormattedText.Style.Mention -> {
                val handle = srcText.substring(srcStart, srcEnd).trimStart('@')
                if (handle.isNotEmpty()) {
                    val url = "https://t.me/$handle"
                    addLink(LinkAnnotation.Url(url, mentionStyle, safeOpen), start, end)
                    linkRanges += LinkRange(start, end, url)
                    pressableRanges += (start until end)
                }
            }
            is FormattedText.Style.MentionName -> {
                // Inline mention with a resolved TDLib user id (TDLib's
                // TextEntityTypeMentionName — what Telegram clients render as a
                // tappable blue name without a leading `@`). Routes straight to
                // the user-profile sheet because the @handle form doesn't apply.
                addLink(
                    LinkAnnotation.Clickable(
                        tag = "mention-name-$idx",
                        styles = mentionStyle,
                        linkInteractionListener = LinkInteractionListener { userMentionTap(s.userId) },
                    ),
                    start,
                    end,
                )
                pressableRanges += (start until end)
            }
            FormattedText.Style.Hashtag -> {
                val tag = srcText.substring(srcStart, srcEnd)
                addLink(
                    LinkAnnotation.Clickable(
                        tag = "hashtag-$idx",
                        styles = mentionStyle,
                        linkInteractionListener = LinkInteractionListener { hashtagTap(tag) },
                    ),
                    start,
                    end,
                )
                pressableRanges += (start until end)
            }
            FormattedText.Style.Spoiler -> {
                val groupId = spoilerSrc.groupOf(idx) ?: return@forEachIndexed
                groupDstRanges.getOrPut(groupId) { mutableListOf() } += (start until end)
                if (groupId in revealedGroups) {
                    addStyle(SpanStyle(color = onSurface), start, end)
                } else {
                    val cover = SpanStyle(color = Color.Transparent)
                    addLink(
                        LinkAnnotation.Clickable(
                            tag = "spoiler-$idx",
                            styles = TextLinkStyles(cover),
                            linkInteractionListener = LinkInteractionListener {
                                revealAtSrcPos(srcStart)
                            },
                        ),
                        start,
                        end,
                    )
                }
            }
            is FormattedText.Style.BlockQuote -> if (idx in acceptedBlockIdx) {
                // Paragraph-level: indent the text to clear the accent bar, keep the
                // body at full readability (the tint already signals "quote"), and
                // record the box for [LinkAwareText] to paint behind it.
                addStyle(
                    ParagraphStyle(textIndent = TextIndent(QUOTE_TEXT_INDENT, QUOTE_TEXT_INDENT)),
                    start,
                    end,
                )
                addStyle(SpanStyle(color = onSurface), start, end)
                blockDecorations += BlockDecoration(start, end, BlockDecoration.Kind.Quote)
            } else {
                // Nested / overlapping quote — degrade to a muted inline span (the
                // enclosing block already owns the box).
                addStyle(SpanStyle(color = mute), start, end)
            }
            is FormattedText.Style.Pre -> if (idx in acceptedBlockIdx) {
                // Block code: monospace + indent, full-width box painted behind. NOT the
                // inline `background` span [Code] uses — the box provides the surface, and
                // a per-glyph background would double up. The language label (PreCode) is
                // drawn in the box corner by [LinkAwareText].
                addStyle(
                    ParagraphStyle(textIndent = TextIndent(CODE_TEXT_INDENT, CODE_TEXT_INDENT)),
                    start,
                    end,
                )
                addStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = onSurface), start, end)
                blockDecorations += BlockDecoration(
                    start,
                    end,
                    BlockDecoration.Kind.Code,
                    language = s.language,
                )
            } else {
                // Nested / overlapping code (e.g. code inside a quote) — inline monospace
                // + per-glyph background, no second box, no ParagraphStyle.
                addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg), start, end)
            }
            else -> span.style.toSpanStyle(accent, codeBg, mute)
                ?.let { style -> addStyle(style, start, end) }
        }
    }
    }
    val spoilerGroups = groupDstRanges
        .toSortedMap()
        .map { (groupId, ranges) ->
            // Seed combines groupId + first range start so two distinct groups in the
            // same message look like distinct clouds (different particle layouts), but
            // a single group has one consistent pattern across all its sub-ranges.
            val seed = groupId * 31 + (ranges.firstOrNull()?.first ?: 0)
            SpoilerGroupInfo(groupId = groupId, seed = seed, ranges = ranges.toList())
        }
    return BuiltAnnotated(
        text = annotated,
        linkRanges = linkRanges.toList(),
        spoilerGroups = spoilerGroups,
        emojiCoverSrcPositions = emojiCoverSrcPositions.toSet(),
        blockDecorations = blockDecorations.toList(),
        pressableRanges = pressableRanges.toList(),
    )
}

private fun FormattedText.Style.toSpanStyle(
    accent: Color,
    codeBg: Color,
    mute: Color,
): SpanStyle? = when (this) {
    FormattedText.Style.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
    FormattedText.Style.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
    FormattedText.Style.Underline -> SpanStyle(textDecoration = TextDecoration.Underline)
    FormattedText.Style.Strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    FormattedText.Style.Code -> SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = codeBg,
    )
    is FormattedText.Style.MentionName -> SpanStyle(color = accent)
    FormattedText.Style.BotCommand -> SpanStyle(color = accent)
    is FormattedText.Style.CustomEmoji -> null
    // Block-level — handled explicitly in buildFromFormatted (ParagraphStyle indent +
    // a box painted behind the text by LinkAwareText), never reaches this inline path.
    is FormattedText.Style.Pre,
    is FormattedText.Style.BlockQuote -> null
    FormattedText.Style.Url,
    is FormattedText.Style.TextUrl,
    FormattedText.Style.Mention,
    FormattedText.Style.Hashtag,
    FormattedText.Style.Spoiler -> null
}

private fun normalizeUrl(raw: String): String {
    if (raw.contains("://")) return raw
    if (raw.startsWith("mailto:") || raw.startsWith("tel:")) return raw
    return "https://$raw"
}
