package dev.lyo.hortay.ui.rich

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.LayoutDirection
import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.data.rich.RichDocument
import dev.lyo.hortay.data.rich.RichInline

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
 * [mode] selects the whole document ([RichMessageMode.Full] — detail / comments-anchor
 * surfaces) or a bounded feed preview ([RichMessageMode.Preview] — see
 * [RichDocument.previewProjection]); a preview projects the block list to a short prefix BEFORE
 * composition so tables / details / slideshows / media past the fold never enter composition.
 *
 * [onScrollToBlock], when supplied, is invoked with the top-level block index an in-document
 * anchor / reference link targets; without it (or when the target isn't in the rendered blocks)
 * the link falls back to opening its external URL.
 */
@Composable
fun RichMessageBody(
    document: RichDocument,
    modifier: Modifier = Modifier,
    mode: RichMessageMode = RichMessageMode.Full,
    onScrollToBlock: ((blockIndex: Int) -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    // Preview projects to a bounded prefix before composition; Full renders every block.
    val blocks = remember(document, mode) {
        when (mode) {
            RichMessageMode.Full -> document.blocks
            RichMessageMode.Preview -> document.previewProjection()
        }
    }
    // name → top-level block index, so an AnchorLink / ReferenceLink can resolve in-document.
    // Built from the RENDERED blocks so a preview never scrolls to a projected-away target.
    val registry = remember(blocks) { buildAnchorRegistry(blocks) }
    val anchorTap = remember(registry, onScrollToBlock, uriHandler) {
        { name: String, url: String ->
            val index = registry[normalizeAnchor(name)]
            if (index != null && onScrollToBlock != null) {
                onScrollToBlock(index)
            } else if (url.isNotBlank()) {
                runCatching { uriHandler.openUri(url) }
            }
        }
    }

    CompositionLocalProvider(LocalRichAnchorTap provides anchorTap) {
        val body: @Composable () -> Unit = { RichBlocks(blocks, path = "b", modifier = modifier) }
        if (document.isRtl) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { body() }
        } else {
            body()
        }
    }
}

private fun normalizeAnchor(name: String): String = name.trim().lowercase()

/** Maps every anchor / reference name reachable inside a top-level block to that block's index. */
private fun buildAnchorRegistry(blocks: List<RichBlock>): Map<String, Int> = buildMap {
    blocks.forEachIndexed { index, block ->
        collectAnchorNames(block).forEach { name ->
            val key = normalizeAnchor(name)
            if (key.isNotEmpty()) putIfAbsent(key, index)
        }
    }
}

private fun collectAnchorNames(block: RichBlock): List<String> = buildList {
    when (block) {
        is RichBlock.Anchor -> add(block.name)
        is RichBlock.SectionHeading -> addAll(collectAnchorNames(block.text))
        is RichBlock.Paragraph -> addAll(collectAnchorNames(block.text))
        is RichBlock.Footer -> addAll(collectAnchorNames(block.text))
        is RichBlock.Preformatted -> addAll(collectAnchorNames(block.text))
        is RichBlock.PullQuote -> {
            addAll(collectAnchorNames(block.text))
            block.credit?.let { addAll(collectAnchorNames(it)) }
        }
        is RichBlock.BlockQuote -> {
            block.blocks.forEach { addAll(collectAnchorNames(it)) }
            block.credit?.let { addAll(collectAnchorNames(it)) }
        }
        is RichBlock.ListBlock -> block.items.forEach { item -> item.blocks.forEach { addAll(collectAnchorNames(it)) } }
        is RichBlock.Details -> {
            addAll(collectAnchorNames(block.header))
            block.blocks.forEach { addAll(collectAnchorNames(it)) }
        }
        else -> Unit // media / divider / math / unknown carry no anchor targets
    }
}

private fun collectAnchorNames(inline: RichInline): List<String> = buildList {
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
