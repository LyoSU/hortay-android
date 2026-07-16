package dev.lyo.hortay.data.rich

/**
 * Projects a [RichDocument] (or a single [RichBlock] / [RichInline]) to plain text for
 * search indexing, share/copy excerpts, notification bodies and feed previews.
 *
 * Projection rules:
 *  - blocks are joined with `\n` (blank results — dividers, anchors — are dropped so no
 *    stray empty lines survive);
 *  - list items are prefixed with a derived marker (`1. ` / `a. ` / `- ` / `[x] ` …) from
 *    the item's `type` / `value` / `hasCheckbox` fields;
 *  - table cells in a row are joined with ` | `, rows with `\n`;
 *  - a custom emoji projects its `alternativeText`, math its raw `expression`, a datetime
 *    its child text, and anchors/dividers to empty;
 *  - a block's caption and credit are appended after its text.
 *
 * A recursion-depth guard bounds the walk: TDLib caps document depth at 16, but malicious
 * input could nest deeper, so anything past [MAX_DEPTH] degrades to empty rather than
 * risking a stack overflow.
 */
object RichPlainText {

    /** Depth ceiling — double TDLib's server-side cap of 16, then degrade to empty. */
    const val MAX_DEPTH: Int = 32

    fun of(document: RichDocument): String = renderBlocks(document.blocks, depth = 0)

    fun of(block: RichBlock): String = renderBlock(block, depth = 0)

    fun of(inline: RichInline): String = renderInline(inline, depth = 0)

    private fun renderBlocks(blocks: List<RichBlock>, depth: Int): String {
        if (depth > MAX_DEPTH) return ""
        return blocks
            .map { renderBlock(it, depth) }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    private fun renderBlock(block: RichBlock, depth: Int): String {
        if (depth > MAX_DEPTH) return ""
        val next = depth + 1
        return when (block) {
            is RichBlock.SectionHeading -> renderInline(block.text, next)
            is RichBlock.Paragraph -> renderInline(block.text, next)
            is RichBlock.Preformatted -> renderInline(block.text, next)
            is RichBlock.Footer -> renderInline(block.text, next)
            RichBlock.Divider -> ""
            is RichBlock.Math -> block.expression
            is RichBlock.Anchor -> ""
            is RichBlock.ListBlock -> block.items
                .joinToString("\n") { item -> listMarker(item) + renderBlocks(item.blocks, next) }
            is RichBlock.BlockQuote -> appendCredit(renderBlocks(block.blocks, next), block.credit, next)
            is RichBlock.PullQuote -> appendCredit(renderInline(block.text, next), block.credit, next)
            is RichBlock.Photo -> renderCaption(block.caption, next)
            is RichBlock.Video -> renderCaption(block.caption, next)
            is RichBlock.Animation -> renderCaption(block.caption, next)
            is RichBlock.Audio -> renderCaption(block.caption, next)
            is RichBlock.VoiceNote -> renderCaption(block.caption, next)
            is RichBlock.Collage -> appendCaption(renderBlocks(block.items, next), block.caption, next)
            is RichBlock.Slideshow -> appendCaption(renderBlocks(block.items, next), block.caption, next)
            is RichBlock.Table -> renderTable(block, next)
            is RichBlock.Details -> appendLine(renderInline(block.header, next), renderBlocks(block.blocks, next))
            is RichBlock.MapPreview -> renderCaption(block.caption, next)
            is RichBlock.Unknown -> block.plainText
        }
    }

    private fun renderInline(inline: RichInline, depth: Int): String {
        if (depth > MAX_DEPTH) return ""
        val next = depth + 1
        return when (inline) {
            is RichInline.Plain -> inline.text
            is RichInline.Bold -> renderInline(inline.child, next)
            is RichInline.Italic -> renderInline(inline.child, next)
            is RichInline.Underline -> renderInline(inline.child, next)
            is RichInline.Strikethrough -> renderInline(inline.child, next)
            is RichInline.Spoiler -> renderInline(inline.child, next)
            is RichInline.Subscript -> renderInline(inline.child, next)
            is RichInline.Superscript -> renderInline(inline.child, next)
            is RichInline.Marked -> renderInline(inline.child, next)
            is RichInline.Fixed -> renderInline(inline.child, next)
            is RichInline.DateTime -> renderInline(inline.child, next)
            is RichInline.Mention -> renderInline(inline.child, next)
            is RichInline.MentionName -> renderInline(inline.child, next)
            is RichInline.Hashtag -> renderInline(inline.child, next)
            is RichInline.Cashtag -> renderInline(inline.child, next)
            is RichInline.BotCommand -> renderInline(inline.child, next)
            is RichInline.Url -> renderInline(inline.child, next)
            is RichInline.EmailAddress -> renderInline(inline.child, next)
            is RichInline.PhoneNumber -> renderInline(inline.child, next)
            is RichInline.BankCardNumber -> renderInline(inline.child, next)
            is RichInline.CustomEmoji -> inline.alternativeText
            is RichInline.Math -> inline.expression
            is RichInline.Reference -> renderInline(inline.child, next)
            is RichInline.ReferenceLink -> renderInline(inline.child, next)
            is RichInline.Anchor -> ""
            is RichInline.AnchorLink -> renderInline(inline.child, next)
            is RichInline.Sequence -> inline.parts.joinToString("") { renderInline(it, next) }
            is RichInline.Unknown -> inline.plainText
        }
    }

    private fun renderTable(table: RichBlock.Table, depth: Int): String {
        val body = table.rows.joinToString("\n") { row ->
            row.cells.joinToString(" | ") { cell -> cell.text?.let { renderInline(it, depth) }.orEmpty() }
        }
        return appendCaption(body, RichCaption(table.caption, credit = null), depth)
    }

    private fun renderCaption(caption: RichCaption?, depth: Int): String =
        appendCaption(base = "", caption = caption, depth = depth)

    private fun appendCaption(base: String, caption: RichCaption?, depth: Int): String {
        if (caption == null) return base
        val text = caption.text?.let { renderInline(it, depth) }.orEmpty()
        return appendCredit(appendLine(base, text), caption.credit, depth)
    }

    private fun appendCredit(base: String, credit: RichInline?, depth: Int): String {
        val rendered = credit?.let { renderInline(it, depth) }.orEmpty()
        return appendLine(base, rendered)
    }

    private fun appendLine(base: String, addition: String): String = when {
        addition.isEmpty() -> base
        base.isEmpty() -> addition
        else -> "$base\n$addition"
    }

    private fun listMarker(item: RichListItem): String {
        if (item.hasCheckbox) return if (item.isChecked) "[x] " else "[ ] "
        return when (item.type) {
            "1" -> "${item.value}. "
            "a" -> "${alphaOrdinal(item.value, upper = false)}. "
            "A" -> "${alphaOrdinal(item.value, upper = true)}. "
            "i" -> "${romanNumeral(item.value).lowercase()}. "
            "I" -> "${romanNumeral(item.value)}. "
            else -> "- "
        }
    }
}
