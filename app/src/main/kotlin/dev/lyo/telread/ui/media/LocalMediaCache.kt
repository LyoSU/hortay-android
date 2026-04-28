package dev.lyo.telread.ui.media

import androidx.compose.runtime.staticCompositionLocalOf
import dev.lyo.telread.data.MediaCache

val LocalMediaCache = staticCompositionLocalOf<MediaCache> {
    error("MediaCache was not provided. Wrap your composition in CompositionLocalProvider(LocalMediaCache provides …).")
}
