package dev.lyo.hortay.data.record

import java.security.SecureRandom

/**
 * Anonymises a session while it is being recorded, keeping everything the app's logic
 * depends on:
 *
 * - **Text** — every letter and digit is replaced by a pseudo-random one of the same class
 *   and UTF-16 width; whitespace, punctuation and emoji stay. Length is preserved, so
 *   entity offsets, line breaks and custom-emoji placeholders still line up. Equal strings
 *   scramble to equal strings (usernames stay usable as keys); the mapping is keyed by a
 *   random [salt] that is never written anywhere.
 * - **Chat / user / group ids** — replaced by small sequential ones through a consistent
 *   mapping that preserves the id-space relations (a channel's chat id is still
 *   `-1000000000000 - supergroupId`, a private chat's id is still the user id).
 * - **Binary blobs** (minithumbnails, waveforms, keys) — zero-filled, same length.
 *
 * Left as is: message ids (the app relies on their arithmetic), dates, counters, sticker
 * and custom-emoji ids, and the few strings that are protocol rather than content
 * ([KEPT_FIELDS], error messages). Dates and counters can still fingerprint a public
 * channel — read a recording through before sharing it.
 *
 * Not thread-safe: one recording, one writer.
 */
class TdRedactor(private val salt: Long = SecureRandom().nextLong()) : TdFieldFilter {
    private val users = HashMap<Long, Long>()
    private val supergroups = HashMap<Long, Long>()
    private val basicGroups = HashMap<Long, Long>()

    override fun string(owner: Class<*>, field: String, value: String): String =
        if (field in KEPT_FIELDS || owner.simpleName == "Error") value else scramble(value)

    override fun bytes(owner: Class<*>, field: String, value: ByteArray): ByteArray = ByteArray(value.size)

    override fun long(owner: Class<*>, field: String, value: Long): Long {
        val name = field.lowercase().removeSuffix("s")
        return when {
            field == "id" -> when (owner.simpleName) {
                "Chat" -> chat(value)
                "User" -> mapped(users, value)
                "Supergroup" -> mapped(supergroups, value)
                "BasicGroup" -> mapped(basicGroups, value)
                else -> value
            }
            name.endsWith("chatid") -> chat(value)
            name.endsWith("userid") -> mapped(users, value)
            name.endsWith("supergroupid") -> mapped(supergroups, value)
            name.endsWith("basicgroupid") -> mapped(basicGroups, value)
            else -> value
        }
    }

    private fun chat(id: Long): Long = when {
        id > 0 -> mapped(users, id)
        id in (SUPERGROUP_CHAT_FLOOR + 1)..<-CHANNEL_OFFSET -> -CHANNEL_OFFSET - mapped(supergroups, -id - CHANNEL_OFFSET)
        id in -CHANNEL_OFFSET..<0 -> -mapped(basicGroups, -id)
        else -> id
    }

    private fun mapped(table: HashMap<Long, Long>, id: Long): Long =
        if (id == 0L) 0L else table.getOrPut(id) { FIRST_ID + table.size }

    private fun scramble(value: String): String {
        var state = seed(value)
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            val width = Character.charCount(cp)
            val base = when {
                Character.isDigit(cp) -> '0' to DIGITS
                Character.isUpperCase(cp) -> 'A' to LETTERS
                Character.isLetter(cp) -> 'a' to LETTERS
                else -> null
            }
            if (base == null) {
                out.append(value, i, i + width)
            } else {
                repeat(width) {
                    state = next(state)
                    out.append(base.first + ((state ushr HIGH_BITS_SHIFT) % base.second).toInt())
                }
            }
            i += width
        }
        return out.toString()
    }

    /** FNV-1a over the string, keyed by the salt. */
    private fun seed(value: String): Long {
        var h = FNV_OFFSET xor salt
        for (ch in value) h = (h xor ch.code.toLong()) * FNV_PRIME
        return h or 1L
    }

    /** xorshift64. */
    private fun next(state: Long): Long {
        var s = state
        s = s xor (s shl 13)
        s = s xor (s ushr 7)
        s = s xor (s shl 17)
        return s
    }

    private companion object {
        /** Protocol strings: values the app branches on, never user content. */
        val KEPT_FIELDS = setOf("mimeType", "emoji", "languageCode", "language")

        const val CHANNEL_OFFSET = 1_000_000_000_000L

        /** Below this the id space belongs to secret chats, which are left untouched. */
        const val SUPERGROUP_CHAT_FLOOR = -1_997_852_516_353L
        const val FIRST_ID = 1_001L
        const val DIGITS = 10L
        const val LETTERS = 26L
        const val HIGH_BITS_SHIFT = 33
        const val FNV_OFFSET = -0x340d631b7bdddcdbL
        const val FNV_PRIME = 0x100000001b3L
    }
}
