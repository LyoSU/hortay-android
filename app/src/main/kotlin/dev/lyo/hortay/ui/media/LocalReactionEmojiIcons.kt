package dev.lyo.hortay.ui.media

import androidx.compose.runtime.compositionLocalOf
import dev.lyo.hortay.data.ReactionEmojiIconRepository

/**
 * Resolver for Telegram's standard-reaction artwork, or null when reaction emoji should
 * draw with the device's emoji font: guest mode and the auth screen (no TDLib session),
 * and the user's [dev.lyo.hortay.data.EmojiStyle.System] choice. Provided by
 * `MainActivity` around the authenticated UI only.
 *
 * `compositionLocalOf` (not static) on purpose: the provided value flips between the
 * repository and null when the user changes the emoji style, and only the reaction
 * glyphs that read it should recompose — not the whole authenticated tree.
 */
val LocalReactionEmojiIcons = compositionLocalOf<ReactionEmojiIconRepository?> { null }
