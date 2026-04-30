package dev.lyo.hortay.data

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Composition-local for the app-wide [TdSender], so deep-tree composables (notably
 * [dev.lyo.hortay.ui.media.TdVideoPlayer], which has to construct a Media3 DataSource
 * factory) can reach it without threading the dependency through every intermediate
 * composable. Provided once at the root in [dev.lyo.hortay.MainActivity].
 */
val LocalTdSender = staticCompositionLocalOf<TdSender> {
    error("TdSender was not provided. Wrap your composition in CompositionLocalProvider(LocalTdSender provides …).")
}
