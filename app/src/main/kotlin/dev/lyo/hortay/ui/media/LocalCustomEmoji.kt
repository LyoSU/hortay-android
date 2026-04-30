package dev.lyo.hortay.ui.media

import androidx.compose.runtime.staticCompositionLocalOf
import dev.lyo.hortay.data.CustomEmojiRepository

/**
 * Composition local for [CustomEmojiRepository]. Provided once at the top of the UI tree
 * (next to [LocalMediaCache]); used by [CustomEmojiInlineView] / [StickerView] to look up
 * sticker descriptors for `customEmojiId`s.
 *
 * `staticCompositionLocalOf` is the right pick here — the repo is a process-singleton and
 * never changes mid-session, so subscribers do not need recomposition on provider swap.
 */
val LocalCustomEmoji = staticCompositionLocalOf<CustomEmojiRepository> {
    error(
        "CustomEmojiRepository not provided. Wrap your composables in CompositionLocalProvider " +
            "with LocalCustomEmoji set to AppGraph.customEmoji.",
    )
}
