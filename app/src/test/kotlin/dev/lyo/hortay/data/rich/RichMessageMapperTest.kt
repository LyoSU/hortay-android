package dev.lyo.hortay.data.rich

import dev.lyo.hortay.data.MessageContentMapper
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.StringResolver
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Coverage for [toRichDocument] — the [TdApi.RichMessage] → [RichDocument] mapper. Builds
 * TdApi objects directly (their fields / all-args constructors are public) and asserts both
 * the mapped AST shape and the [RichPlainText] projection where it's the load-bearing output.
 */
class RichMessageMapperTest {

    @Test
    fun `maps a nested inline tree`() {
        val paragraph = TdApi.PageBlockParagraph(
            TdApi.RichTexts(
                arrayOf(
                    TdApi.RichTextBold(TdApi.RichTextItalic(TdApi.RichTextPlain("hi"))),
                    TdApi.RichTextUrl(TdApi.RichTextPlain("link"), "https://example.com", false),
                    TdApi.RichTextCustomEmoji(42L, "🔥"),
                ),
            ),
        )
        val document = richMessage(paragraph).toRichDocument()

        val block = assertInstanceOf(RichBlock.Paragraph::class.java, document.blocks.single())
        val sequence = assertInstanceOf(RichInline.Sequence::class.java, block.text)
        assertEquals(3, sequence.parts.size)

        val bold = assertInstanceOf(RichInline.Bold::class.java, sequence.parts[0])
        val italic = assertInstanceOf(RichInline.Italic::class.java, bold.child)
        assertEquals(RichInline.Plain("hi"), italic.child)

        val url = assertInstanceOf(RichInline.Url::class.java, sequence.parts[1])
        assertEquals("https://example.com", url.url)
        assertEquals(RichInline.Plain("link"), url.child)

        val emoji = assertInstanceOf(RichInline.CustomEmoji::class.java, sequence.parts[2])
        assertEquals(42L, emoji.customEmojiId)
        assertEquals("🔥", emoji.alternativeText)

        assertEquals("hilink🔥", RichPlainText.of(document))
    }

    @Test
    fun `maps every list marker type, preserving type value and checkbox`() {
        val list = TdApi.PageBlockList(
            arrayOf(
                listItem(type = "1", value = 3, text = "num"),
                listItem(type = "a", value = 1, text = "lower"),
                listItem(type = "A", value = 2, text = "upper"),
                listItem(type = "i", value = 4, text = "roman-lower"),
                listItem(type = "I", value = 9, text = "roman-upper"),
                listItem(type = "", value = 0, text = "bullet"),
                checkboxItem(checked = true, text = "done"),
                checkboxItem(checked = false, text = "todo"),
            ),
        )
        val document = richMessage(list).toRichDocument()
        val mapped = assertInstanceOf(RichBlock.ListBlock::class.java, document.blocks.single())

        assertEquals(listOf("1", "a", "A", "i", "I", "", "", ""), mapped.items.map { it.type })
        assertEquals(listOf(3, 1, 2, 4, 9, 0, 0, 0), mapped.items.map { it.value })
        assertEquals(
            listOf(false, false, false, false, false, false, true, true),
            mapped.items.map { it.hasCheckbox },
        )
        assertEquals(listOf(true, false), mapped.items.takeLast(2).map { it.isChecked })

        // The projector derives markers from type/value/checkbox — pin the end-to-end result.
        val expected = listOf(
            "3. num", "a. lower", "B. upper", "iv. roman-lower", "IX. roman-upper",
            "- bullet", "[x] done", "[ ] todo",
        ).joinToString("\n")
        assertEquals(expected, RichPlainText.of(document))
    }

    @Test
    fun `maps a table keeping a null cell text null`() {
        val row = arrayOf(
            tableCell("A"),
            TdApi.PageBlockTableCell(
                null,
                false,
                1,
                1,
                TdApi.PageBlockHorizontalAlignmentLeft(),
                TdApi.PageBlockVerticalAlignmentTop(),
            ),
            tableCell("B"),
        )
        val table = TdApi.PageBlockTable(
            TdApi.RichTextPlain(""),
            arrayOf(row),
            true,
            false,
        )
        val document = richMessage(table).toRichDocument()
        val mapped = assertInstanceOf(RichBlock.Table::class.java, document.blocks.single())

        assertNull(mapped.caption, "empty table caption normalises to null")
        assertTrue(mapped.isBordered)
        val cells = mapped.rows.single().cells
        assertEquals(RichInline.Plain("A"), cells[0].text)
        assertNull(cells[1].text, "cell hidden under a colspan/rowspan stays null")
        assertEquals(RichInline.Plain("B"), cells[2].text)
        assertEquals("A |  | B", RichPlainText.of(document))
    }

