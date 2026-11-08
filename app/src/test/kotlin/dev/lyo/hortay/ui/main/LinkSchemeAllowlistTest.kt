package dev.lyo.hortay.ui.main

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LinkSchemeAllowlistTest {

    @Test
    fun `web, telegram, mail and phone schemes are allowed in any case`() {
        listOf("http", "https", "HTTPS", "tg", "mailto", "tel").forEach {
            assertTrue(isAllowedLinkScheme(it), it)
        }
    }

    @Test
    fun `script, intent, file and missing schemes are rejected`() {
        listOf("javascript", "JavaScript", "intent", "file", "content", "data", "", null).forEach {
            assertFalse(isAllowedLinkScheme(it), "$it")
        }
    }
}
