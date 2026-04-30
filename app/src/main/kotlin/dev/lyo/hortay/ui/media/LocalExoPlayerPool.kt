package dev.lyo.hortay.ui.media

import androidx.compose.runtime.staticCompositionLocalOf

val LocalExoPlayerPool = staticCompositionLocalOf<ExoPlayerPool> {
    error("ExoPlayerPool was not provided. Wrap your composition in CompositionLocalProvider(LocalExoPlayerPool provides …).")
}
