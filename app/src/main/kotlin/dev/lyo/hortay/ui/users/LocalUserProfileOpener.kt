package dev.lyo.hortay.ui.users

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Functional handle that opens the [UserProfileSheet] for a given TDLib user id.
 * The seeded callback is provided once at the [dev.lyo.hortay.ui.main.MainScaffold]
 * level — the actual sheet visibility state lives there alongside the channel-drill
 * stack, so the personal-channel "open in app" row reuses the existing push path.
 *
 * Why a CompositionLocal and not a parameter on every screen / row:
 *   The trigger surfaces live on ~5 unrelated surfaces (comment header, PostCard
 *   author row, forward-from-user chip, in-text user mention, channel context
 *   subtitle). Threading the callback through every composable param would be a
 *   constructor explosion of the same kind that drove the CompositionLocal-based
 *   media-cache / read-cursor injection in CLAUDE.md. Same mitigation applies.
 *
 * Default is a no-op so screens rendered outside MainScaffold (auth, previews,
 * tests) don't crash on tap — the affordance just becomes inert.
 */
val LocalUserProfileOpener = staticCompositionLocalOf<UserProfileOpener> {
    UserProfileOpener { _ -> /* no-op default; MainScaffold overrides */ }
}

/** Functional type so we can keep stable identity for equality without lambda boxing. */
fun interface UserProfileOpener {
    fun open(userId: Long)
}
