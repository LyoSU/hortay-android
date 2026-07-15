package dev.lyo.hortay.data.rich

import dev.lyo.hortay.data.PHOTO_TARGET_INLINE_PX
import dev.lyo.hortay.data.TdMedia
import dev.lyo.hortay.data.VideoQualities
import dev.lyo.hortay.data.toMedia
import dev.lyo.hortay.data.toThumbMedia
import dev.lyo.hortay.data.videoQualities
import kotlinx.collections.immutable.toImmutableList
import org.drinkless.tdlib.TdApi

/**
 * Pure mapper from TDLib's [TdApi.RichMessage] (`richMessage`) to the TDLib-independent
 * [RichDocument] AST. Every known `PageBlock*` and `RichText*` constructor in the 1.8.66
 * bindings is enumerated explicitly; only genuinely unmodelled variants (a future upstream
 * addition) reach the `else` branch and fold to [RichBlock.Unknown] / [RichInline.Unknown].
 *
 * Folding rules that aren't a straight 1:1 (see `ARCHITECTURE.md` / the domain KDocs):
 *  - instant-view-only blocks (title, subtitle, header, cover, embedded, thinking, related
 *    articles, …) fold to [RichBlock.Unknown] carrying a best-effort plain projection of
 *    their children;
 *  - `richTextDiff` maps its NEW text child normally and drops the old text;
 *  - `richTextIcon` folds to `RichInline.Unknown("")` (instant-view-only, no text);
 *  - media handles reuse the message-content descriptors ([TdMedia], [VideoQualities]) from
 *    `MessageContentMapper.kt`, so the feed's media composables work unchanged; a null TdApi
 *    media yields a null domain media.
 *
 * Recursion is bounded by [RichPlainText.MAX_DEPTH] (32, double TDLib's server cap of 16):
 * anything deeper folds to [Unknown] instead of recursing, so malicious input can't overflow
 * the stack.
 */
fun TdApi.RichMessage.toRichDocument(): RichDocument = RichDocument(
    blocks = (blocks ?: emptyArray()).map { mapBlock(it, depth = 0) }.toImmutableList(),
    isRtl = isRtl,
    isFull = isFull,
)

