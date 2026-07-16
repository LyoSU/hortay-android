package dev.lyo.hortay.ui.rich

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Covers [resolveAnchorTap] — the pure decision behind an anchor / reference tap. A tap resolves
 * to an in-document scroll only when the name is registered AND scrolling is available; every
 * other case with a fallback URL yields [AnchorTapAction.OpenUrl], which [RichMessageBody] must
 * route through the masked-link confirmation (never a direct open) so an internal-looking
 * footnote can't silently open an external URL.
 */
class RichAnchorTapTest {

    private val intro = AnchorTarget(blockIndex = 0, ancestorDetailPaths = emptyList())
    private val footnote = AnchorTarget(blockIndex = 3, ancestorDetailPaths = listOf("b.3"))
    private val registry = mapOf("intro" to intro, "footnote-1" to footnote)

    @Test
    fun `resolved name with scrolling available scrolls in-document`() {
        val action = resolveAnchorTap("intro", "https://evil.example", registry, canScroll = true)
        assertEquals(AnchorTapAction.Scroll(intro), action)
    }

    @Test
    fun `resolved name normalizes case and whitespace and carries the target`() {
        val action = resolveAnchorTap("  Footnote-1 ", url = "", registry, canScroll = true)
        assertEquals(AnchorTapAction.Scroll(footnote), action)
    }

    @Test
    fun `unresolved name with a fallback url opens the url`() {
        val action = resolveAnchorTap("missing", "https://evil.example", registry, canScroll = true)
        assertEquals(AnchorTapAction.OpenUrl("https://evil.example"), action)
    }

    @Test
    fun `resolved name but no scroll target opens the url (feed preview)`() {
        // Feed cards can't scroll — even a resolvable anchor falls back to the URL, which must go
        // through the confirmation, not straight to the browser.
        val action = resolveAnchorTap("intro", "https://evil.example", registry, canScroll = false)
        assertEquals(AnchorTapAction.OpenUrl("https://evil.example"), action)
    }

    @Test
    fun `unresolved name with a blank url is a no-op`() {
        val action = resolveAnchorTap("missing", url = "", registry, canScroll = true)
        assertEquals(AnchorTapAction.None, action)
    }
}
