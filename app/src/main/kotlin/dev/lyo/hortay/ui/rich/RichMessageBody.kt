package dev.lyo.hortay.ui.rich

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.LayoutDirection
import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.data.rich.RichDocument
import dev.lyo.hortay.data.rich.RichInline
import dev.lyo.hortay.data.rich.RichPlainText
import dev.lyo.hortay.ui.text.LocalLinkConfirm
import dev.lyo.hortay.ui.text.Unhandled
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Entry point for rendering a [RichDocument] — the inline-text tree, text blocks, and the
 * media-bearing blocks (photo / video / animation / audio / voice / collage / slideshow /
 * table / map) of a Telegram rich message. A media block with an unresolvable file falls
 * back to [RichMediaPlaceholder].
 *
 * Blocks stack in a plain [androidx.compose.foundation.layout.Column] (NEVER a nested
 * LazyColumn — the post card is one item of the outer feed list). An RTL document flips
 * [LocalLayoutDirection] for the whole body; code and math content stays LTR internally
 * (handled inside the block composables).
 *
 * [mode] selects the whole document ([RichMessageMode.Reading] — detail / comments-anchor
 * surfaces) or a bounded feed excerpt ([RichMessageMode.FeedPreview] — see
 * [RichDocument.previewProjection]); a preview projects the block list to a short prefix BEFORE
 * composition so tables / details / slideshows / media past the fold never enter composition.
 *
 * In-document navigation ([RichInline.AnchorLink] / [RichInline.ReferenceLink] taps) is
 * self-contained and only wired in [RichMessageMode.Reading] (the FULL document renders, so a
 * scroll target always exists): a [BringIntoViewRequester] is registered on every top-level block
 * that hosts an anchor / reference name, and a tap scrolls the nearest scroll parent to it —
 * auto-opening any collapsed [RichBlock.Details] on the way (see [RichAnchorController]) and
 * flashing a soft accent highlight on landing. In [RichMessageMode.FeedPreview] the body is a
 * clamped excerpt with no scroll target, so a tap on an in-document link falls back to opening
 * its external URL — routed through [LocalLinkConfirm] exactly like a masked [RichInline.Url], so
 * an internal-looking footnote can't silently open an external phishing URL.
 */
@Composable
fun RichMessageBody(
    document: RichDocument,
    modifier: Modifier = Modifier,
    mode: RichMessageMode = RichMessageMode.Reading,
    // FeedPreview only: the pre-computed bounded prefix. [RichFeedPreview] already projects the
    // document to decide its "read full" affordance, so it threads the result in here and the body
    // never projects a second time. Null (a standalone FeedPreview / test) projects here; ignored
    // in Reading, which always renders every block.
    projectedBlocks: ImmutableList<RichBlock>? = null,
) {
    val blocks = remember(document, mode, projectedBlocks) {
        when (mode) {
            RichMessageMode.Reading -> document.blocks
            RichMessageMode.FeedPreview -> projectedBlocks ?: document.previewProjection()
        }
    }
    // The two modes diverge on machinery, not just layout: Reading builds in-document navigation
    // (registry / requesters / controller / details-expansion / footnote sheet); FeedPreview builds
    // none of it. Split into two composables — not a conditional `remember` in one — so the
    // always-mounted feed never allocates the Reading state (finding: FeedPreview was paying for a
    // controller + scope it can't use), and so a hypothetical mode flip cleanly rebuilds.
    when (mode) {
        RichMessageMode.Reading -> ReadingRichBody(document, blocks, modifier)
        RichMessageMode.FeedPreview -> PreviewRichBody(document, blocks, modifier)
    }
}

/**
 * Reading-surface body: builds the in-document navigation machinery — the anchor registry, one
 * [BringIntoViewRequester] per target block, the [RichAnchorController], the shared
 * [RichDetailsExpansion], and the footnote sheet — and renders the FULL document. All of it is
 * allocated ONLY here; a [PreviewRichBody] on the always-mounted feed carries none of it.
 */
