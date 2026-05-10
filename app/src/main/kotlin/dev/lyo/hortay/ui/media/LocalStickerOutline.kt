package dev.lyo.hortay.ui.media

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Composition local for [StickerOutlineStore]. Provided once at the top of the UI tree
 * (next to [LocalMediaCache] / [LocalCustomEmoji]); used by [StickerView] /
 * [CustomEmojiInlineView] to fetch the cached vector silhouette of an in-flight sticker
 * before its thumbnail / playback file land.
 *
 * `staticCompositionLocalOf` matches the rest of the heavy-singleton injection in this
 * package — the store is process-scoped and never reassigned during a session, so
 * subscribers don't need recomposition on provider swap.
 */
val LocalStickerOutline = staticCompositionLocalOf<StickerOutlineStore> {
    error(
        "StickerOutlineStore not provided. Wrap your composables in CompositionLocalProvider " +
            "with LocalStickerOutline set to AppGraph.stickerOutline.",
    )
}