    @Test
    fun `folds instant-view-only blocks to Unknown with best-effort text`() {
        val document = richMessage(
            TdApi.PageBlockTitle(TdApi.RichTextPlain("Doc Title")),
        ).toRichDocument()
        val block = assertInstanceOf(RichBlock.Unknown::class.java, document.blocks.single())
        assertEquals("Doc Title", block.plainText)
    }

    @Test
    fun `richTextDiff folds to its new text child`() {
        val document = richMessage(
            TdApi.PageBlockParagraph(
                TdApi.RichTextDiff(TdApi.RichTextPlain("new"), TdApi.RichTextPlain("old")),
            ),
        ).toRichDocument()
        val block = assertInstanceOf(RichBlock.Paragraph::class.java, document.blocks.single())
        assertEquals(RichInline.Plain("new"), block.text)
        assertEquals("new", RichPlainText.of(document))
    }

    @Test
    fun `deep inline nesting folds to Unknown without stack overflow`() {
        var text: TdApi.RichText = TdApi.RichTextPlain("deep")
        repeat(100) { text = TdApi.RichTextBold(text) }
        val document = richMessage(TdApi.PageBlockParagraph(text)).toRichDocument()

        // Must not throw a StackOverflowError; the guard folds the over-deep tail to Unknown,
        // so the whole over-deep run projects to empty.
        assertEquals("", RichPlainText.of(document))
        assertTrue(containsUnknownInline(document.blocks.single()), "over-deep tail folds to Unknown")
    }

    @Test
    fun `messageRichMessage maps to PostContent RichMessage with cached plain text`() {
        val content = TdApi.MessageRichMessage(
            TdApi.RichMessage(
                arrayOf(
                    TdApi.PageBlockSectionHeading(TdApi.RichTextPlain("Heading"), 1),
                    TdApi.PageBlockParagraph(TdApi.RichTextPlain("Body")),
                ),
                false,
                true,
            ),
        )
        val mapped = MessageContentMapper.map(content, EmptyStringResolver)
        val rich = assertInstanceOf(PostContent.RichMessage::class.java, mapped)
        assertEquals("Heading\nBody", rich.plainText)
        assertEquals(rich.plainText, rich.captionPlain)
        assertTrue(rich.document.isFull)
        assertEquals(2, rich.document.blocks.size)
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private fun richMessage(vararg blocks: TdApi.PageBlock): TdApi.RichMessage =
        TdApi.RichMessage(arrayOf(*blocks), false, true)

    private fun listItem(type: String, value: Int, text: String) = TdApi.PageBlockListItem(
        "",
        arrayOf(TdApi.PageBlockParagraph(TdApi.RichTextPlain(text))),
        false,
        false,
        value,
        type,
    )

    private fun checkboxItem(checked: Boolean, text: String) = TdApi.PageBlockListItem(
        "",
        arrayOf(TdApi.PageBlockParagraph(TdApi.RichTextPlain(text))),
        true,
        checked,
        0,
        "",
    )

    private fun tableCell(text: String) = TdApi.PageBlockTableCell(
        TdApi.RichTextPlain(text),
        false,
        1,
        1,
        TdApi.PageBlockHorizontalAlignmentLeft(),
        TdApi.PageBlockVerticalAlignmentTop(),
    )

    private fun containsUnknownInline(block: RichBlock): Boolean =
        block is RichBlock.Paragraph && containsUnknownInline(block.text)

    private fun containsUnknownInline(inline: RichInline): Boolean = when (inline) {
        is RichInline.Unknown -> true
        is RichInline.Bold -> containsUnknownInline(inline.child)
        is RichInline.Sequence -> inline.parts.any { containsUnknownInline(it) }
        else -> false
    }

    private object EmptyStringResolver : StringResolver {
        override fun getString(id: Int): String = ""
        override fun getString(id: Int, vararg args: Any?): String = ""
        override fun getQuantityString(id: Int, count: Int, vararg args: Any?): String = ""
    }
}