private fun mapBlock(block: TdApi.PageBlock, depth: Int): RichBlock {
    if (depth > RichPlainText.MAX_DEPTH) return RichBlock.Unknown("")
    val next = depth + 1
    return when (block) {
        is TdApi.PageBlockSectionHeading ->
            RichBlock.SectionHeading(childInline(block.text, next), block.size)
        is TdApi.PageBlockParagraph -> RichBlock.Paragraph(childInline(block.text, next))
        is TdApi.PageBlockPreformatted ->
            RichBlock.Preformatted(childInline(block.text, next), block.language.orEmpty())
        is TdApi.PageBlockFooter -> RichBlock.Footer(childInline(block.footer, next))
        is TdApi.PageBlockDivider -> RichBlock.Divider
        is TdApi.PageBlockMathematicalExpression -> RichBlock.Math(block.expression.orEmpty())
        is TdApi.PageBlockAnchor -> RichBlock.Anchor(block.name.orEmpty())
        is TdApi.PageBlockList -> RichBlock.ListBlock(
            (block.items ?: emptyArray()).map { mapListItem(it, next) }.toImmutableList(),
        )
        is TdApi.PageBlockBlockQuote -> RichBlock.BlockQuote(
            blocks = (block.blocks ?: emptyArray()).map { mapBlock(it, next) }.toImmutableList(),
            credit = inlineOrNull(block.credit, next),
        )
        is TdApi.PageBlockPullQuote -> RichBlock.PullQuote(
            text = childInline(block.text, next),
            credit = inlineOrNull(block.credit, next),
        )
        is TdApi.PageBlockPhoto -> RichBlock.Photo(
            media = block.photo?.toMedia(PHOTO_TARGET_INLINE_PX),
            caption = mapCaption(block.caption, next),
            hasSpoiler = block.hasSpoiler,
        )
        is TdApi.PageBlockVideo -> {
            val video = block.video
            RichBlock.Video(
                media = video?.toThumbMedia(),
                playbackFileId = video?.video?.id ?: 0,
                qualities = video?.let { videoQualities(it, null) },
                durationSec = video?.duration ?: 0,
                caption = mapCaption(block.caption, next),
                needAutoplay = block.needAutoplay,
                isLooped = block.isLooped,
                hasSpoiler = block.hasSpoiler,
            )
        }
        is TdApi.PageBlockAnimation -> {
            val animation = block.animation
            RichBlock.Animation(
                media = animation?.toThumbMedia(),
                playbackFileId = animation?.animation?.id ?: 0,
                caption = mapCaption(block.caption, next),
                needAutoplay = block.needAutoplay,
                hasSpoiler = block.hasSpoiler,
            )
        }
        is TdApi.PageBlockAudio -> {
            val audio = block.audio
            RichBlock.Audio(
                fileId = audio?.audio?.id,
                title = audio?.title.orEmpty().ifBlank { audio?.fileName.orEmpty() },
                performer = audio?.performer.orEmpty(),
                durationSec = audio?.duration ?: 0,
                caption = mapCaption(block.caption, next),
            )
        }
        is TdApi.PageBlockVoiceNote -> {
            val voice = block.voiceNote
            RichBlock.VoiceNote(
                fileId = voice?.voice?.id,
                durationSec = voice?.duration ?: 0,
                waveform = voice?.waveform,
                caption = mapCaption(block.caption, next),
            )
        }
        is TdApi.PageBlockCollage -> RichBlock.Collage(
            items = (block.blocks ?: emptyArray()).map { mapBlock(it, next) }.toImmutableList(),
            caption = mapCaption(block.caption, next),
        )
        is TdApi.PageBlockSlideshow -> RichBlock.Slideshow(
            items = (block.blocks ?: emptyArray()).map { mapBlock(it, next) }.toImmutableList(),
            caption = mapCaption(block.caption, next),
        )
        is TdApi.PageBlockTable -> mapTable(block, next)
        is TdApi.PageBlockDetails -> RichBlock.Details(
            header = childInline(block.header, next),
            blocks = (block.blocks ?: emptyArray()).map { mapBlock(it, next) }.toImmutableList(),
            isOpen = block.isOpen,
        )
        is TdApi.PageBlockMap -> RichBlock.MapPreview(
            latitude = block.location?.latitude ?: 0.0,
            longitude = block.location?.longitude ?: 0.0,
            zoom = block.zoom,
            width = block.width,
            height = block.height,
            caption = mapCaption(block.caption, next),
        )

        // Instant-view-only blocks — not modelled; fold to Unknown with a best-effort plain
        // projection of whatever text/children they carry.
        is TdApi.PageBlockTitle -> RichBlock.Unknown(inlinePlain(block.title, next))
        is TdApi.PageBlockSubtitle -> RichBlock.Unknown(inlinePlain(block.subtitle, next))
        is TdApi.PageBlockAuthorDate -> RichBlock.Unknown(inlinePlain(block.author, next))
        is TdApi.PageBlockHeader -> RichBlock.Unknown(inlinePlain(block.header, next))
        is TdApi.PageBlockSubheader -> RichBlock.Unknown(inlinePlain(block.subheader, next))
        is TdApi.PageBlockKicker -> RichBlock.Unknown(inlinePlain(block.kicker, next))
        is TdApi.PageBlockThinking -> RichBlock.Unknown(inlinePlain(block.text, next))
        is TdApi.PageBlockCover ->
            RichBlock.Unknown(block.cover?.let { RichPlainText.of(mapBlock(it, next)) }.orEmpty())
        is TdApi.PageBlockEmbedded -> RichBlock.Unknown(captionPlain(block.caption, next))
        is TdApi.PageBlockEmbeddedPost -> RichBlock.Unknown(
            listOf(
                block.author.orEmpty(),
                blocksPlain(block.blocks, next),
                captionPlain(block.caption, next),
            ).filter { it.isNotEmpty() }.joinToString("\n"),
        )
        is TdApi.PageBlockChatLink -> RichBlock.Unknown(block.title.orEmpty())
        is TdApi.PageBlockRelatedArticles -> RichBlock.Unknown(inlinePlain(block.header, next))
        else -> RichBlock.Unknown("")
    }
}