@Composable
private fun ReadingRichBody(document: RichDocument, blocks: List<RichBlock>, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    val confirmMaskedLink = LocalLinkConfirm.current
    val haptics = LocalHapticFeedback.current

    // name → top-level block target, so an AnchorLink / ReferenceLink can resolve in-document.
    val registry = remember(blocks) { buildAnchorRegistry(blocks) }

    // Live expansion controller for the whole document — auto-opens a COLLAPSED details section an
    // in-document anchor lands inside. Dormant until [RichAnchorController.navigate] requests a path.
    val detailsExpansion = remember { RichDetailsExpansion() }

    // One requester per top-level block that is an anchor target.
    val targetIndices = remember(registry) { registry.values.map { it.blockIndex }.toSet() }
    val requesters = remember(targetIndices) { targetIndices.associateWith { BringIntoViewRequester() } }
    val scope = rememberCoroutineScope()
    val controller = remember(requesters, detailsExpansion, scope) {
        RichAnchorController(requesters, detailsExpansion, scope)
    }

    // A footnote / reference marker whose text is resolvable in-document opens this sheet; an
    // anchor marker scrolls; an external-only marker falls back to the masked-link confirmation.
    val referenceSheet = remember { mutableStateOf<RichReferenceSheetData?>(null) }
    val anchorTap = remember(blocks, registry, controller, uriHandler, confirmMaskedLink, haptics) {
        { kind: RichLinkKind, name: String, url: String ->
            // Same anti-phishing path as a masked RichInline.Url: the confirm hook resolves the
            // destination and surfaces the sheet only for genuinely external targets; the sentinel
            // default (standalone previews / tests) opens directly.
            fun open(target: String) {
                if (confirmMaskedLink === Unhandled) runCatching { uriHandler.openUri(target) } else confirmMaskedLink(target)
            }
            when (kind) {
                RichLinkKind.Reference -> {
                    val excerpt = findReferenceExcerpt(blocks, name)
                    val target = registry[normalizeAnchor(name)]
                    when {
                        excerpt != null && target != null -> {
                            // Subtle tick as the footnote sheet rises — same idiom as poll votes.
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            referenceSheet.value = RichReferenceSheetData(excerpt, target)
                        }
                        url.isNotBlank() -> open(url)
                        else -> Unit
                    }
                }
                RichLinkKind.Anchor -> when (val action = resolveAnchorTap(name, url, registry, canScroll = true)) {
                    is AnchorTapAction.Scroll -> {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        controller.navigate(action.target)
                    }
                    is AnchorTapAction.OpenUrl -> open(action.url)
                    AnchorTapAction.None -> Unit
                }
            }
            Unit
        }
    }

    RichBodyScaffold(
        document = document,
        blocks = blocks,
        modifier = modifier,
        reading = true,
        anchorTap = anchorTap,
        detailsExpansion = detailsExpansion,
        controller = controller,
    )

    referenceSheet.value?.let { data ->
        RichReferenceSheet(
            data = data,
            onGoToReference = { controller.navigate(it) },
            onDismiss = { referenceSheet.value = null },
        )
    }
}

/**
 * Feed-preview body: a bounded prefix with NO scroll parent, so an [RichInline.AnchorLink] /
 * [RichInline.ReferenceLink] tap can only fall back to opening its external URL — routed through
 * [LocalLinkConfirm] exactly like a masked [RichInline.Url], so an internal-looking footnote can't
 * silently open an external phishing URL. Builds none of the Reading navigation machinery.
 */
@Composable
private fun PreviewRichBody(document: RichDocument, blocks: List<RichBlock>, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    val confirmMaskedLink = LocalLinkConfirm.current
    val anchorTap = remember(uriHandler, confirmMaskedLink) {
        { _: RichLinkKind, _: String, url: String ->
            if (url.isNotBlank()) {
                if (confirmMaskedLink === Unhandled) runCatching { uriHandler.openUri(url) } else confirmMaskedLink(url)
            }
            Unit
        }
    }
    RichBodyScaffold(
        document = document,
        blocks = blocks,
        modifier = modifier,
        reading = false,
        anchorTap = anchorTap,
        detailsExpansion = null,
        controller = null,
    )
}

/**
 * Shared body for both modes: provides the rich CompositionLocals, hosts the fullscreen table
 * viewer a compact feed-preview table escalates to (a Dialog, so one host per message escapes to
 * the window regardless of feed position), applies the RTL direction flip for an RTL document, and
 * stacks the blocks. [detailsExpansion] / [controller] are null off the reading surface.
 */
@Composable
private fun RichBodyScaffold(
    document: RichDocument,
    blocks: List<RichBlock>,
    modifier: Modifier,
    reading: Boolean,
    anchorTap: (RichLinkKind, String, String) -> Unit,
    detailsExpansion: RichDetailsExpansion?,
    controller: RichAnchorController?,
) {
    CompositionLocalProvider(
        LocalRichAnchorTap provides anchorTap,
        LocalRichReading provides reading,
        LocalRichDetailsExpansion provides detailsExpansion,
        LocalRichAnchorController provides controller,
    ) {
        RichTableViewerHost(
            layoutDirection = if (document.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        ) {
            val body: @Composable () -> Unit = {
                RichBlocks(blocks, path = "b", modifier = modifier, readingColumn = reading)
            }
            if (document.isRtl) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { body() }
            } else {
                body()
            }
        }
    }
}

