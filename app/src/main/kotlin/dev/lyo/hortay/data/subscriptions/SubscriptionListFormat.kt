package dev.lyo.hortay.data.subscriptions

import dev.lyo.hortay.data.web.parseUsernameFromInput
import java.time.LocalDate

/**
 * The portable channel-list format behind import / export: plain UTF-8 text, one
 * `https://t.me/<username>` per line. Human-readable on purpose — the same text can be
 * saved as a file, pasted into a Telegram chat or into another reader, and read back.
 *
 * [parse] is deliberately lenient so it also accepts lists that were never produced by
 * [format]: any text is scanned for `t.me/<name>`, `t.me/s/<name>`, `telegram.me/<name>`,
 * `@name` and `tg://resolve?domain=<name>`, every hit is validated by
 * [parseUsernameFromInput] (the single username rule the Add-channel sheet already uses),
 * and the result is deduplicated case-insensitively in order of first appearance. Bare
 * words are NOT read as usernames — in arbitrary shared text they are almost always prose.
 *
 * Links that are not a public channel handle are skipped: invite links (`t.me/+…`,
 * `t.me/joinchat/…`) carry no stable username, and service paths such as `t.me/addlist`,
 * `t.me/proxy` or `t.me/addstickers` are Telegram features, not channels ([RESERVED_PATHS]).
 */
object SubscriptionListFormat {

    /** Most channels one import picks up; the rest of a longer list is reported as cut off. */
    const val MAX_IMPORT_CHANNELS = 500

    /** Most characters read from an imported file (about 1 MB) — a list of 500 links is about 15 KB. */
    const val MAX_IMPORT_CHARS = 1024 * 1024

    /** MIME type of both the shared text and the saved file. */
    const val MIME_TYPE = "text/plain"

    /** One link per line, lowercase, sorted and deduplicated, ending with a newline. */
    fun format(usernames: Collection<String>): String =
        usernames.asSequence()
            .map { it.trim().removePrefix("@").lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .joinToString(separator = "") { "$LINK_PREFIX$it\n" }

    /** Default file name for a saved list, e.g. `hortay-channels-2026-11-27.txt`. */
    fun exportFileName(date: LocalDate): String = "hortay-channels-$date.txt"

    /**
     * Every distinct channel username found in [text], lowercase, in order of first
     * appearance, at most [limit] of them; [Parsed.truncated] says more were found.
     */
    fun parse(text: String, limit: Int = MAX_IMPORT_CHANNELS): Parsed {
        val hits = ArrayList<Pair<Int, String>>()
        for (match in LINK_RX.findAll(text)) {
            val name = match.groupValues[1]
            if (name.lowercase() in RESERVED_PATHS) continue
            parseUsernameFromInput("t.me/$name")?.let { hits += match.range.first to it }
        }
        for (match in MENTION_RX.findAll(text)) {
            parseUsernameFromInput(match.groupValues[1])?.let { hits += match.range.first to it }
        }
        for (match in RESOLVE_RX.findAll(text)) {
            // parseUsernameFromInput's tg:// pattern is case-sensitive on the scheme.
            val canonical = "tg://resolve?" + match.groupValues[1]
            parseUsernameFromInput(canonical)?.let { hits += match.range.first to it }
        }
        hits.sortBy { it.first }
        val unique = hits.map { it.second }.distinct()
        return Parsed(unique.take(limit), truncated = unique.size > limit)
    }

    /** Result of [parse]. */
    data class Parsed(val usernames: List<String>, val truncated: Boolean)

    private const val LINK_PREFIX = "https://t.me/"

    /**
     * `t.me/<name>` with an optional scheme, `www.` and the `s/` web-preview prefix. The
     * name group stops at the first character a username can't hold, so `t.me/+invite`
     * never matches and `t.me/durov/123` (a post link) yields `durov`. The look-behind
     * keeps `t.me` from matching inside a longer host such as `xt.me`.
     */
    private val LINK_RX = Regex(
        """(?<![A-Za-z0-9_.\-])(?:https?://)?(?:www\.)?(?:t|telegram)\.me/(?:s/)?([A-Za-z0-9_]+)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * `@name` that is not part of an e-mail address or a URL path, and not cut out of a
     * longer word: no username character right before the `@` or right after the name.
     */
    private val MENTION_RX = Regex("""(?<![A-Za-z0-9_.@/])@([A-Za-z][A-Za-z0-9_]{1,31})(?![A-Za-z0-9_])""")

    /** `tg://resolve?…` up to the next whitespace; the query is validated downstream. */
    private val RESOLVE_RX = Regex("""tg://resolve\?(\S+)""", RegexOption.IGNORE_CASE)

    /** First path segments of `t.me` links that are Telegram features, not usernames. */
    private val RESERVED_PATHS = setOf(
        "joinchat", "addlist", "addstickers", "addemoji", "addtheme", "proxy", "socks",
        "share", "setlanguage", "login", "iv", "bg", "invoice", "boost", "confirmphone",
        "contact", "giftcode", "nft", "msg", "msg_url", "c", "s", "m", "a", "k", "z",
        "dl", "auth", "premium_offer", "premium_multigift", "stars_topup", "addpremium",
    )
}