private fun mapInline(text: TdApi.RichText, depth: Int): RichInline {
    if (depth > RichPlainText.MAX_DEPTH) return RichInline.Unknown("")
    val next = depth + 1
    return when (text) {
        is TdApi.RichTextPlain -> RichInline.Plain(text.text.orEmpty())
        is TdApi.RichTextBold -> RichInline.Bold(childInline(text.text, next))
        is TdApi.RichTextItalic -> RichInline.Italic(childInline(text.text, next))
        is TdApi.RichTextUnderline -> RichInline.Underline(childInline(text.text, next))
        is TdApi.RichTextStrikethrough -> RichInline.Strikethrough(childInline(text.text, next))
        is TdApi.RichTextSpoiler -> RichInline.Spoiler(childInline(text.text, next))
        is TdApi.RichTextSubscript -> RichInline.Subscript(childInline(text.text, next))
        is TdApi.RichTextSuperscript -> RichInline.Superscript(childInline(text.text, next))
        is TdApi.RichTextMarked -> RichInline.Marked(childInline(text.text, next))
        is TdApi.RichTextFixed -> RichInline.Fixed(childInline(text.text, next))
        is TdApi.RichTextDateTime -> RichInline.DateTime(
            child = childInline(text.text, next),
            unixTime = text.unixTime,
            formatting = mapDateTimeFormat(text.formattingType),
        )
        is TdApi.RichTextMention ->
            RichInline.Mention(childInline(text.text, next), text.username.orEmpty())
        is TdApi.RichTextMentionName ->
            RichInline.MentionName(childInline(text.text, next), text.userId)
        is TdApi.RichTextHashtag ->
            RichInline.Hashtag(childInline(text.text, next), text.hashtag.orEmpty())
        is TdApi.RichTextCashtag ->
            RichInline.Cashtag(childInline(text.text, next), text.cashtag.orEmpty())
        is TdApi.RichTextBotCommand ->
            RichInline.BotCommand(childInline(text.text, next), text.botCommand.orEmpty())
        is TdApi.RichTextUrl -> RichInline.Url(childInline(text.text, next), text.url.orEmpty())
        is TdApi.RichTextEmailAddress ->
            RichInline.EmailAddress(childInline(text.text, next), text.emailAddress.orEmpty())
        is TdApi.RichTextPhoneNumber ->
            RichInline.PhoneNumber(childInline(text.text, next), text.phoneNumber.orEmpty())
        is TdApi.RichTextBankCardNumber ->
            RichInline.BankCardNumber(childInline(text.text, next), text.bankCardNumber.orEmpty())
        is TdApi.RichTextCustomEmoji ->
            RichInline.CustomEmoji(text.customEmojiId, text.alternativeText.orEmpty())
        is TdApi.RichTextMathematicalExpression -> RichInline.Math(text.expression.orEmpty())
        is TdApi.RichTextReference ->
            RichInline.Reference(childInline(text.text, next), text.name.orEmpty())
        is TdApi.RichTextReferenceLink -> RichInline.ReferenceLink(
            child = childInline(text.text, next),
            referenceName = text.referenceName.orEmpty(),
            url = text.url.orEmpty(),
        )
        is TdApi.RichTextAnchor -> RichInline.Anchor(text.name.orEmpty())
        is TdApi.RichTextAnchorLink -> RichInline.AnchorLink(
            child = childInline(text.text, next),
            anchorName = text.anchorName.orEmpty(),
            url = text.url.orEmpty(),
        )
        is TdApi.RichTexts -> RichInline.Sequence(
            (text.texts ?: emptyArray()).map { mapInline(it, next) }.toImmutableList(),
        )
        // `richTextDiff` carries the pre-edit and post-edit text; we keep the new text and
        // drop the old (mapped at the same depth so styling stays intact).
        is TdApi.RichTextDiff -> childInline(text.text, depth)
        // Instant-view-only icon — no text to project.
        is TdApi.RichTextIcon -> RichInline.Unknown("")
        else -> RichInline.Unknown("")
    }
}

/** Map a nullable [TdApi.RichText] child, substituting an empty [RichInline.Plain] for null. */
private fun childInline(text: TdApi.RichText?, depth: Int): RichInline =
    if (text == null) RichInline.Plain("") else mapInline(text, depth)

/**
 * Map a nullable [TdApi.RichText] to a domain inline, collapsing to `null` when it projects to
 * empty text — used for captions / credits / table captions the renderer skips when blank.
 */
