package dev.lyo.hortay.ui.media

import androidx.compose.runtime.staticCompositionLocalOf
import dev.lyo.hortay.data.MediaCache

val LocalMediaCache = staticCompositionLocalOf<MediaCache> {
    error("MediaCache was not provided. Wrap your composition in CompositionLocalProvider(LocalMediaCache provides …).")
}
