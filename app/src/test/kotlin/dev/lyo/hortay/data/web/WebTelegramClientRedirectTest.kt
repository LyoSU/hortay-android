package dev.lyo.hortay.data.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the 30x redirect classification from a `t.me/s/<u>` probe. The earlier
 * check (`location.contains("t.me/") && !contains("t.me/s/")`) mis-classified a
 * redirect to a PUBLIC post permalink (`t.me/<u>/<seq>`) as [FetchResult.PrivateChannel].
 * Only a bare channel root (`t.me/<username>`, no message path) is the private
 * signal; anything else is a network error we must not silently absorb.
 */
class WebTelegramClientRedirectTest {

    @Test
    fun `bare channel root is a private channel`() {
        assertInstanceOf(
            FetchResult.PrivateChannel::class.java,
            classifyRedirect(302, "https://t.me/durov"),
        )
    }

    @Test
    fun `bare channel root with trailing slash is a private channel`() {
        assertInstanceOf(
            FetchResult.PrivateChannel::class.java,
            classifyRedirect(301, "https://t.me/durov/"),
        )
    }

    @Test
    fun `post permalink is not private`() {
        assertInstanceOf(
            FetchResult.NetworkError::class.java,
            classifyRedirect(302, "https://t.me/durov/123"),
        )
    }

    @Test
    fun `preview path redirect is not private`() {
        // A redirect back to the /s/ preview shouldn't happen, but if it does it is
        // not the private signal — it carries an extra path segment.
        assertInstanceOf(
            FetchResult.NetworkError::class.java,
            classifyRedirect(302, "https://t.me/s/durov"),
        )
    }

    @Test
    fun `foreign redirect target is a network error`() {
        val result = classifyRedirect(302, "https://example.com/login")
        assertInstanceOf(FetchResult.NetworkError::class.java, result)
    }

    @Test
    fun `empty location is a network error`() {
        assertInstanceOf(
            FetchResult.NetworkError::class.java,
            classifyRedirect(308, ""),
        )
    }

    @Test
    fun `network error carries the code and location for diagnosis`() {
        val result = classifyRedirect(307, "https://example.com/x")
        result as FetchResult.NetworkError
        assertTrue(result.cause.message!!.contains("307"))
        assertTrue(result.cause.message!!.contains("example.com"))
    }

    @Test
    fun `preview url carries the handle as one path segment`() {
        assertEquals("https://t.me/s/durov", buildTmePreviewUrl("durov", null, null))
        assertEquals(
            "https://t.me/s/durov?before=42&after=7",
            buildTmePreviewUrl("durov", before = "42", after = "7"),
        )
    }

    @Test
    fun `preview url refuses handles that could change path or query`() {
        listOf("..", "../foo", "foo/bar", "foo?a=b", "foo#x", "foo%2Fbar", "", "a".repeat(33))
            .forEach { assertNull(buildTmePreviewUrl(it, null, null), it) }
    }
}