/**
 * True while composing a [RichMessageMode.Reading] document — the editorial reading surface
 * (post-detail / comments anchor). Media captions read this to drop their feed-preview line clamp
 * and render in full; the default `false` keeps the feed-preview clamp everywhere else.
 */
internal val LocalRichReading = staticCompositionLocalOf { false }

/** Accent-highlight dwell after an in-document anchor jump lands, in milliseconds. */
private const val ANCHOR_HIGHLIGHT_MS = 700L

/**
 * Where an in-document anchor / reference name lives: the [blockIndex] of the TOP-LEVEL block that
 * hosts it (the scroll target — a nested anchor resolves to its top-level container, see the
 * registry KDoc) and [ancestorDetailPaths], the document paths of every collapsible
 * [RichBlock.Details] enclosing it, outermost first, that must be expanded for it to be on screen.
 */
@Stable
internal data class AnchorTarget(val blockIndex: Int, val ancestorDetailPaths: List<String>)

/**
 * Drives in-document anchor navigation for one [RichMessageMode.Reading] document. Holds the
 * per-target-block [BringIntoViewRequester]s, the shared [RichDetailsExpansion], and the transient
 * "which top-level block is flashing" state read by [RichBlocks].
 *
 * [navigate] expands every ancestor details, lets that composition frame settle, scrolls the
 * nearest scroll parent to the target block, then flashes the accent highlight. Provided to the
 * tree via [LocalRichAnchorController] (null off the reading surface).
 */
@Stable
internal class RichAnchorController(
    private val requesters: Map<Int, BringIntoViewRequester>,
    private val expansion: RichDetailsExpansion,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    /** Top-level block index currently flashing its landing highlight, or null. */
    var highlightedIndex by mutableStateOf<Int?>(null)
        private set

    private var highlightJob: Job? = null

    /** The requester attached to the top-level block at [index], or null if it hosts no target. */
    fun requesterFor(index: Int): BringIntoViewRequester? = requesters[index]

    fun navigate(target: AnchorTarget) {
        scope.launch {
            if (target.ancestorDetailPaths.isNotEmpty()) {
                target.ancestorDetailPaths.forEach { expansion.requestExpand(it) }
                // Let the requested-open state apply + the details begin expanding before we measure
                // the block's bounds, so bringIntoView aims at the opened section, not its collapsed row.
                withFrameNanos { }
            }
            requesters[target.blockIndex]?.bringIntoView()
            flash(target.blockIndex)
        }
    }

    private fun flash(index: Int) {
        highlightJob?.cancel()
        highlightJob = scope.launch {
            highlightedIndex = index
            delay(ANCHOR_HIGHLIGHT_MS)
            highlightedIndex = null
        }
    }
}

/** Null off the reading surface; set to the document's [RichAnchorController] in Reading mode. */
internal val LocalRichAnchorController = staticCompositionLocalOf<RichAnchorController?> { null }

/**
 * Pure decision for an [RichInline.AnchorLink] / [RichInline.ReferenceLink] tap, factored out of
 * [RichMessageBody] so the "internal anchor vs external fallback" branch is unit-testable:
 *  - the name resolves in [registry] AND scrolling is available → [AnchorTapAction.Scroll] carrying
 *    the [AnchorTarget] (block index + the collapsed-details paths to open on the way);
 *  - otherwise a non-blank [url] → [AnchorTapAction.OpenUrl] (the caller routes it through the
 *    masked-link confirmation, never a direct open);
 *  - neither → [AnchorTapAction.None].
 */
internal sealed interface AnchorTapAction {
    data class Scroll(val target: AnchorTarget) : AnchorTapAction
    data class OpenUrl(val url: String) : AnchorTapAction
    data object None : AnchorTapAction
}

internal fun resolveAnchorTap(
    name: String,
    url: String,
    registry: Map<String, AnchorTarget>,
    canScroll: Boolean,
): AnchorTapAction {
    val target = registry[normalizeAnchor(name)]
    return when {
        target != null && canScroll -> AnchorTapAction.Scroll(target)
        url.isNotBlank() -> AnchorTapAction.OpenUrl(url)
        else -> AnchorTapAction.None
    }
}

internal fun normalizeAnchor(name: String): String = name.trim().lowercase()

