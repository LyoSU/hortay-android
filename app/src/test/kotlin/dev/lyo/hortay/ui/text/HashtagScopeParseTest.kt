package dev.lyo.hortay.ui.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Locks in the `#tag@channel` decomposition used by [parseHashtagWithScope].
 *
 * The contract matters in two converging dispatch paths:
 *   - the scaffold-level default [LocalHashtagTap] (sees explicit `#tag@channel`
 *     text entities and respects the suffix as the user's choice), and
 *   - the PostBody-level scoped wrapper, which only injects its captured channel
 *     scope when the tap text has NO `@suffix` already (so the suffix-bearing
 *     entity wins).
 *
 * Pure stdlib — no Android types — so this test runs on the JVM without
 * Robolectric.
 */
class HashtagScopeParseTest {

    @Test
    fun `bare hashtag carries leading hash and no scope`() {
        assertEquals("#foo" to null, parseHashtagWithScope("#foo"))
    }

    @Test
    fun `hashtag without leading hash still normalises`() {
        assertEquals("#foo" to null, parseHashtagWithScope("foo"))
    }

    @Test
    fun `scoped hashtag splits on at sign`() {
        // The canonical TDLib `TextEntityTypeHashtag` form: "#tag, optionally
        // containing a chat username at the end". The suffix becomes the
        // resolved channel scope.
        assertEquals("#foo" to "channel", parseHashtagWithScope("#foo@channel"))
    }

    @Test
    fun `scoped hashtag without leading hash also splits`() {
        // Defensive — some entity payloads strip the leading sigil before reaching
        // the tap handler. The split still produces a usable (tag, scope) pair.
        assertEquals("#foo" to "channel", parseHashtagWithScope("foo@channel"))
    }

    @Test
    fun `trailing at sign with no channel returns null scope`() {
        // `#foo@` is malformed — no scope name follows. Treat as plain hashtag
        // rather than synthesising an empty channel handle.
        assertEquals("#foo@" to null, parseHashtagWithScope("#foo@"))
    }

    @Test
    fun `leading at sign with empty tag returns global hashtag of bare body`() {
        // `#@channel` would have an empty tag body, so we treat it as a plain
        // hashtag of value `#@channel` (no scope split). The default tap will
        // then dispatch a global search — the renderer would normally never
        // produce this shape, but the dispatcher stays defensive.
        assertEquals("#@channel" to null, parseHashtagWithScope("#@channel"))
    }

    @Test
    fun `whitespace is trimmed`() {
        assertEquals("#foo" to "channel", parseHashtagWithScope("  #foo@channel  "))
    }

    @Test
    fun `empty input is preserved as empty`() {
        // Reachable only on a degenerate `LocalHashtagTap.current("")` call;
        // exercising the no-throw branch keeps the dispatcher resilient.
        assertEquals("" to null, parseHashtagWithScope(""))
    }
}
