package dev.lyo.hortay.data.nav

import androidx.compose.runtime.Immutable
import androidx.navigation3.runtime.NavKey
import dev.lyo.hortay.data.TimelinePost

/**
 * Navigation 3 back-stack keys, consumed by `NavDisplay`'s `entryProvider` in
 * [dev.lyo.hortay.ui.main.MainScaffold] (authenticated) and
 * [dev.lyo.hortay.ui.web.WebModeScaffold] (guest).
 *
 * **Deliberately NOT `@Serializable`.** The back stack is process-singleton state on
 * `AppGraph` and is intentionally *not* restored across process death — a killed process
 * means the user abandoned the drill path, and cold launch must land on the Feed top. Because
 * we drive `NavDisplay` from a plain graph-scoped `SnapshotStateList<NavKey>` (not the saveable
 * `rememberNavBackStack`), serialization is unnecessary — which lets [CommentsKey] carry a live
 * [TimelinePost] directly, preserving the guest-mode frozen-anchor path with zero change.
 *
 * **Identity of duplicate keys (known limitation).** NavDisplay keys per-entry saveable state
 * and ViewModelStore on the key itself, so two value-equal keys on the stack share one state
 * bag. The common re-open case — tapping the channel that already sits directly below the top —
 * never stacks a duplicate: `safelyOpenChannel` collapses it to a pop. A genuine re-nest of the
 * *same* channel via a different path (A → comments → B → A again) is the only way to land two
 * equal [ChannelKey]s on the stack, and it would share scroll/VM state across the two. The old
 * model avoided this with a per-instance UUID `entryId`; the canonical Nav3 fix is a per-entry
 * `contentKey` (not yet wired) — tracked as a follow-up, the case is rare and non-crashing.
 */
@Immutable
sealed interface AppNavKey : NavKey

/** Tab host (Feed/Channels/Saved/Profile). Always the back-stack root; details push on top. */
@Immutable
data object HomeKey : AppNavKey

/**
 * Single-channel drill. `scrollToMessageId` lets a deep link push the channel pre-targeted at
 * a specific post (consumed once on first composition).
 */
@Immutable
data class ChannelKey(
    val chatId: Long,
    val scrollToMessageId: Long? = null,
) : AppNavKey

/**
 * Comments thread anchored at [anchor]. Carries the live [TimelinePost] (see file KDoc on why
 * serialization isn't needed); the screen prefers the live `PostsRepository` entry and falls
 * back to this snapshot for evicted / guest-mode posts.
 */
@Immutable
data class CommentsKey(
    val anchor: TimelinePost,
    /**
     * Hero-open anchor: the post's absolute on-screen Y captured in the feed. Retained through
     * the NavDisplay cutover so behaviour is unchanged; the shared-element container transform
     * replaces (and deletes) it in the morph stage. `null` for a normal/deep-link open.
     */
    val heroAnchorY: Int? = null,
) : AppNavKey

/** Archive browser. */
@Immutable
data object ArchiveKey : AppNavKey

/** Archive settings (reached from Settings → Profile). */
@Immutable
data object ArchiveSettingsKey : AppNavKey

/**
 * Guest-mode single-channel drill, identified by `t.me/s/` handle (guest mode mints no TDLib
 * chatIds). Only consumed by the guest scaffold's `NavDisplay`.
 */
@Immutable
data class WebChannelKey(
    val username: String,
) : AppNavKey