/**
 * Maps every anchor / reference name reachable inside a top-level block to that block's
 * [AnchorTarget]. The scroll target is always the TOP-LEVEL block index (that's where the
 * [BringIntoViewRequester] can attach); an anchor nested inside a quote / list / details resolves
 * to its top-level container, and any [RichBlock.Details] enclosing it is recorded so navigation
 * can auto-open it. The block-path strings recorded here MUST match the `path` each
 * [RichBlock.Details] receives at render time (see [RichBlocks] / RichDetails path construction).
 */
private fun buildAnchorRegistry(blocks: List<RichBlock>): Map<String, AnchorTarget> = buildMap {
    blocks.forEachIndexed { topIndex, block ->
        registerBlockAnchors(block, path = "b.$topIndex", topIndex = topIndex, detailPaths = emptyList()) { name, target ->
            val key = normalizeAnchor(name)
            if (key.isNotEmpty()) putIfAbsent(key, target)
        }
    }
}

private fun registerBlockAnchors(
    block: RichBlock,
    path: String,
    topIndex: Int,
    detailPaths: List<String>,
    emit: (String, AnchorTarget) -> Unit,
) {
    val target = AnchorTarget(topIndex, detailPaths)
    fun inline(text: RichInline) = collectAnchorNames(text).forEach { emit(it, target) }
    when (block) {
        is RichBlock.Anchor -> emit(block.name, target)
        is RichBlock.SectionHeading -> inline(block.text)
        is RichBlock.Paragraph -> inline(block.text)
        is RichBlock.Footer -> inline(block.text)
        is RichBlock.Preformatted -> inline(block.text)
        is RichBlock.PullQuote -> {
            inline(block.text)
            block.credit?.let { inline(it) }
        }
        is RichBlock.BlockQuote -> {
            block.blocks.forEachIndexed { i, b -> registerBlockAnchors(b, "$path.q.$i", topIndex, detailPaths, emit) }
            block.credit?.let { inline(it) }
        }
        is RichBlock.ListBlock -> block.items.forEachIndexed { itemIndex, item ->
            item.blocks.forEachIndexed { i, b -> registerBlockAnchors(b, "$path.$itemIndex.$i", topIndex, detailPaths, emit) }
        }
        is RichBlock.Details -> {
            // The header row is always visible (even collapsed), so its anchors don't need this
            // details expanded; its body does.
            inline(block.header)
            val childDetailPaths = detailPaths + path
            block.blocks.forEachIndexed { i, b -> registerBlockAnchors(b, "$path.d.$i", topIndex, childDetailPaths, emit) }
        }
        else -> Unit // media / divider / math / unknown carry no anchor targets
    }
}

/** Every anchor / reference name reachable inside an inline tree (targets, not the link sources). */
internal fun collectAnchorNames(inline: RichInline): List<String> = buildList {
    when (inline) {
        is RichInline.Anchor -> add(inline.name)
        is RichInline.Reference -> {
            add(inline.name)
            addAll(collectAnchorNames(inline.child))
        }
        is RichInline.Sequence -> inline.parts.forEach { addAll(collectAnchorNames(it)) }
        is RichInline.Bold -> addAll(collectAnchorNames(inline.child))
        is RichInline.Italic -> addAll(collectAnchorNames(inline.child))
        is RichInline.Underline -> addAll(collectAnchorNames(inline.child))
        is RichInline.Strikethrough -> addAll(collectAnchorNames(inline.child))
        is RichInline.Spoiler -> addAll(collectAnchorNames(inline.child))
        is RichInline.Subscript -> addAll(collectAnchorNames(inline.child))
        is RichInline.Superscript -> addAll(collectAnchorNames(inline.child))
        is RichInline.Marked -> addAll(collectAnchorNames(inline.child))
        is RichInline.Fixed -> addAll(collectAnchorNames(inline.child))
        is RichInline.DateTime -> addAll(collectAnchorNames(inline.child))
        is RichInline.Mention -> addAll(collectAnchorNames(inline.child))
        is RichInline.MentionName -> addAll(collectAnchorNames(inline.child))
        is RichInline.Hashtag -> addAll(collectAnchorNames(inline.child))
        is RichInline.Cashtag -> addAll(collectAnchorNames(inline.child))
        is RichInline.BotCommand -> addAll(collectAnchorNames(inline.child))
        is RichInline.Url -> addAll(collectAnchorNames(inline.child))
        is RichInline.EmailAddress -> addAll(collectAnchorNames(inline.child))
        is RichInline.PhoneNumber -> addAll(collectAnchorNames(inline.child))
        is RichInline.BankCardNumber -> addAll(collectAnchorNames(inline.child))
        is RichInline.ReferenceLink -> addAll(collectAnchorNames(inline.child))
        is RichInline.AnchorLink -> addAll(collectAnchorNames(inline.child))
        is RichInline.Plain, is RichInline.CustomEmoji, is RichInline.Math, is RichInline.Unknown -> Unit
    }
}

