package dev.lyo.hortay.data.subscriptions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SubscriptionListFormatTest {

    private fun names(text: String) = SubscriptionListFormat.parse(text).usernames

    @Test
    fun `reads every supported form out of mixed text in order of appearance`() {
        val text = """
            Good reads: https://t.me/durov and t.me/s/telegram (web preview),
            also @TechNews, a post link http://t.me/bbcnews/12345,
            telegram.me/oldstyle and tg://resolve?domain=deeplinked&post=4
        """.trimIndent()
        assertEquals(
            listOf("durov", "telegram", "technews", "bbcnews", "oldstyle", "deeplinked"),
            names(text),
        )
    }

    @Test
    fun `deduplicates case-insensitively across forms`() {
        val text = "https://t.me/Durov\n@durov\nT.ME/DUROV\ntg://resolve?domain=durov"
        assertEquals(listOf("durov"), names(text))
    }

    @Test
    fun `ignores invite links, folders, proxies and other service paths`() {
        val text = """
            https://t.me/+AbCdEf123
            https://t.me/joinchat/AAAAAEkk
            https://t.me/addlist/xyz
            https://t.me/proxy?server=1.2.3.4&port=443&secret=abc
            https://t.me/addstickers/pack
            https://t.me/c/123456/7
            https://t.me/real_channel
        """.trimIndent()
        assertEquals(listOf("real_channel"), names(text))
    }

    @Test
    fun `rejects malformed handles and e-mail addresses`() {
        val text = "mail me at someone@example.com, @1digitfirst, @a, t.me/x, " +
            "t.me/${"a".repeat(40)}, nott.me/fake"
        assertTrue(names(text).isEmpty())
    }

    @Test
    fun `bare words are not taken for usernames`() {
        assertTrue(names("durov\ntelegram\njust some prose").isEmpty())
    }

    @Test
    fun `a mention at the end of a sentence still counts`() {
        assertEquals(listOf("durov"), names("Follow @durov."))
    }

    @Test
    fun `caps the list and reports truncation`() {
        val text = (1..12).joinToString("\n") { "https://t.me/channel_$it" }
        val parsed = SubscriptionListFormat.parse(text, limit = 10)
        assertEquals(10, parsed.usernames.size)
        assertEquals("channel_1", parsed.usernames.first())
        assertTrue(parsed.truncated)
        assertFalse(SubscriptionListFormat.parse(text, limit = 12).truncated)
    }

    @Test
    fun `format writes one sorted lowercase link per line`() {
        assertEquals(
            "https://t.me/alpha\nhttps://t.me/durov\n",
            SubscriptionListFormat.format(listOf("Durov", "@alpha", "durov", " ")),
        )
    }

    @Test
    fun `format output parses back to the same list`() {
        val list = listOf("alpha", "beta_2", "durov")
        assertEquals(list, names(SubscriptionListFormat.format(list)))
    }

    @Test
    fun `export file name carries the ISO date`() {
        assertEquals(
            "hortay-channels-2026-11-27.txt",
            SubscriptionListFormat.exportFileName(LocalDate.of(2026, 11, 27)),
        )
    }
}
