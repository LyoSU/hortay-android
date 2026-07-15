package dev.lyo.hortay.data.rich

import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RichPlainTextTest {

    @Test
    fun `projects a representative document`() {
        val doc = RichDocument(
            blocks = persistentListOf(
                RichBlock.SectionHeading(RichInline.Plain("Title"), size = 1),
                RichBlock.Paragraph(
                    RichInline.Sequence(
                        persistentListOf(
                            RichInline.Bold(RichInline.Plain("Hello ")),
                            RichInline.Url(RichInline.Plain("site"), url = "https://example.com"),
                            RichInline.Plain(" "),
                            RichInline.CustomEmoji(customEmojiId = 1L, alternativeText = "🔥"),
                        ),
                    ),
                ),
                RichBlock.ListBlock(
                    persistentListOf(
                        orderedItem("first", value = 1),
                        orderedItem("second", value = 2),
                    ),
                ),
                RichBlock.ListBlock(
                    persistentListOf(
                        checkboxItem("done", checked = true),
                        checkboxItem("todo", checked = false),
                    ),
                ),
                RichBlock.Table(
                    caption = null,
                    rows = persistentListOf(
                        RichTableRow(
                            persistentListOf(
                                cell("A"),
                                nullCell(),
                                cell("B"),
                            ),
                        ),
                    ),
                    isBordered = true,
                    isStriped = false,
                ),
                RichBlock.BlockQuote(
                    blocks = persistentListOf(RichBlock.Paragraph(RichInline.Plain("quoted"))),
                    credit = RichInline.Plain("— author"),
                ),
            ),
            isRtl = false,
            isFull = true,
        )

        val expected = listOf(
            "Title",
            "Hello site 🔥",
            "1. first",
            "2. second",
            "[x] done",
            "[ ] todo",
            "A |  | B",
            "quoted",
            "— author",
        ).joinToString("\n")

        assertEquals(expected, RichPlainText.of(doc))
    }

    @Test
    fun `list markers derive from type, value and checkbox`() {
        assertEquals("3. x", projectOrdered(type = "1", value = 3))
        assertEquals("a. x", projectOrdered(type = "a", value = 1))
        assertEquals("aa. x", projectOrdered(type = "a", value = 27))
        assertEquals("B. x", projectOrdered(type = "A", value = 2))
        assertEquals("iv. x", projectOrdered(type = "i", value = 4))
        assertEquals("IX. x", projectOrdered(type = "I", value = 9))
        assertEquals("- x", projectOrdered(type = "", value = 0))
        assertEquals("[x] x", RichPlainText.of(RichBlock.ListBlock(persistentListOf(checkboxItem("x", checked = true)))))
        assertEquals("[ ] x", RichPlainText.of(RichBlock.ListBlock(persistentListOf(checkboxItem("x", checked = false)))))
    }

    @Test
    fun `custom emoji, math and datetime project their fallbacks`() {
        assertEquals("🔥", RichPlainText.of(RichInline.CustomEmoji(1L, "🔥")))
        assertEquals("x^2", RichPlainText.of(RichInline.Math("x^2")))
        assertEquals("E=mc^2", RichPlainText.of(RichBlock.Math("E=mc^2")))
        val dt = RichInline.DateTime(
            child = RichInline.Plain("tomorrow"),
            unixTime = 0,
            formatting = RichDateTimeFormat.Relative,
        )
        assertEquals("tomorrow", RichPlainText.of(dt))
    }

    @Test
    fun `anchors and dividers project to empty and are dropped from documents`() {
        assertEquals("", RichPlainText.of(RichBlock.Divider))
        assertEquals("", RichPlainText.of(RichInline.Anchor("top")))
        val doc = RichDocument(
            blocks = persistentListOf(
                RichBlock.Paragraph(RichInline.Plain("a")),
                RichBlock.Divider,
                RichBlock.Anchor("mid"),
                RichBlock.Paragraph(RichInline.Plain("b")),
            ),
            isRtl = false,
            isFull = true,
        )
        assertEquals("a\nb", RichPlainText.of(doc))
    }

    @Test
    fun `unknown carries best-effort plain text`() {
        assertEquals("block-fallback", RichPlainText.of(RichBlock.Unknown("block-fallback")))
        assertEquals("inline-fallback", RichPlainText.of(RichInline.Unknown("inline-fallback")))
    }

    @Test
    fun `deep inline nesting degrades to empty without overflow`() {
        var inline: RichInline = RichInline.Plain("deep")
        repeat(100) { inline = RichInline.Bold(inline) }
        // A throw (e.g. StackOverflowError) would fail the test; the guard must return "" instead.
        assertEquals("", RichPlainText.of(inline))
    }

    @Test
    fun `deep block nesting degrades to empty without overflow`() {
        var block: RichBlock = RichBlock.Paragraph(RichInline.Plain("deep"))
        repeat(100) { block = RichBlock.BlockQuote(persistentListOf(block), credit = null) }
        // A throw (e.g. StackOverflowError) would fail the test; the guard must return "" instead.
        assertEquals("", RichPlainText.of(block))
    }

    private fun orderedItem(text: String, value: Int) = RichListItem(
        label = "",
        blocks = persistentListOf(RichBlock.Paragraph(RichInline.Plain(text))),
        hasCheckbox = false,
        isChecked = false,
        value = value,
        type = "1",
    )

    private fun checkboxItem(text: String, checked: Boolean) = RichListItem(
        label = "",
        blocks = persistentListOf(RichBlock.Paragraph(RichInline.Plain(text))),
        hasCheckbox = true,
        isChecked = checked,
        value = 0,
        type = "",
    )

    private fun projectOrdered(type: String, value: Int): String {
        val item = RichListItem(
            label = "",
            blocks = persistentListOf(RichBlock.Paragraph(RichInline.Plain("x"))),
            hasCheckbox = false,
            isChecked = false,
            value = value,
            type = type,
        )
        return RichPlainText.of(RichBlock.ListBlock(persistentListOf(item)))
    }

    private fun cell(text: String) = RichTableCell(
        text = RichInline.Plain(text),
        isHeader = false,
        colspan = 1,
        rowspan = 1,
        align = RichHorizontalAlignment.Left,
        valign = RichVerticalAlignment.Top,
    )

    private fun nullCell() = RichTableCell(
        text = null,
        isHeader = false,
        colspan = 1,
        rowspan = 1,
        align = RichHorizontalAlignment.Left,
        valign = RichVerticalAlignment.Top,
    )
}