/**
 * Plain-text excerpt of the in-document [RichInline.Reference] named [referenceName], or `null`
 * when no such target exists (an external-only reference — the caller keeps the confirm-and-open
 * fallback). This is what makes a footnote marker resolvable "in-document": TDLib carries the
 * footnote body as the `child` of a `richTextReference` node, so we find the first matching
 * Reference and project its child through [RichPlainText]. Text blocks, quotes, lists and details
 * bodies are searched; media captions and table cells (where references effectively never appear)
 * are not.
 */
internal fun findReferenceExcerpt(blocks: List<RichBlock>, referenceName: String): String? {
    val key = normalizeAnchor(referenceName)
    if (key.isEmpty()) return null
    return blocks.firstNotNullOfOrNull { findReferenceInBlock(it, key) }
}

private fun findReferenceInBlock(block: RichBlock, key: String): String? = when (block) {
    is RichBlock.SectionHeading -> findReferenceInInline(block.text, key)
    is RichBlock.Paragraph -> findReferenceInInline(block.text, key)
    is RichBlock.Footer -> findReferenceInInline(block.text, key)
    is RichBlock.Preformatted -> findReferenceInInline(block.text, key)
    is RichBlock.PullQuote ->
        findReferenceInInline(block.text, key) ?: block.credit?.let { findReferenceInInline(it, key) }
    is RichBlock.BlockQuote ->
        block.blocks.firstNotNullOfOrNull { findReferenceInBlock(it, key) }
            ?: block.credit?.let { findReferenceInInline(it, key) }
    is RichBlock.ListBlock ->
        block.items.firstNotNullOfOrNull { item -> item.blocks.firstNotNullOfOrNull { findReferenceInBlock(it, key) } }
    is RichBlock.Details ->
        findReferenceInInline(block.header, key) ?: block.blocks.firstNotNullOfOrNull { findReferenceInBlock(it, key) }
    else -> null
}

private fun findReferenceInInline(inline: RichInline, key: String): String? {
    if (inline is RichInline.Reference && normalizeAnchor(inline.name) == key) {
        return RichPlainText.of(inline.child).trim().ifEmpty { null }
    }
    return when (inline) {
        is RichInline.Sequence -> inline.parts.firstNotNullOfOrNull { findReferenceInInline(it, key) }
        is RichInline.Reference -> findReferenceInInline(inline.child, key)
        is RichInline.Bold -> findReferenceInInline(inline.child, key)
        is RichInline.Italic -> findReferenceInInline(inline.child, key)
        is RichInline.Underline -> findReferenceInInline(inline.child, key)
        is RichInline.Strikethrough -> findReferenceInInline(inline.child, key)
        is RichInline.Spoiler -> findReferenceInInline(inline.child, key)
        is RichInline.Subscript -> findReferenceInInline(inline.child, key)
        is RichInline.Superscript -> findReferenceInInline(inline.child, key)
        is RichInline.Marked -> findReferenceInInline(inline.child, key)
        is RichInline.Fixed -> findReferenceInInline(inline.child, key)
        is RichInline.DateTime -> findReferenceInInline(inline.child, key)
        is RichInline.Mention -> findReferenceInInline(inline.child, key)
        is RichInline.MentionName -> findReferenceInInline(inline.child, key)
        is RichInline.Hashtag -> findReferenceInInline(inline.child, key)
        is RichInline.Cashtag -> findReferenceInInline(inline.child, key)
        is RichInline.BotCommand -> findReferenceInInline(inline.child, key)
        is RichInline.Url -> findReferenceInInline(inline.child, key)
        is RichInline.EmailAddress -> findReferenceInInline(inline.child, key)
        is RichInline.PhoneNumber -> findReferenceInInline(inline.child, key)
        is RichInline.BankCardNumber -> findReferenceInInline(inline.child, key)
        is RichInline.ReferenceLink -> findReferenceInInline(inline.child, key)
        is RichInline.AnchorLink -> findReferenceInInline(inline.child, key)
        is RichInline.Plain, is RichInline.CustomEmoji, is RichInline.Math,
        is RichInline.Anchor, is RichInline.Unknown -> null
    }
}
