package dev.lyo.hortay.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Locks in the pure local-fallback parser inside [TelegramLinkResolver]. The fallback
 * runs only when TDLib's `GetInternalLinkType` is unavailable (cold-launch race
 * before the JNI side is wired), so it has to mirror TDLib's output for the link
 * shapes our UI dispatches natively — otherwise the same tapped URL would land in a
 * different in-app destination depending on whether the daemon was warmed.
 *
 * Cases captured here also pin the resolver's host gate (only `t.me` / `telegram.me`
 * / `telegram.dog` proceed to handle parsing — without that, a `https://github.com/foo`
 * tap would be misread as a public channel handle "foo" and re-issue
 * SearchPublicChat).
 */
class TelegramLinkResolverParseLocalTest {

    private fun parse(
        scheme: String?,
        host: String?,
        path: List<String> = emptyList(),
        query: Map<String, String> = emptyMap(),
        rawUrl: String,
    ): DeepLink? = TelegramLinkResolver.parseLocalFromParts(
        scheme = scheme,
        host = host,
        pathSegments = path,
        queryParams = query,
        rawUrl = rawUrl,
    )

    // ---------- t.me public channel handles ----------

    @Test
    fun `t_me handle resolves to public channel`() {
        val raw = "https://t.me/durov"
        assertEquals(
            DeepLink.PublicChannel(handle = "durov", serverPostId = null, originalUrl = raw),
            parse("https", "t.me", listOf("durov"), rawUrl = raw),
        )
    }

    @Test
    fun `t_me handle with post id captures server post number`() {
        val raw = "https://t.me/durov/42"
        assertEquals(
            DeepLink.PublicChannel(handle = "durov", serverPostId = 42L, originalUrl = raw),
            parse("https", "t.me", listOf("durov", "42"), rawUrl = raw),
        )
    }

    @Test
    fun `at-prefixed handle is stripped`() {
        // Some shares emit `t.me/@durov` — accept and normalise.
        val raw = "https://t.me/@durov"
        assertEquals(
            DeepLink.PublicChannel(handle = "durov", serverPostId = null, originalUrl = raw),
            parse("https", "t.me", listOf("@durov"), rawUrl = raw),
        )
    }

    @Test
    fun `telegram_me alias is accepted as a public host`() {
        val raw = "https://telegram.me/durov"
        assertEquals(
            DeepLink.PublicChannel("durov", null, raw),
            parse("https", "telegram.me", listOf("durov"), rawUrl = raw),
        )
    }

    @Test
    fun `telegram_dog alias is accepted as a public host`() {
        val raw = "https://telegram.dog/durov"
        assertEquals(
            DeepLink.PublicChannel("durov", null, raw),
            parse("https", "telegram.dog", listOf("durov"), rawUrl = raw),
        )
    }

    // ---------- t.me/c/<chatId>/<msg> private channel ----------

    @Test
    fun `t_me private channel resolves with -100 prefix`() {
        val raw = "https://t.me/c/1234567890"
        assertEquals(
            DeepLink.PrivateChannel(
                chatId = -1001234567890L,
                serverPostId = null,
                originalUrl = raw,
            ),
            parse("https", "t.me", listOf("c", "1234567890"), rawUrl = raw),
        )
    }

    @Test
    fun `t_me private channel with msg captures post id`() {
        val raw = "https://t.me/c/1234567890/42"
        assertEquals(
            DeepLink.PrivateChannel(
                chatId = -1001234567890L,
                serverPostId = 42L,
                originalUrl = raw,
            ),
            parse("https", "t.me", listOf("c", "1234567890", "42"), rawUrl = raw),
        )
    }

    @Test
    fun `t_me c with non-numeric chat id returns null`() {
        assertNull(
            parse("https", "t.me", listOf("c", "abc"), rawUrl = "https://t.me/c/abc"),
        )
    }

    // ---------- t.me invite links — `+` and legacy `joinchat` ----------

    @Test
    fun `t_me plus invite resolves to ChatInvite using the full URL`() {
        // The canonical form: the entire URL IS the invite payload, TDLib expects it
        // verbatim on CheckChatInviteLink.
        val raw = "https://t.me/+abc123xyz"
        assertEquals(
            DeepLink.ChatInvite(inviteLink = raw, originalUrl = raw),
            parse("https", "t.me", listOf("+abc123xyz"), rawUrl = raw),
        )
    }

    @Test
    fun `t_me legacy joinchat reconstructs canonical invite URL`() {
        // TDLib's CheckChatInviteLink expects `t.me/+<hash>` shape — reconstruct it so
        // the warm path (post-TDLib-boot) and the cold path agree on the invite token.
        val raw = "https://t.me/joinchat/abc123xyz"
        assertEquals(
            DeepLink.ChatInvite(
                inviteLink = "https://t.me/+abc123xyz",
                originalUrl = raw,
            ),
            parse("https", "t.me", listOf("joinchat", "abc123xyz"), rawUrl = raw),
        )
    }

    @Test
    fun `t_me bare plus without token is rejected`() {
        // `t.me/+` alone — no hash to check. Not a meaningful invite.
        assertNull(
            parse("https", "t.me", listOf("+"), rawUrl = "https://t.me/+"),
        )
    }

    // ---------- t.me blocked routes (sticker, share, addtheme, proxy) ----------

    @Test
    fun `t_me addstickers is rejected to avoid misreading as a channel handle`() {
        assertNull(
            parse("https", "t.me", listOf("addstickers", "PackName"), rawUrl = "https://t.me/addstickers/PackName"),
        )
    }

