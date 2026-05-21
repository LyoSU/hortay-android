package dev.lyo.hortay.data

import org.drinkless.tdlib.TdApi

/**
 * Resolve a [TdApi.EmojiStatus] (set on `User.emojiStatus` / `Chat.emojiStatus`) into
 * the custom-emoji id we should render as the status badge. Returns null when:
 *
 *   • the status is absent (the user never picked one),
 *   • the stored `expirationDate` is already in the past (TDLib normally clears
 *     these itself via `UpdateUser`, but the guard protects us from acting on a
 *     stale snapshot read from cache).
 *
 * Both supported status variants resolve to one custom-emoji id:
 *   • [TdApi.EmojiStatusTypeCustomEmoji] — the user picked a regular animated
 *     emoji; its `customEmojiId` is used directly.
 *   • [TdApi.EmojiStatusTypeUpgradedGift] — the user picked an NFT/collectible
 *     gift as their status. The gift carries a `modelCustomEmojiId` (the
 *     primary animated emoji of the gift's model) plus a backdrop and a smaller
 *     symbol overlay. For a 14–18 dp inline badge the model alone is the right
 *     visual — same trade-off the official Telegram clients make outside the
 *     dedicated gift-profile screen. Backdrop + symbol composition stays for a
 *     future profile-header redesign; not relevant for the inline badge.
 *
 * Pure TDLib decoding — no UI dependency. Both the mapper layer (comments,
 * timeline posts) and the user-profile repository call this so every surface
 * applies the same expiration rule.
 */
fun resolveEmojiStatusId(status: TdApi.EmojiStatus?): Long? {
    if (status == null) return null
    val exp = status.expirationDate
    if (exp != 0 && exp.toLong() * 1000L <= System.currentTimeMillis()) return null
    return when (val type = status.type) {
        is TdApi.EmojiStatusTypeCustomEmoji -> type.customEmojiId
        is TdApi.EmojiStatusTypeUpgradedGift -> type.modelCustomEmojiId
        else -> null
    }
}
