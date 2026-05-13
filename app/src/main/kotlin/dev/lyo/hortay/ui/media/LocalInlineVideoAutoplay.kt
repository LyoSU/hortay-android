package dev.lyo.hortay.ui.media

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * User's master "play short videos silently in the feed" preference, propagated via
 * composition so [dev.lyo.hortay.ui.timeline.PostBody] doesn't need to thread the flag
 * through five nested composables (`PostCard → PostBody → AlbumBlock → SingleMedia →
 * MediaWithSpoiler`). When `false`, every video item keeps the static poster + play
 * badge until the user opens it explicitly — same UX as before the feature existed.
 *
 * Wired in [dev.lyo.hortay.ui.main.MainScaffold] and
 * [dev.lyo.hortay.ui.web.WebModeScaffold] from
 * [dev.lyo.hortay.data.SettingsStore.inlineVideoAutoplay]. Default `true` (provider
 * not installed) matches the shipped behaviour for the few surfaces that compose
 * media outside the scaffold tree.
 *
 * `staticCompositionLocalOf` matches the rest of the `Local*` family in this
 * package: the value is read at the leaf, changes are infrequent, and we'd rather
 * skip Compose's per-reader bookkeeping that the non-static variant carries.
 */
val LocalInlineVideoAutoplay = staticCompositionLocalOf { true }