    @Test
    fun `t_me share is rejected`() {
        assertNull(parse("https", "t.me", listOf("share"), rawUrl = "https://t.me/share"))
    }

    @Test
    fun `t_me proxy is rejected`() {
        assertNull(parse("https", "t.me", listOf("proxy"), rawUrl = "https://t.me/proxy"))
    }

    // ---------- tg:// schemes ----------

    @Test
    fun `tg resolve carries domain and post id`() {
        val raw = "tg://resolve?domain=durov&post=42"
        assertEquals(
            DeepLink.PublicChannel(handle = "durov", serverPostId = 42L, originalUrl = raw),
            parse("tg", "resolve", query = mapOf("domain" to "durov", "post" to "42"), rawUrl = raw),
        )
    }

    @Test
    fun `tg resolve at-prefixed domain is normalised`() {
        val raw = "tg://resolve?domain=@durov"
        assertEquals(
            DeepLink.PublicChannel("durov", null, raw),
            parse("tg", "resolve", query = mapOf("domain" to "@durov"), rawUrl = raw),
        )
    }

    @Test
    fun `tg privatepost resolves with -100 prefix`() {
        val raw = "tg://privatepost?channel=1234567890&post=42"
        assertEquals(
            DeepLink.PrivateChannel(
                chatId = -1001234567890L,
                serverPostId = 42L,
                originalUrl = raw,
            ),
            parse(
                "tg", "privatepost",
                query = mapOf("channel" to "1234567890", "post" to "42"),
                rawUrl = raw,
            ),
        )
    }

    @Test
    fun `tg join carries invite token reconstructed as canonical URL`() {
        val raw = "tg://join?invite=abc123xyz"
        assertEquals(
            DeepLink.ChatInvite(
                inviteLink = "https://t.me/+abc123xyz",
                originalUrl = raw,
            ),
            parse("tg", "join", query = mapOf("invite" to "abc123xyz"), rawUrl = raw),
        )
    }

    @Test
    fun `tg resolve without domain is rejected`() {
        // Defensive: malformed `tg://resolve?` without the required param.
        assertNull(parse("tg", "resolve", query = emptyMap(), rawUrl = "tg://resolve"))
    }

    // ---------- host gate (non-Telegram hosts) ----------

    @Test
    fun `non-telegram https host is rejected`() {
        // Regression: previously the fallback misread any path-segment[0] as a public
        // handle. A tap on `https://github.com/durov` would have routed into
        // SearchPublicChat("durov") and surfaced an unrelated Telegram channel.
        assertNull(
            parse("https", "github.com", listOf("durov"), rawUrl = "https://github.com/durov"),
        )
    }

    @Test
    fun `t_me without path returns null`() {
        assertNull(parse("https", "t.me", rawUrl = "https://t.me"))
    }

    @Test
    fun `unknown scheme returns null`() {
        assertNull(parse("ftp", "t.me", listOf("durov"), rawUrl = "ftp://t.me/durov"))
    }

    // ---------- tg://search hashtag URLs ----------

    @Test
    fun `tg search with hashtag query produces HashtagSearch`() {
        // `tg://search?query=#foo` is the canonical global hashtag-search URL
        // (`InternalLinkTypeSearch` in the warm path; this fallback covers cold-
        // launch). Channel scope is always null — no documented Telegram URL form
        // carries a per-channel hashtag scope.
        val raw = "tg://search?query=%23foo"
        assertEquals(
            DeepLink.HashtagSearch(tag = "#foo", channelHandle = null, originalUrl = raw),
            parse("tg", "search", query = mapOf("query" to "#foo"), rawUrl = raw),
        )
    }

    @Test
    fun `tg search with bare query (no hash) still recognises as hashtag`() {
        // Telegram-Android occasionally emits the tag without the leading `#`
        // inside the `query` param. Treat it as a hashtag — the prefix is
        // re-added on the way out so the snackbar shows the canonical form.
        val raw = "tg://search?query=foo"
        assertEquals(
            DeepLink.HashtagSearch(tag = "#foo", channelHandle = null, originalUrl = raw),
            parse("tg", "search", query = mapOf("query" to "foo"), rawUrl = raw),
        )
    }

    @Test
    fun `tg search accepts short q alias`() {
        // Some share flows emit `?q=` instead of `?query=`. Equivalent intent.
        val raw = "tg://search?q=%23foo"
        assertEquals(
            DeepLink.HashtagSearch(tag = "#foo", channelHandle = null, originalUrl = raw),
            parse("tg", "search", query = mapOf("q" to "#foo"), rawUrl = raw),
        )
    }

    @Test
    fun `tg search with empty query is rejected`() {
        assertNull(parse("tg", "search", query = mapOf("query" to ""), rawUrl = "tg://search?query="))
    }

    @Test
    fun `tg search without query param is rejected`() {
        assertNull(parse("tg", "search", query = emptyMap(), rawUrl = "tg://search"))
    }

    @Test
    fun `tg search with whitespace-only query is rejected`() {
        assertNull(parse("tg", "search", query = mapOf("query" to "  "), rawUrl = "tg://search?query=%20"))
    }

    @Test
    fun `tg search with multi-word query is rejected as hashtag`() {
        // `tg://search?query=foo+bar` is a free-text search, not a hashtag.
        // Whitespace in the decoded body disqualifies the hashtag branch — the
        // resolver falls through (in fallback, this just returns null; in the
        // warm path the same URL would produce HashtagSearch with empty tag,
        // which the snackbar collector renders as the generic form).
        assertNull(
            parse("tg", "search", query = mapOf("query" to "foo bar"), rawUrl = "tg://search?query=foo+bar"),
        )
    }
}
