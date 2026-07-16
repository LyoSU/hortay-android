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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.LayoutDirection
import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.data.rich.RichDocument
import dev.lyo.hortay.data.rich.RichInline
import dev.lyo.hortay.ui.text.LocalLinkConfirm
import dev.lyo.hortay.ui.text.Unhandled
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
) {
    val uriHandler = LocalUriHandler.current
    val confirmMaskedLink = LocalLinkConfirm.current
    // FeedPreview projects to a bounded prefix before composition; Reading renders every block.
    val blocks = remember(document, mode) {
        when (mode) {
            RichMessageMode.Reading -> document.blocks
            RichMessageMode.FeedPreview -> document.previewProjection()
        }
    }
    val reading = mode == RichMessageMode.Reading

    // name → top-level block target, so an AnchorLink / ReferenceLink can resolve in-document.
    // Built from the RENDERED blocks so a preview never scrolls to a projected-away target.
    val registry = remember(blocks) { buildAnchorRegistry(blocks) }

    // Live expansion controller for the whole document — auto-opens a COLLAPSED details section an
    // in-document anchor lands inside. Dormant (opens nothing) until [RichAnchorController.navigate]
    // requests a path.
    val detailsExpansion = remember { RichDetailsExpansion() }

    // One requester per top-level block that is an anchor target. Created for every mode (cheap),
    // but only wired into the tree + tap dispatch in Reading — a FeedPreview has no scroll parent.
    val targetIndices = remember(registry) { registry.values.map { it.blockIndex }.toSet() }
    val requesters = remember(targetIndices) { targetIndices.associateWith { BringIntoViewRequester() } }
    val scope = rememberCoroutineScope()
    val controller = remember(requesters, detailsExpansion, scope) {
        RichAnchorController(requesters, detailsExpansion, scope)
    }
    val activeController = if (reading) controller else null

    val anchorTap = remember(registry, controller, reading, uriHandler, confirmMaskedLink) {
        { name: String, url: String ->
            when (val action = resolveAnchorTap(name, url, registry, canScroll = reading)) {
                is AnchorTapAction.Scroll -> controller.navigate(action.target)
                // Same anti-phishing path as a masked RichInline.Url: the confirm hook resolves
                // the destination and surfaces the sheet only for genuinely external targets;
                // the sentinel default (standalone previews / tests) opens directly.
                is AnchorTapAction.OpenUrl ->
                    if (confirmMaskedLink === Unhandled) runCatching { uriHandler.openUri(action.url) } else confirmMaskedLink(action.url)
                AnchorTapAction.None -> Unit
            }
            Unit
        }
    }

    CompositionLocalProvider(
        LocalRichAnchorTap provides anchorTap,
        LocalRichReading provides reading,
        LocalRichDetailsExpansion provides detailsExpansion,
        LocalRichAnchorController provides activeController,
    ) {
        // Hosts the fullscreen table viewer a compact feed-preview table escalates to, and
        // provides LocalTableViewer to the body below. The overlay is a Dialog, so a single host
        // per rich message is enough — it escapes to the window regardless of feed position.
        RichTableViewerHost {
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
