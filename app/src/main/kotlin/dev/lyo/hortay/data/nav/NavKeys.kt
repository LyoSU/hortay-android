package dev.lyo.hortay.data.nav

import androidx.compose.runtime.Immutable
import androidx.navigation3.runtime.NavKey
import dev.lyo.hortay.data.TimelinePost

/**
 * Navigation 3 back-stack keys — the typed replacement for the hand-rolled
 * `NavEntry` sealed interface (see the approved migration plan).
 *
 * Each key is a [NavKey] consumed by `NavDisplay`'s `entryProvider`. Payloads mirror the
 * old `NavEntry` variants 1:1 so the cutover is mechanical.
 *
 * **Deliberately NOT `@Serializable`.** The back stack is process-singleton state on
 * `AppGraph` and is intentionally *not* restored across process death — a killed process
 * means the user abandoned the drill path, and cold launch must land on the Feed top (the
 * same contract the old `NavStack` KDoc documents). Because we drive `NavDisplay` from a
 * plain graph-scoped `SnapshotStateList<NavKey>` (not the saveable `rememberNavBackStack`),
 * serialization is unnecessary — which lets [CommentsKey] carry a live [TimelinePost] exactly
 * as the old entry did, preserving the guest-mode anchor path with zero change.
 *
 * **Identity of duplicate keys.** The old model minted a per-instance UUID `entryId` so that
 * pushing the same channel twice produced two independent screens (channel → comments → the
 * same channel again — the unlimited-nesting Telegram pattern). Value-equal data-class keys
 * collide under Nav3's default identity, so duplicate-capable keys ([ChannelKey], [CommentsKey])
 * are disambiguated at the `NavDisplay` call site via a per-entry `contentKey` rather than by
 * baking a nonce into the key itself.
 */
@Immutable
sealed interface AppNavKey : NavKey

/** Tab host (Feed/Channels/Saved/Profile). Always the back-stack root; details push on top. */
@Immutable
data object HomeKey : AppNavKey

/**
 * Single-channel drill. `scrollToMessageId` lets a deep link push the channel pre-targeted at
 * a specific post (consumed once on first composition). Mirror of `NavEntry.Channel`.
 */
@Immutable
data class ChannelKey(
    val chatId: Long,
    val scrollToMessageId: Long? = null,
) : AppNavKey

/**
 * Comments thread anchored at [anchor]. Carries the live [TimelinePost] (see file KDoc on why
 * serialization isn't needed); the screen prefers the live `PostsRepository` entry and falls
 * back to this snapshot for evicted / guest-mode posts. Mirror of `NavEntry.Comments`.
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

/** Archive browser. Mirror of `NavEntry.Archive`. */
@Immutable
data object ArchiveKey : AppNavKey

/** Archive settings (reached from Settings → Profile). Mirror of `NavEntry.ArchiveSettings`. */
@Immutable
data object ArchiveSettingsKey : AppNavKey

/**
 * Guest-mode single-channel drill, identified by `t.me/s/` handle (guest mode mints no TDLib
 * chatIds). Only consumed by the guest scaffold's `NavDisplay`. Mirror of `NavEntry.WebChannel`.
 */
@Immutable
data class WebChannelKey(
    val username: String,
) : AppNavKey
