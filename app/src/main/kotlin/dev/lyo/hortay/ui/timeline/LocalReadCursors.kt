package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.staticCompositionLocalOf
import dev.lyo.hortay.data.EmptyReadCursors
import dev.lyo.hortay.data.ReadCursors

/**
 * Process-wide cursor map (chatId → lastReadInboxMessageId) propagated via
 * composition so PostCard can derive the per-card unread flag without each
 * card subscribing to a global flow. Scaffold-level providers
 * (MainScaffold for TDLib mode, WebModeScaffold for guest mode) install the
 * appropriate snapshot of the map; the default is empty, which makes every
 * post render as read — the safe fallback for unauthenticated surfaces
 * (auth screen, link-preview dialog) and for the cold-start race where the
 * scaffold hasn't yet collected its first emission.
 *
 * `staticCompositionLocalOf` matches [dev.lyo.hortay.ui.media.LocalIsCenteredItem]:
 * the local's *identity* is stable for the lifetime of the scaffold; the cursor
 * map itself is a [kotlinx.collections.immutable.PersistentMap], so
 * structural-sharing on `put` keeps O(log N) write cost and Compose's snapshot
 * tracking can scope recompositions to the readers whose cursor actually changed.
 */
val LocalReadCursors = staticCompositionLocalOf<ReadCursors> { EmptyReadCursors }
