package dev.lyo.hortay.ui.media

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Composition local for the process-wide [CustomEmojiAnimator]. Provided once at the
 * top of the UI tree (next to [LocalCustomEmoji] / [LocalMediaCache]); used by
 * [InlineCustomEmojiRenderer] to acquire shared playback handles.
 *
 * `staticCompositionLocalOf` is right here — the animator is a process-singleton that
 * never changes mid-session, so subscribers don't need recomposition on provider swap.
 */
val LocalCustomEmojiAnimator = staticCompositionLocalOf<CustomEmojiAnimator> {
    error(
        "CustomEmojiAnimator not provided. Wrap your composables in CompositionLocalProvider " +
            "with LocalCustomEmojiAnimator set to AppGraph.customEmojiAnimator.",
    )
}
