package dev.lyo.hortay.ui.rich

import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.data.rich.RichInline
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Covers [findReferenceExcerpt] — the pure "is this footnote resolvable in-document?" projection
 * behind the reference bottom sheet. A [RichInline.Reference] carries the footnote body as its
 * child; the excerpt is that child projected to plain text. An unresolvable name yields null so
 * the caller keeps the external open-URL fallback.
 */
class RichReferenceExcerptTest {

    private fun reference(name: String, body: String): RichInline =
        RichInline.Reference(RichInline.Plain(body), name)

    @Test
    fun `resolves a reference sitting in a paragraph`() {
        val blocks = persistentListOf(
            RichBlock.Paragraph(RichInline.Plain("Body")),
            RichBlock.Footer(reference("fn1", "The footnote text.")),
        )
        assertEquals("The footnote text.", findReferenceExcerpt(blocks, "fn1"))
    }

    @Test
    fun `resolves a reference nested inside a details body`() {
        val blocks = persistentListOf(
            RichBlock.Details(
                header = RichInline.Plain("Notes"),
                blocks = persistentListOf(RichBlock.Paragraph(reference("src", "Nested source."))),
                isOpen = false,
            ),
        )
        assertEquals("Nested source.", findReferenceExcerpt(blocks, "src"))
    }

    @Test
    fun `normalizes case and whitespace on the name`() {
        val blocks = persistentListOf(RichBlock.Paragraph(reference("Ref-2", "Two.")))
        assertEquals("Two.", findReferenceExcerpt(blocks, "  ref-2 "))
    }

    @Test
    fun `unknown name is unresolvable`() {
        val blocks = persistentListOf(RichBlock.Paragraph(reference("known", "Here.")))
        assertNull(findReferenceExcerpt(blocks, "missing"))
    }

    @Test
    fun `an empty reference body is unresolvable`() {
        val blocks = persistentListOf(RichBlock.Paragraph(reference("blank", "   ")))
        assertNull(findReferenceExcerpt(blocks, "blank"))
    }
}
