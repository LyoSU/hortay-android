package dev.lyo.hortay.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.CustomEmojiInlineView
import dev.lyo.hortay.ui.theme.PremiumGold

/**
 * The little badge that sits next to a user's display name across the app
 * (settings hero, user-profile sheet, comment rows). Mirrors Telegram-Android's
 * resolution order:
 *
 *   1. Custom emoji status — when the user picked an emoji as their status,
 *      its animated/static sticker replaces the gold star. We honour TDLib's
 *      `EmojiStatus.expirationDate`: a status with a past expiration is
 *      treated as cleared and we fall through to the star (TDLib normally
 *      clears it itself via `UpdateUser`, but the check guards against stale
 *      snapshots).
 *   2. Plain Premium — gold filled star (Material Symbols Rounded, FILL=1).
 *   3. Neither — render nothing.
 *
 * Monochrome status emojis (`CustomEmojiSticker.needsRepainting`) are tinted
 * with [PremiumGold] so the brand colour reads even when the user chose a
 * plain glyph. Animated TGS/WebM custom-status emojis route through the
 * shared [dev.lyo.hortay.ui.media.CustomEmojiAnimator] so N instances on the
 * same screen (a thread full of premium commenters) share one Lottie session.
 *
 * Both regular emoji statuses and NFT/upgraded-gift statuses are supported —
 * see [dev.lyo.hortay.data.resolveEmojiStatusId] for the decoding (gifts
 * collapse to their `modelCustomEmojiId`; backdrop + symbol overlay is a
 * future profile-header concern, not relevant at inline-badge size).
 */
@Composable
fun PremiumStatusBadge(
    isPremium: Boolean,
    emojiStatusId: Long?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.cd_premium_badge)
    when {
        emojiStatusId != null -> CustomEmojiInlineView(
            customEmojiId = emojiStatusId,
            contentDescription = description,
            tintColor = PremiumGold,
            modifier = modifier.size(size),
        )
        isPremium -> Symbol(
            name = "star",
            filled = true,
            contentDescription = description,
            tint = PremiumGold,
            size = size,
            modifier = modifier,
        )
    }
}