private fun inlineOrNull(text: TdApi.RichText?, depth: Int): RichInline? {
    if (text == null) return null
    val inline = mapInline(text, depth)
    return if (RichPlainText.of(inline).isEmpty()) null else inline
}

private fun mapListItem(item: TdApi.PageBlockListItem, depth: Int): RichListItem = RichListItem(
    label = item.label.orEmpty(),
    blocks = (item.blocks ?: emptyArray()).map { mapBlock(it, depth) }.toImmutableList(),
    hasCheckbox = item.hasCheckbox,
    isChecked = item.isChecked,
    value = item.value,
    type = item.type.orEmpty(),
)

private fun mapTable(table: TdApi.PageBlockTable, depth: Int): RichBlock.Table = RichBlock.Table(
    caption = inlineOrNull(table.caption, depth),
    rows = (table.cells ?: emptyArray()).map { row ->
        RichTableRow(
            (row ?: emptyArray()).map { mapCell(it, depth) }.toImmutableList(),
        )
    }.toImmutableList(),
    isBordered = table.isBordered,
    isStriped = table.isStriped,
)

private fun mapCell(cell: TdApi.PageBlockTableCell, depth: Int): RichTableCell = RichTableCell(
    // A null cell text is an invisible cell hidden under a neighbour's colspan/rowspan — keep
    // it null (do NOT normalise a present-but-empty text to null here).
    text = cell.text?.let { mapInline(it, depth) },
    isHeader = cell.isHeader,
    colspan = cell.colspan,
    rowspan = cell.rowspan,
    align = mapHorizontalAlignment(cell.align),
    valign = mapVerticalAlignment(cell.valign),
)

private fun mapCaption(caption: TdApi.PageBlockCaption?, depth: Int): RichCaption? {
    if (caption == null) return null
    val text = inlineOrNull(caption.text, depth)
    val credit = inlineOrNull(caption.credit, depth)
    if (text == null && credit == null) return null
    return RichCaption(text, credit)
}

private fun mapHorizontalAlignment(
    align: TdApi.PageBlockHorizontalAlignment?,
): RichHorizontalAlignment = when (align) {
    is TdApi.PageBlockHorizontalAlignmentCenter -> RichHorizontalAlignment.Center
    is TdApi.PageBlockHorizontalAlignmentRight -> RichHorizontalAlignment.Right
    else -> RichHorizontalAlignment.Left
}

private fun mapVerticalAlignment(
    valign: TdApi.PageBlockVerticalAlignment?,
): RichVerticalAlignment = when (valign) {
    is TdApi.PageBlockVerticalAlignmentMiddle -> RichVerticalAlignment.Middle
    is TdApi.PageBlockVerticalAlignmentBottom -> RichVerticalAlignment.Bottom
    else -> RichVerticalAlignment.Top
}

private fun mapDateTimeFormat(type: TdApi.DateTimeFormattingType?): RichDateTimeFormat = when (type) {
    is TdApi.DateTimeFormattingTypeAbsolute -> RichDateTimeFormat.Absolute(
        timePrecision = mapPrecision(type.timePrecision),
        datePrecision = mapPrecision(type.datePrecision),
        showDayOfWeek = type.showDayOfWeek,
    )
    else -> RichDateTimeFormat.Relative
}

private fun mapPrecision(precision: TdApi.DateTimePartPrecision?): RichDateTimePrecision =
    when (precision) {
        is TdApi.DateTimePartPrecisionShort -> RichDateTimePrecision.Short
        is TdApi.DateTimePartPrecisionLong -> RichDateTimePrecision.Long
        else -> RichDateTimePrecision.None
    }

// ── Best-effort plain-text extraction for the instant-view-only blocks that fold to Unknown ──

private fun inlinePlain(text: TdApi.RichText?, depth: Int): String =
    if (text == null) "" else RichPlainText.of(mapInline(text, depth))

private fun blocksPlain(blocks: Array<TdApi.PageBlock>?, depth: Int): String =
    (blocks ?: emptyArray())
        .map { RichPlainText.of(mapBlock(it, depth)) }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

private fun captionPlain(caption: TdApi.PageBlockCaption?, depth: Int): String {
    if (caption == null) return ""
    return listOf(inlinePlain(caption.text, depth), inlinePlain(caption.credit, depth))
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}
