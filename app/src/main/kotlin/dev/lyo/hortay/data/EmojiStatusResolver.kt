package dev.lyo.hortay.data

import org.drinkless.tdlib.TdApi

/**
 * Resolve a [TdApi.EmojiStatus] (set on `User.emojiStatus` / `Chat.emojiStatus`) into
 * the custom-emoji id we should render as the status badge. Returns null when:
 *
 *   • the status is absent (the common case — most users don't pick a status),
 *   • the status type is `EmojiStatusTypeUpgradedGift` (NFT collectible; the
 *     gift-sticker renderer isn't bundled, so the UI falls back to the gold
 *     premium star — matching how older Telegram clients render unknown
 *     statuses),
 *   • the stored `expirationDate` is already in the past (TDLib normally clears
 *     these itself via `UpdateUser`, but the guard protects us from acting on a
 *     stale snapshot read from cache).
 *
 * Pure TDLib decoding — no UI dependency. Both the mapper layer (comments,
 * timeline posts) and the user-profile repository call this so every surface
 * applies the same expiration rule.
 */
fun resolveEmojiStatusId(status: TdApi.EmojiStatus?): Long? {
    val custom = status?.type as? TdApi.EmojiStatusTypeCustomEmoji ?: return null
    val exp = status.expirationDate
    if (exp != 0 && exp.toLong() * 1000L <= System.currentTimeMillis()) return null
    return custom.customEmojiId
}
