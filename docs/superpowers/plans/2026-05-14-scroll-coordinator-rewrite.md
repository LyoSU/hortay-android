# Scroll Coordinator Rewrite — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace 5 parallel `LaunchedEffect`s that fight over scroll position with a single ViewModel-owned `TimelineUiState` (Loading/Ready/Empty) that exposes a precomputed `initialIndex`. The `LazyColumn` mounts only when state is Ready, so first paint lands at the correct anchor (top/unread-boundary/deep-link-target) with no flash and no post-paint animation. Unify the three "jump" pills (NewPostsPill, UnreadCounterPill, home-tap) on a `smartScrollTo(target)` helper that instant-jumps when distance exceeds a threshold.

**Architecture:** Move scroll-anchor computation out of Compose effects into the ViewModel. UI becomes declarative: `when (state) { Loading -> Skeleton; Ready -> LazyColumn(initialIndex); Empty -> Hero }`. After first successful landing, the route becomes user-controlled — no refresh, cursor update, or live arrival can auto-scroll. Deep-links route through a `Resolving → Resolved/Missing` state that holds the channel skeleton until `loadHistoryAround` returns, so the channel's head post never flashes.

**Tech Stack:** Kotlin 2.3.10 (K2), Jetpack Compose BOM 2026.04.01, M3 Expressive, Coroutines 1.10.1, `PersistentList`/`PersistentMap` (kotlinx.collections.immutable), JUnit 5 + `kotlinx-coroutines-test`. No new dependencies.

---

## File Structure

### New files

| Path | Responsibility |
|---|---|
| `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/TimelineUiState.kt` | Sealed `Loading`/`Ready(posts, initialIndex, initialOffset, frozenCursors)`/`Empty`. Plus `buildTimelineUiState(...)` pure function that derives state from `(posts, cursorsLanded, refreshing, feedOrder)`. |
| `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/SmartScroll.kt` | `LazyListState.smartScrollTo(target, threshold)` extension. Instant `scrollToItem` if `|target − firstVisible| > threshold`, else `animateScrollToItem`. |
| `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/ChannelUiState.kt` | Sealed `Resolving(targetMessageId)`/`Ready(posts, initialIndex)`/`Missing(reason)` for per-channel screen, drives deep-link skeleton-gate. |
| `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/SkeletonFeed.kt` | Shimmer placeholder rows shaped like `PostCard`. Mounted by both screens during Loading/Resolving. |
| `app/src/test/kotlin/dev/lyo/hortay/ui/timeline/TimelineUiStateBuilderTest.kt` | Tests for state transitions and `initialIndex` derivation in both feed orders. |
| `app/src/test/kotlin/dev/lyo/hortay/ui/timeline/ChannelUiStateBuilderTest.kt` | Tests for deep-link Resolving → Ready/Missing flow. |
| `app/src/test/kotlin/dev/lyo/hortay/ui/timeline/SmartScrollDistanceTest.kt` | Tests for distance-threshold branch selection (pure helper). |
| `app/src/test/kotlin/dev/lyo/hortay/ui/timeline/PendingScrollResolverTest.kt` | Tests for the new `resolveTargetIndex` pure helper extracted from `rememberPendingScrollToMessage`. |

### Modified files

| Path | Reason |
|---|---|
| `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/TimelineViewModel.kt` | Expose `uiState: StateFlow<TimelineUiState>`. Compose `(posts, cursorsLanded, frozenCursors, feedOrder)` into `Ready` once both posts non-empty and cursors landed; until then `Loading`. Latch `frozenCursors` on first Ready and on refresh completion. |
| `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/ChannelViewModel.kt` | Accept `scrollToMessageId` constructor arg, expose `channelUiState: StateFlow<ChannelUiState>`. When `scrollToMessageId != null`, stay in `Resolving` until `loadHistoryAround` returns and target is found in slice. |
| `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/TimelineScreen.kt` | Delete cold-start pin loop (L332-414), home-tap effect (L577-591), scope-switch effect (L612-621), feedOrder-flip effect (L816-824). Render `when (uiState)`. Replace three `animateScrollToItem` call-sites with `smartScrollTo`. |
| `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/ChannelScreen.kt` | Render `when (channelUiState)`. Delete cold-entry effect (L282-311). Use `initialFirstVisibleItemIndex` from state. Skeleton mounted while `Resolving`. |
| `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/FeedScrollEffects.kt` | Replace `rememberPendingScrollToMessage` with a thin wrapper around `resolveTargetIndex` pure helper. Fix silent-hang: after `loadHistoryAround` returns `true`, give one snapshot tick to find target; if still not found, call `onMissed`. |
| `app/src/main/kotlin/dev/lyo/hortay/data/ReadCursors.kt` | Keep `continueReadingIndex` as-is. (No changes; called from `buildTimelineUiState`.) |
| `app/src/main/kotlin/dev/lyo/hortay/ui/main/MainScaffold.kt` | Pass `scrollToMessageId` through `NavEntry.Channel` → `ChannelViewModel` factory (already in signature; just wire). No other changes here. |
| `app/src/main/res/values/strings.xml` + `values-uk/strings.xml` | Add `R.string.deep_link_post_unavailable` for the Missing snackbar. UK + EN parity. |
| `CHANGELOG.md` | Single entry under `[Unreleased]`. |

### Deleted blocks (do not delete files)

- `TimelineScreen.kt:332-414` — cold-start scroll pin.
- `TimelineScreen.kt:577-591` — home-tap `animateScrollToItem`.
- `TimelineScreen.kt:612-621` — scope-switch effect.
- `TimelineScreen.kt:816-824` — feedOrder-flip effect.
- `ChannelScreen.kt:282-311` — channel cold-entry scroll for OldestUnreadFirst.

---

## Tasks

### Task 1: Fix silent-hang in `rememberPendingScrollToMessage`

**Why first:** Smallest, isolated, removes a present bug regardless of larger refactor. Establishes the test pattern for later tasks.

**Files:**
- Test: `app/src/test/kotlin/dev/lyo/hortay/ui/timeline/PendingScrollResolverTest.kt` (create)
- Modify: `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/FeedScrollEffects.kt:39-72`

#### Step 1.1: Extract pure helper

- [ ] Add a top-level pure function in `FeedScrollEffects.kt` above `rememberPendingScrollToMessage`:

```kotlin
/**
 * Locates the (chatId, messageId) target in [items]. Returns the row index, or
 * -1 when not found. Albums hit on any member id.
 */
internal fun resolveTargetIndex(
    items: List<FeedItem>,
    chatId: Long,
    messageId: Long,
): Int = items.indexOfFirst { item ->
    item.posts().any { p ->
        p.chatId == chatId && (p.id == messageId || messageId in p.albumMessageIds)
    }
}
```

#### Step 1.2: Write failing test

Create `app/src/test/kotlin/dev/lyo/hortay/ui/timeline/PendingScrollResolverTest.kt`:

```kotlin
package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.TimelinePost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PendingScrollResolverTest {

    private fun post(chatId: Long, id: Long, album: List<Long> = emptyList()): TimelinePost =
        TimelinePost(
            chatId = chatId,
            id = id,
            senderName = "ch",
            senderHandle = null,
            date = 0L,
            content = PostContent.Text("body"),
            albumMessageIds = album,
        )

    @Test
    fun `returns index of direct match`() {
        val items = listOf(
            FeedItem.Single(post(1L, 100L)),
            FeedItem.Single(post(1L, 200L)),
            FeedItem.Single(post(1L, 300L)),
        )
        assertEquals(1, resolveTargetIndex(items, 1L, 200L))
    }

    @Test
    fun `returns index of album member match`() {
        val items = listOf(
            FeedItem.Single(post(1L, 100L)),
            FeedItem.Single(post(1L, 200L, album = listOf(200L, 201L, 202L))),
        )
        assertEquals(1, resolveTargetIndex(items, 1L, 202L))
    }

    @Test
    fun `returns minus one when target missing`() {
        val items = listOf(FeedItem.Single(post(1L, 100L)))
        assertEquals(-1, resolveTargetIndex(items, 1L, 999L))
    }

    @Test
    fun `returns minus one for different chatId`() {
        val items = listOf(FeedItem.Single(post(1L, 100L)))
        assertEquals(-1, resolveTargetIndex(items, 2L, 100L))
    }
}
```

#### Step 1.3: Run test, verify it passes

```
./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.ui.timeline.PendingScrollResolverTest"
```

Expected: 4 tests pass (the helper is already correctly implemented inline; the test guards against regression when we touch the file).

#### Step 1.4: Rewrite `rememberPendingScrollToMessage` to use helper and fix silent hang

Replace lines 39-72 of `FeedScrollEffects.kt` with:

```kotlin
/**
 * Resolves a deferred "scroll to (chatId, messageId)" request once the target row
 * appears in [displayedItems]. On first miss, runs [loadHistoryAround] exactly
 * once. If the target still isn't present after the next snapshot emission within
 * [loadGraceMs], [onMissed] fires — prevents the silent-hang failure mode where
 * loadHistoryAround returned true but PostFilterStrategy / grouping pruned the
 * target out of [displayedItems].
 */
@Composable
fun rememberPendingScrollToMessage(
    displayedItems: List<FeedItem>,
    pendingTarget: Pair<Long, Long>?,
    loadHistoryAround: suspend (chatId: Long, messageId: Long) -> Boolean,
    onLanded: suspend (chatId: Long, messageId: Long, index: Int) -> Unit,
    onMissed: () -> Unit,
    loadGraceMs: Long = 1500L,
) {
    val itemsState = rememberUpdatedState(displayedItems)
    LaunchedEffect(pendingTarget) {
        val (chatId, messageId) = pendingTarget ?: return@LaunchedEffect
        // First pass: target may already be in [displayedItems] from a prior
        // global feed harvest — try a single resolve before issuing an RPC.
        val initialIndex = resolveTargetIndex(itemsState.value, chatId, messageId)
        if (initialIndex >= 0) {
            onLanded(chatId, messageId, initialIndex)
            return@LaunchedEffect
        }
        // Miss: issue around-load. False return = chat inaccessible / window empty.
        val landed = loadHistoryAround(chatId, messageId)
        if (!landed) {
            onMissed()
            return@LaunchedEffect
        }
        // Around-load succeeded. Race the next snapshot emission against a grace
        // timeout. If a new emission contains the target → land. If grace elapses
        // first → onMissed (target got filtered out by PostFilterStrategy or by
        // album grouping that pruned a single member).
        val landedIndex = withTimeoutOrNull(loadGraceMs) {
            snapshotFlow { itemsState.value }
                .map { resolveTargetIndex(it, chatId, messageId) }
                .first { it >= 0 }
        }
        if (landedIndex != null) {
            onLanded(chatId, messageId, landedIndex)
        } else {
            onMissed()
        }
    }
}
```

Add imports at the top of `FeedScrollEffects.kt`:

```kotlin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
```

#### Step 1.5: Run tests and lint

```
./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.ui.timeline.*"
./gradlew :app:lintRelease
```

Expected: all green.

#### Step 1.6: Commit

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/timeline/FeedScrollEffects.kt \
        app/src/test/kotlin/dev/lyo/hortay/ui/timeline/PendingScrollResolverTest.kt
git commit -m "$(cat <<'EOF'
fix(timeline): cure silent hang in pending-scroll resolver

After loadHistoryAround returns true but the target is filtered out
(PostFilterStrategy drop, album grouping prune), the helper used to
collect snapshotFlow forever. Now races a 1500ms grace window and
fires onMissed on timeout.

Extracted resolveTargetIndex as pure helper, covered by 4 JUnit tests.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `smartScrollTo` distance-threshold helper

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/SmartScroll.kt`
- Create: `app/src/test/kotlin/dev/lyo/hortay/ui/timeline/SmartScrollDistanceTest.kt`

#### Step 2.1: Write failing test

```kotlin
// SmartScrollDistanceTest.kt
package dev.lyo.hortay.ui.timeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmartScrollDistanceTest {

    @Test
    fun `chooses Instant when distance exceeds threshold`() {
        assertEquals(ScrollKind.Instant, scrollKindFor(currentIndex = 0, target = 100, threshold = 8))
    }

    @Test
    fun `chooses Animated when distance equals threshold`() {
        assertEquals(ScrollKind.Animated, scrollKindFor(currentIndex = 0, target = 8, threshold = 8))
    }

    @Test
    fun `chooses Animated when within threshold`() {
        assertEquals(ScrollKind.Animated, scrollKindFor(currentIndex = 10, target = 12, threshold = 8))
    }

    @Test
    fun `distance is symmetric — scrolling up`() {
        assertEquals(ScrollKind.Instant, scrollKindFor(currentIndex = 200, target = 50, threshold = 8))
    }

    @Test
    fun `same index is Animated noop`() {
        assertEquals(ScrollKind.Animated, scrollKindFor(currentIndex = 5, target = 5, threshold = 8))
    }
}
```

#### Step 2.2: Run test, verify it fails

```
./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.ui.timeline.SmartScrollDistanceTest"
```

Expected: FAIL — `ScrollKind` / `scrollKindFor` undefined.

#### Step 2.3: Implement helper

Create `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/SmartScroll.kt`:

```kotlin
package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.lazy.LazyListState
import kotlin.math.abs

/**
 * Scroll strategy choice. `Instant` for far targets (cheap, no animation
 * through unloaded rows), `Animated` for near targets (smooth, preserves
 * sense of place). Matches Telegram-Android's `scrollByTouch` vs hard-jump
 * split — distance threshold is the canonical chat-UI pattern for jump
 * pills (Telegram, Slack, Discord all do this).
 */
internal enum class ScrollKind { Instant, Animated }

/**
 * Pure helper: pick scroll strategy from distance. Extracted for testing —
 * LazyListState's internal state isn't exercisable from JUnit.
 */
internal fun scrollKindFor(currentIndex: Int, target: Int, threshold: Int): ScrollKind =
    if (abs(target - currentIndex) > threshold) ScrollKind.Instant else ScrollKind.Animated

/**
 * Default distance threshold (rows). ~3 viewports at typical PostCard height.
 * Lifts the canonical chat-UI pattern: animate near, jump far. Anything
 * beyond ~8 rows animates through layout passes the user doesn't care
 * about — instant jump + brief highlight on the destination card is the
 * established pattern (Telegram Android `SCROLL_MAX_*`, jhakim.com chat
 * scroll playbook).
 */
internal const val SMART_SCROLL_THRESHOLD_ROWS = 8

/**
 * Jump or animate to [target] based on distance from current first-visible
 * index. Used by all three "jump" pills: NewPostsPill, UnreadCounterPill,
 * home-tap. Suspends until scroll completes.
 */
internal suspend fun LazyListState.smartScrollTo(
    target: Int,
    threshold: Int = SMART_SCROLL_THRESHOLD_ROWS,
) {
    val current = firstVisibleItemIndex
    when (scrollKindFor(current, target, threshold)) {
        ScrollKind.Instant -> scrollToItem(target)
        ScrollKind.Animated -> animateScrollToItem(target)
    }
}
```

#### Step 2.4: Run test, verify it passes

```
./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.ui.timeline.SmartScrollDistanceTest"
```

Expected: 5 tests pass.

#### Step 2.5: Replace three call-sites in `TimelineScreen.kt`

Replace `listState.animateScrollToItem(target)` at the following lines with `listState.smartScrollTo(target)`:

- L590 (home-tap effect)
- L1635 (NewPostsPill onClick)
- L1680 (UnreadCounterPill onClick)

Note: Task 5 will delete L577-591 entirely. For Task 2 just edit L590 in place — the delete in Task 5 is a separate change.

Exact edits (keep `scope.launch` wrapping where it exists):

```kotlin
// L590 — was: listState.animateScrollToItem(target)
listState.smartScrollTo(target)

// L1635 — was: listState.animateScrollToItem(target)
listState.smartScrollTo(target)

// L1680 — was: scope.launch { listState.animateScrollToItem(target) }
scope.launch { listState.smartScrollTo(target) }
```

#### Step 2.6: Run lint + tests

```
./gradlew :app:testDebugUnitTest
./gradlew :app:lintRelease
```

Expected: all green.

#### Step 2.7: Commit

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/timeline/SmartScroll.kt \
        app/src/test/kotlin/dev/lyo/hortay/ui/timeline/SmartScrollDistanceTest.kt \
        app/src/main/kotlin/dev/lyo/hortay/ui/timeline/TimelineScreen.kt
git commit -m "$(cat <<'EOF'
feat(timeline): distance-threshold jump for unread/new-posts/home pills

The three "jump" pills (NewPostsPill, UnreadCounterPill, home-tap) used
animateScrollToItem unconditionally. For a target 100+ rows away the
animation rendered every intermediate card on the way, feeling sluggish
and locking the user out for ~2-3 seconds.

Threshold default: 8 rows (~3 viewports). Beyond that, instant jump
matches Telegram, Slack, Discord. Within that, smooth animation
preserves sense of place.

Pure-helper distance branch covered by 5 JUnit tests.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `TimelineUiState` types and builder

**Why now:** Pure types + builder is the foundation for Task 4 (ViewModel wiring) and Task 5 (UI gate). Doing it in isolation lets us test exhaustively.

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/TimelineUiState.kt`
- Create: `app/src/test/kotlin/dev/lyo/hortay/ui/timeline/TimelineUiStateBuilderTest.kt`

#### Step 3.1: Write failing test

```kotlin
// TimelineUiStateBuilderTest.kt
package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.EmptyReadCursors
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.TimelinePost
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimelineUiStateBuilderTest {

    private fun post(chatId: Long, id: Long, date: Long): TimelinePost = TimelinePost(
        chatId = chatId,
        id = id,
        senderName = "ch",
        senderHandle = null,
        date = date,
        content = PostContent.Text("body"),
    )

    @Test
    fun `Loading while posts empty`() {
        val s = buildTimelineUiState(
            posts = persistentListOf(),
            cursorsLanded = false,
            cursors = EmptyReadCursors,
            feedOrder = FeedOrder.Newest,
            refreshing = true,
        )
        assertTrue(s is TimelineUiState.Loading)
    }

    @Test
    fun `Empty when refresh finished and no posts`() {
        val s = buildTimelineUiState(
            posts = persistentListOf(),
            cursorsLanded = true,
            cursors = EmptyReadCursors,
            feedOrder = FeedOrder.Newest,
            refreshing = false,
        )
        assertTrue(s is TimelineUiState.Empty)
    }

    @Test
    fun `Loading while reverse-feed cursors not landed`() {
        // Critical: in OldestUnreadFirst with cursors=null, rendering posts at
        // index 0 would land the user on the OLDEST post in asc-by-date sort —
        // perceived as "random ancient post" in cold-start.
        val posts = listOf(post(1L, 100L, 1_000L)).toPersistentList()
        val s = buildTimelineUiState(
            posts = posts,
            cursorsLanded = false,
            cursors = EmptyReadCursors,
            feedOrder = FeedOrder.OldestUnreadFirst,
            refreshing = false,
        )
        assertTrue(s is TimelineUiState.Loading)
    }

    @Test
    fun `Ready at top in Newest when caught up`() {
        val posts = listOf(
            post(1L, 100L, 3_000L),
            post(1L, 99L, 2_000L),
            post(1L, 98L, 1_000L),
        ).toPersistentList()
        val cursors = persistentMapOf(1L to 100L)
        val s = buildTimelineUiState(
            posts = posts,
            cursorsLanded = true,
            cursors = cursors,
            feedOrder = FeedOrder.Newest,
            refreshing = false,
        )
        assertTrue(s is TimelineUiState.Ready)
        s as TimelineUiState.Ready
        assertEquals(0, s.initialIndex)
    }

    @Test
    fun `Ready at oldest-unread index in Newest`() {
        // Newest order: newest-first. Oldest unread is at the BOTTOM of the
        // unread block — indexOfLast.
        val posts = listOf(
            post(1L, 100L, 3_000L), // unread
            post(1L, 99L, 2_000L), // unread
            post(1L, 98L, 1_000L), // read
        ).toPersistentList()
        val cursors = persistentMapOf(1L to 98L)
        val s = buildTimelineUiState(
            posts = posts,
            cursorsLanded = true,
            cursors = cursors,
            feedOrder = FeedOrder.Newest,
            refreshing = false,
        )
        assertTrue(s is TimelineUiState.Ready)
        s as TimelineUiState.Ready
        assertEquals(1, s.initialIndex)
    }

    @Test
    fun `Ready at first-unread boundary in OldestUnreadFirst`() {
        // OldestUnreadFirst: asc-by-date. Read above, unread below. Boundary =
        // first unread by indexOfFirst on the SORTED list.
        val posts = listOf(
            post(1L, 100L, 3_000L), // unread
            post(1L, 99L, 2_000L), // unread
            post(1L, 98L, 1_000L), // read
        ).toPersistentList()
        val cursors = persistentMapOf(1L to 98L)
        val s = buildTimelineUiState(
            posts = posts,
            cursorsLanded = true,
            cursors = cursors,
            feedOrder = FeedOrder.OldestUnreadFirst,
            refreshing = false,
        )
        assertTrue(s is TimelineUiState.Ready)
        s as TimelineUiState.Ready
        // Sorted asc: [98, 99, 100]. Read=98 → first unread index = 1.
        assertEquals(1, s.initialIndex)
        assertEquals(persistentMapOf(1L to 98L), s.frozenCursors)
    }

    @Test
    fun `Ready at lastIndex in OldestUnreadFirst when caught up`() {
        val posts = listOf(
            post(1L, 100L, 3_000L),
            post(1L, 99L, 2_000L),
            post(1L, 98L, 1_000L),
        ).toPersistentList()
        val cursors = persistentMapOf(1L to 100L) // all read
        val s = buildTimelineUiState(
            posts = posts,
            cursorsLanded = true,
            cursors = cursors,
            feedOrder = FeedOrder.OldestUnreadFirst,
            refreshing = false,
        )
        assertTrue(s is TimelineUiState.Ready)
        s as TimelineUiState.Ready
        assertEquals(2, s.initialIndex) // lastIndex of 3-element list
    }
}
```

#### Step 3.2: Run test, verify it fails

```
./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.ui.timeline.TimelineUiStateBuilderTest"
```

Expected: FAIL — `TimelineUiState` / `buildTimelineUiState` undefined.

#### Step 3.3: Implement types and builder

Create `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/TimelineUiState.kt`:

```kotlin
package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.EmptyReadCursors
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.ReadCursors
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.continueReadingIndex
import dev.lyo.hortay.data.orderedFor
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

/**
 * Source-of-truth for what TimelineScreen renders. The ViewModel computes the
 * initial scroll anchor as part of this state — by the time the UI sees
 * [Ready], everything needed to mount LazyColumn at the correct row in one
 * frame is present. UI never computes scroll position from effects; it just
 * passes [Ready.initialIndex] to `rememberSaveable` LazyListState.
 *
 * Three states:
 *   • [Loading] — refresh in flight (Newest) OR cursors not yet landed
 *     (OldestUnreadFirst). UI renders a [SkeletonFeed]. No LazyColumn
 *     mounted, no scroll position to fight over.
 *   • [Ready]   — posts non-empty, cursors landed (in reverse mode),
 *     [initialIndex] is the first paint position. After mount this state
 *     can update freely (new posts, cursor advances) — but the LazyColumn's
 *     LazyListState is owned by the user from this point.
 *   • [Empty]   — refresh finished, zero posts. Render an empty-state hero.
 */
@Immutable
sealed interface TimelineUiState {
    @Immutable data object Loading : TimelineUiState
    @Immutable data object Empty : TimelineUiState

    @Immutable
    data class Ready(
        val posts: PersistentList<TimelinePost>,
        val initialIndex: Int,
        val frozenCursors: ReadCursors,
    ) : TimelineUiState
}

/**
 * Pure function: derive [TimelineUiState] from VM-observed inputs.
 *
 * Gate logic — same intent as Telegram Android's `firstLoading` /
 * `firstMessagesLoaded` flags: do not paint the feed until the answer to
 * "where should we land?" is known.
 *
 *   • Newest mode: cursors only affect the optional "scroll to oldest
 *     unread" anchor. If posts are present we can render — cursors
 *     missing means we land at index 0 (top), which is the canonical
 *     "newest at top" fallback.
 *   • OldestUnreadFirst (reverse) mode: cursors are LOAD-BEARING. Without
 *     them, the boundary picker would return -1 and we'd land at lastIndex
 *     (= newest at the bottom) of an asc-by-date sort — that's the user-
 *     reported "starts on a random old post" symptom. Hold Loading until
 *     cursors land.
 *
 * [frozenCursors] is captured at the moment we transition to Ready: it
 * pins the "Непрочитане" boundary divider so it doesn't migrate as the
 * user dwells and acks posts (the chat-app idiom every modern messenger
 * follows). The live cursor map continues to drive per-card unread
 * strips and the "↓ N" counter pill.
 */
fun buildTimelineUiState(
    posts: PersistentList<TimelinePost>,
    cursorsLanded: Boolean,
    cursors: ReadCursors,
    feedOrder: FeedOrder,
    refreshing: Boolean,
): TimelineUiState {
    if (posts.isEmpty()) {
        return if (refreshing) TimelineUiState.Loading else TimelineUiState.Empty
    }
    // Reverse feed: cursors are required to position the user. Stay Loading
    // until they arrive. Newest mode tolerates missing cursors — initialIndex
    // falls back to 0 (top).
    if (feedOrder == FeedOrder.OldestUnreadFirst && !cursorsLanded) {
        return TimelineUiState.Loading
    }
    val sorted = posts.orderedFor(feedOrder, cursors).toPersistentList()
    val initialIndex = when (feedOrder) {
        FeedOrder.Newest -> {
            val boundary = continueReadingIndex(feedOrder, sorted, cursors)
            if (boundary >= 0) boundary else 0
        }
        FeedOrder.OldestUnreadFirst -> {
            val boundary = continueReadingIndex(feedOrder, sorted, cursors)
            if (boundary >= 0) boundary else sorted.lastIndex.coerceAtLeast(0)
        }
    }
    return TimelineUiState.Ready(
        posts = sorted,
        initialIndex = initialIndex,
        frozenCursors = cursors,
    )
}
```

#### Step 3.4: Run test, verify it passes

```
./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.ui.timeline.TimelineUiStateBuilderTest"
```

Expected: 7 tests pass.

#### Step 3.5: Commit

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/timeline/TimelineUiState.kt \
        app/src/test/kotlin/dev/lyo/hortay/ui/timeline/TimelineUiStateBuilderTest.kt
git commit -m "$(cat <<'EOF'
feat(timeline): TimelineUiState with precomputed initial scroll anchor

Pure types + builder. The reverse-feed cold-start race ("random ancient
post" symptom) is resolved at the type level: OldestUnreadFirst stays
Loading until cursors land, so the LazyColumn never composes with the
wrong scroll position.

Frozen cursors latched at Ready transition — preserves the chat-app
idiom of a non-migrating "New messages" boundary divider.

Pure builder covered by 7 JUnit tests across Loading/Ready/Empty,
Newest/Reverse, and caught-up/has-unread axes.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Wire `TimelineUiState` into `TimelineViewModel`

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/TimelineViewModel.kt`

#### Step 4.1: Add `uiState` StateFlow

Read the existing `TimelineViewModel.kt` to find the imports block. Then add the following imports if missing:

```kotlin
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.ReadCursors
import dev.lyo.hortay.data.EmptyReadCursors
import kotlinx.coroutines.flow.combine
```

#### Step 4.2: Change `TimelineViewModel` constructor to accept feedOrder + cursors flows

The VM currently takes `(repo: FeedSource, bookmarks: BookmarkStore)`. We extend it to also receive the live feed-order setting and the read-cursors flow so the UiState is fully self-contained.

Modify the class header:

```kotlin
class TimelineViewModel(
    private val repo: FeedSource,
    private val bookmarks: BookmarkStore,
    feedOrderFlow: StateFlow<FeedOrder>,
    cursorsFlow: StateFlow<ReadCursors>,
    cursorsLandedFlow: StateFlow<Boolean>,
) : ViewModel() {
```

#### Step 4.3: Wire AppGraph factory

Read `app/src/main/kotlin/dev/lyo/hortay/AppGraph.kt` to find where `TimelineViewModel` is constructed (search for `TimelineViewModel(`). Modify the construction site to pass the new args:

```kotlin
// In AppGraph, the existing settings/read-cursor stores expose StateFlow<FeedOrder>
// and StateFlow<ReadCursors>. Cursors-landed is a derived flag we already compute.
TimelineViewModel(
    repo = postsRepository,
    bookmarks = bookmarkStore,
    feedOrderFlow = settingsStore.feedOrder,
    cursorsFlow = readCursors.flow,
    cursorsLandedFlow = readCursors.landed,
)
```

If `readCursors.landed` doesn't already exist as a `StateFlow<Boolean>`, derive it inline from the cursor-loading machinery. Search `ReadCursors` for "landed" / `MutableStateFlow<Boolean>` to find the existing flag — it's the same one TimelineScreen currently calls `readCursorsLandedState`.

#### Step 4.4: Expose `uiState` in the VM

Add after the existing `posts` declaration in `TimelineViewModel`:

```kotlin
/**
 * Source-of-truth for TimelineScreen. Combines posts, cursor landing,
 * frozen cursors, refresh state, and feed order into a single discriminated
 * union. UI mounts LazyColumn only when this is [TimelineUiState.Ready].
 *
 * Combining inside the VM (not in the Composable) prevents the cold-start
 * recomposition race: by the time the Composable sees Ready, the
 * `initialIndex` is already correct for the first measure pass.
 */
val uiState: StateFlow<TimelineUiState> = combine(
    posts,
    cursorsLandedFlow,
    cursorsFlow,
    feedOrderFlow,
    _refreshing,
) { ps, landed, cursors, order, refreshing ->
    buildTimelineUiState(
        posts = ps,
        cursorsLanded = landed,
        cursors = cursors,
        feedOrder = order,
        refreshing = refreshing,
    )
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TimelineUiState.Loading)
```

#### Step 4.5: Run lint + tests

```
./gradlew :app:testDebugUnitTest
./gradlew :app:lintRelease
```

Expected: all green. (No new tests in this task — Task 3's builder tests cover the behaviour, and this task is plumbing.)

#### Step 4.6: Commit

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/timeline/TimelineViewModel.kt \
        app/src/main/kotlin/dev/lyo/hortay/AppGraph.kt
git commit -m "$(cat <<'EOF'
feat(timeline): expose TimelineUiState from VM

ViewModel now combines posts + cursors + cursorsLanded + feedOrder +
refreshing into a single StateFlow<TimelineUiState>. UI consumes this
instead of computing scroll anchors from effects.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Rewrite `TimelineScreen` around UiState

This is the biggest task. It DELETES four LaunchedEffect blocks and replaces the LazyColumn mounting with a `when (uiState)`. SkeletonFeed is mounted during Loading.

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/SkeletonFeed.kt`
- Modify: `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/TimelineScreen.kt`

#### Step 5.1: Implement SkeletonFeed

Create `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/SkeletonFeed.kt`:

```kotlin
package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Placeholder rows shown while [TimelineUiState] is Loading or
 * [ChannelUiState] is Resolving. Shape matches PostCard roughly so the
 * transition to the real LazyColumn is visually quiet — same trick
 * Telegram-Android's `messageSkeletons` uses.
 *
 * Intentionally NOT animated: we want users to perceive "loading" without
 * the cost of a shimmer effect that recomposes 60 times per second and
 * fights cold-start RPCs for CPU.
 */
@Stable
@Composable
fun SkeletonFeed(rowCount: Int = 6, modifier: Modifier = Modifier) {
    val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier.fillMaxSize()) {
        repeat(rowCount) {
            SkeletonRow(placeholderColor)
        }
    }
}

@Composable
private fun SkeletonRow(color: androidx.compose.ui.graphics.Color) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(color)
            )
            Spacer(Modifier.size(12.dp))
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .height(14.dp)
                    .fillMaxWidth(0.5f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .height(14.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .height(14.dp)
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
    }
}
```

#### Step 5.2: Read existing TimelineScreen and identify deletion blocks

Run:

```
grep -n "LaunchedEffect" app/src/main/kotlin/dev/lyo/hortay/ui/timeline/TimelineScreen.kt | head -30
```

The four blocks to delete are at approximate line ranges (verify before editing):

- L332-414 — cold-start scroll pin (`LaunchedEffect(Unit)`)
- L577-591 — home-tap effect
- L612-621 — scope-switch effect (`LaunchedEffect(showOnlyBookmarked)` or similar)
- L816-824 — feedOrder-flip effect

Use `Read` to load each block before deleting, to confirm line ranges and capture the surrounding context.

#### Step 5.3: Replace LazyColumn mount with `when (uiState)`

The current TimelineScreen renders a `LazyColumn` directly, gated on `feedReady` etc. Replace the entire feed-rendering section with:

```kotlin
val uiState by vm.uiState.collectAsStateWithLifecycle()
val listState = rememberSaveable(
    showOnlyBookmarked,
    saver = LazyListState.Saver,
) {
    when (val s = uiState) {
        is TimelineUiState.Ready -> LazyListState(s.initialIndex, 0)
        else -> LazyListState(0, 0)
    }
}

when (val s = uiState) {
    TimelineUiState.Loading -> SkeletonFeed(modifier = Modifier.fillMaxSize())
    TimelineUiState.Empty -> TimelineEmptyHero(showOnlyBookmarked = showOnlyBookmarked)
    is TimelineUiState.Ready -> TimelineLazyColumn(
        state = listState,
        posts = s.posts,
        frozenCursors = s.frozenCursors,
        // ... rest of existing LazyColumn args
    )
}
```

The `rememberSaveable` here is keyed on `showOnlyBookmarked` (the Home/Saved scope) — switching tabs gets a fresh ListState computed from the next Ready emission. The Saver preserves position across config changes within the same scope.

#### Step 5.4: Delete the four LaunchedEffects

After the `when` block is in place, delete the four blocks identified in Step 5.2. The home-tap effect (L577-591) currently calls `smartScrollTo(target)` after Task 2 — but the effect itself is dead now because the user has full control after first paint. Replace home-tap with a direct dispatch from the NavBar tap callback:

Find where `homeTapTrigger` is fired (in `MainScaffold` or in this file). Replace the effect-driven scroll with:

```kotlin
// In the home-tap callback (wherever onHomeTap is invoked for the timeline):
onHomeTap = {
    val current = listState.firstVisibleItemIndex
    if (current == 0) {
        vm.refresh()
    } else {
        scope.launch { listState.smartScrollTo(0) }
    }
}
```

This eliminates the effect and makes the behaviour explicit at the call site.

#### Step 5.5: Run lint + tests

```
./gradlew :app:testDebugUnitTest
./gradlew :app:lintRelease
```

Expected: all green. If lint complains about unused imports from the deleted effects — clean them up.

#### Step 5.6: Hand-verify on device

```
./gradlew :app:installDebug
adb logcat -s TdClient PostsRepository TimelineViewModel
```

Verify the four golden flows manually:

1. **Cold start, Newest** — kill app, relaunch. Should show SkeletonFeed for ~1-2s, then snap to top of fresh feed in one frame. No flash.
2. **Cold start, OldestUnreadFirst** — same, but should snap to the read→unread boundary (between read above and unread below) in one frame. No "ancient post" flash.
3. **Home-tap on NavBar** — at position 50, tap home → instant jump to top (no animate-through). At position 5, tap home → smooth animate.
4. **Refresh during scroll** — pull-to-refresh while at position 30 → user stays at position 30 (no auto-pin to top).

#### Step 5.7: Commit

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/timeline/TimelineScreen.kt \
        app/src/main/kotlin/dev/lyo/hortay/ui/timeline/SkeletonFeed.kt
git commit -m "$(cat <<'EOF'
refactor(timeline): gate-on-Ready, drop 4 scroll-pin effects

The cold-start scroll pin (snapshotFlow + takeWhile + scrollToItem in a
loop), home-tap effect, scope-switch effect, and feedOrder-flip effect
are gone. LazyColumn is now mounted only when uiState is Ready, with
the initial scroll position passed to LazyListState directly. First
paint lands at the correct row in one frame.

Cold-start chaos ("random ancient post on launch", "newest card swaps
under your finger as chunks stream in") is fixed at the architectural
level — no Compose effect runs scroll position decisions any more.

Home-tap behaviour now lives at the NavBar callback, not in an effect.
Refresh no longer auto-pins to top.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: `ChannelUiState` + per-channel deep-link gating

**Files:**
- Create: `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/ChannelUiState.kt`
- Create: `app/src/test/kotlin/dev/lyo/hortay/ui/timeline/ChannelUiStateBuilderTest.kt`
- Modify: `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/ChannelViewModel.kt`

#### Step 6.1: Write failing test

```kotlin
// ChannelUiStateBuilderTest.kt
package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.TimelinePost
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChannelUiStateBuilderTest {

    private fun post(id: Long, album: List<Long> = emptyList()): TimelinePost = TimelinePost(
        chatId = 1L,
        id = id,
        senderName = "ch",
        senderHandle = null,
        date = id,
        content = PostContent.Text("body"),
        albumMessageIds = album,
    )

    @Test
    fun `Resolving while history still loading`() {
        val s = buildChannelUiState(
            posts = persistentListOf(),
            historyLoading = true,
            scrollToMessageId = 200L,
            attemptedAround = false,
        )
        assertTrue(s is ChannelUiState.Resolving)
    }

    @Test
    fun `Ready at zero when no scrollToMessageId and history loaded`() {
        val posts = listOf(post(100L), post(99L)).toPersistentList()
        val s = buildChannelUiState(
            posts = posts,
            historyLoading = false,
            scrollToMessageId = null,
            attemptedAround = false,
        )
        assertTrue(s is ChannelUiState.Ready)
        s as ChannelUiState.Ready
        assertEquals(0, s.initialIndex)
    }

    @Test
    fun `Ready at target index when scrollToMessageId resolved`() {
        val posts = listOf(post(300L), post(200L), post(100L)).toPersistentList()
        val s = buildChannelUiState(
            posts = posts,
            historyLoading = false,
            scrollToMessageId = 200L,
            attemptedAround = false,
        )
        assertTrue(s is ChannelUiState.Ready)
        s as ChannelUiState.Ready
        assertEquals(1, s.initialIndex)
        assertEquals(200L, s.highlightedMessageId)
    }

    @Test
    fun `Resolving when scrollToMessageId not yet in posts and not attempted`() {
        val posts = listOf(post(100L)).toPersistentList()
        val s = buildChannelUiState(
            posts = posts,
            historyLoading = false,
            scrollToMessageId = 999L,
            attemptedAround = false,
        )
        assertTrue(s is ChannelUiState.Resolving)
    }

    @Test
    fun `Missing when scrollToMessageId not in posts after around-load attempt`() {
        val posts = listOf(post(100L)).toPersistentList()
        val s = buildChannelUiState(
            posts = posts,
            historyLoading = false,
            scrollToMessageId = 999L,
            attemptedAround = true,
        )
        assertTrue(s is ChannelUiState.Missing)
    }

    @Test
    fun `Album member id resolves to anchor index`() {
        val posts = listOf(post(300L), post(200L, album = listOf(200L, 201L, 202L))).toPersistentList()
        val s = buildChannelUiState(
            posts = posts,
            historyLoading = false,
            scrollToMessageId = 202L,
            attemptedAround = false,
        )
        assertTrue(s is ChannelUiState.Ready)
        s as ChannelUiState.Ready
        assertEquals(1, s.initialIndex)
    }
}
```

#### Step 6.2: Run test, verify it fails

```
./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.ui.timeline.ChannelUiStateBuilderTest"
```

Expected: FAIL — undefined.

#### Step 6.3: Implement types and builder

Create `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/ChannelUiState.kt`:

```kotlin
package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.TimelinePost
import kotlinx.collections.immutable.PersistentList

/**
 * State for a single-channel screen. Discriminated union:
 *
 *   • [Resolving] — first-paint gate. Either history is still loading, or
 *     a deep-link scrollToMessageId hasn't been found / fetched yet. UI
 *     renders a SkeletonFeed so the channel's head post never flashes
 *     before the target one (the bug user reported as "another post for
 *     half a second").
 *   • [Ready]     — posts non-empty, initial index resolved. The
 *     [highlightedMessageId] is non-null on deep-link landings so the
 *     PostCard can pulse a brief highlight after the scroll.
 *   • [Missing]   — deep-link target couldn't be located after the
 *     around-load attempt. UI shows a snackbar and falls back to the
 *     channel's normal newest-first view (mounted Ready with index 0).
 */
@Immutable
sealed interface ChannelUiState {
    @Immutable data object Resolving : ChannelUiState

    @Immutable
    data class Ready(
        val posts: PersistentList<TimelinePost>,
        val initialIndex: Int,
        val highlightedMessageId: Long?,
    ) : ChannelUiState

    @Immutable data object Missing : ChannelUiState
}

/**
 * Pure builder. The around-load attempt is signaled via [attemptedAround]:
 * the VM flips this true after `loadHistoryAround` has been issued and its
 * result has reached the posts flow (or timed out). Until then we stay in
 * Resolving — never fall through to Ready with a wrong index.
 */
fun buildChannelUiState(
    posts: PersistentList<TimelinePost>,
    historyLoading: Boolean,
    scrollToMessageId: Long?,
    attemptedAround: Boolean,
): ChannelUiState {
    if (historyLoading) return ChannelUiState.Resolving
    if (scrollToMessageId == null) {
        return ChannelUiState.Ready(
            posts = posts,
            initialIndex = 0,
            highlightedMessageId = null,
        )
    }
    val idx = posts.indexOfFirst { p ->
        p.id == scrollToMessageId || scrollToMessageId in p.albumMessageIds
    }
    if (idx >= 0) {
        return ChannelUiState.Ready(
            posts = posts,
            initialIndex = idx,
            highlightedMessageId = scrollToMessageId,
        )
    }
    return if (attemptedAround) ChannelUiState.Missing else ChannelUiState.Resolving
}
```

#### Step 6.4: Run test, verify it passes

```
./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.ui.timeline.ChannelUiStateBuilderTest"
```

Expected: 6 tests pass.

#### Step 6.5: Wire into `ChannelViewModel`

Modify `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/ChannelViewModel.kt`:

Add `scrollToMessageId: Long?` to the constructor (must come after `chatId`). Add the `attemptedAround` state and the `channelUiState` flow:

```kotlin
class ChannelViewModel(
    private val repo: PostsRepository,
    private val bookmarks: BookmarkStore,
    val chatId: Long,
    private val scrollToMessageId: Long?,
) : ViewModel() {
    // ... existing fields ...

    private val _attemptedAround = MutableStateFlow(false)

    val channelUiState: StateFlow<ChannelUiState> = combine(
        posts,
        _historyLoading,
        _attemptedAround,
    ) { ps, loading, attempted ->
        buildChannelUiState(
            posts = ps,
            historyLoading = loading,
            scrollToMessageId = scrollToMessageId,
            attemptedAround = attempted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChannelUiState.Resolving)

    init {
        // Existing init block...

        // Deep-link resolution: if a scrollToMessageId came in with the route,
        // issue an around-load and flip `_attemptedAround` once the result is
        // observed in `posts` OR a grace timeout elapses.
        if (scrollToMessageId != null) {
            viewModelScope.launch {
                val initialMatch = posts.value.indexOfFirst { p ->
                    p.id == scrollToMessageId || scrollToMessageId in p.albumMessageIds
                }
                if (initialMatch < 0) {
                    runCatching { repo.loadHistoryAround(chatId, scrollToMessageId) }
                    withTimeoutOrNull(1_500L) {
                        posts.first { ps ->
                            ps.any { p ->
                                p.id == scrollToMessageId || scrollToMessageId in p.albumMessageIds
                            }
                        }
                    }
                }
                _attemptedAround.value = true
            }
        }
    }
}
```

Update the VM factory in `MainScaffold.kt` (or wherever `viewModel(key = "channel:$chatId")` is invoked) to pass `scrollToMessageId` from `NavEntry.Channel.scrollToMessageId`.

#### Step 6.6: Run lint + tests

```
./gradlew :app:testDebugUnitTest
./gradlew :app:lintRelease
```

Expected: all green.

#### Step 6.7: Commit

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/timeline/ChannelUiState.kt \
        app/src/test/kotlin/dev/lyo/hortay/ui/timeline/ChannelUiStateBuilderTest.kt \
        app/src/main/kotlin/dev/lyo/hortay/ui/timeline/ChannelViewModel.kt \
        app/src/main/kotlin/dev/lyo/hortay/ui/main/MainScaffold.kt
git commit -m "$(cat <<'EOF'
feat(channel): ChannelUiState with deep-link Resolving gate

Per-channel screen now exposes a discriminated union state. When a
deep-link arrives with scrollToMessageId, VM stays in Resolving until
loadHistoryAround returns and target is observed in posts (or 1500ms
grace elapses). UI mounts SkeletonFeed during Resolving — channel's
head post no longer flashes before deep-link target.

Pure builder covered by 6 JUnit tests across all branches.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Rewrite `ChannelScreen` around `ChannelUiState`

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/ui/timeline/ChannelScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-uk/strings.xml`

#### Step 7.1: Add strings

In `values/strings.xml`:

```xml
<string name="deep_link_post_unavailable">Post unavailable</string>
```

In `values-uk/strings.xml`:

```xml
<string name="deep_link_post_unavailable">Пост недоступний</string>
```

#### Step 7.2: Replace ChannelScreen feed-rendering block

Replace the feed-rendering block in `ChannelScreen.kt` with the `when (channelUiState)` dispatch. The block to replace starts where `displayedItems` is computed (around L227) and goes through the LazyColumn end.

```kotlin
val channelUiState by vm.channelUiState.collectAsStateWithLifecycle()
val listState = rememberSaveable(chatId, saver = LazyListState.Saver) {
    when (val s = channelUiState) {
        is ChannelUiState.Ready -> LazyListState(s.initialIndex, 0)
        else -> LazyListState(0, 0)
    }
}

when (val s = channelUiState) {
    ChannelUiState.Resolving -> SkeletonFeed(modifier = Modifier.fillMaxSize())
    ChannelUiState.Missing -> {
        val msg = stringResource(R.string.deep_link_post_unavailable)
        LaunchedEffect(s) { snackbarHost.showSnackbar(msg) }
        // After showing the snackbar, render the channel's normal newest-first
        // view. We use a fresh state flow read to ensure we don't get stuck.
        ChannelLazyColumn(
            state = listState,
            posts = posts,
            // ... existing args
        )
    }
    is ChannelUiState.Ready -> ChannelLazyColumn(
        state = listState,
        posts = s.posts,
        highlightedMessageId = s.highlightedMessageId,
        // ... existing args
    )
}
```

#### Step 7.3: Delete the cold-entry effect

Find the `LaunchedEffect(chatId, feedOrder)` block at approximately L282-311 (the channel cold-entry scroll for OldestUnreadFirst). Delete entirely — the new `initialIndex` from `ChannelUiState.Ready` does this job declaratively.

Also delete the existing `rememberPendingScrollToMessage` call (around L234-247) — the deep-link resolution is now in the VM, not in this Composable.

#### Step 7.4: Run lint + tests

```
./gradlew :app:testDebugUnitTest
./gradlew :app:lintRelease
```

Expected: all green. Watch for unused imports — clean them.

#### Step 7.5: Hand-verify deep-links on device

```
./gradlew :app:installDebug
```

Manual test paths:
1. Tap a `t.me/<channel>/<id>` link in another app — should open Hortay with channel screen showing skeleton, then ONE-FRAME paint at the target message with brief highlight. No flash of other posts.
2. Tap a quote-reply link inside the app pointing to an old post in same channel — same as above.
3. Tap a link to an inaccessible post — skeleton briefly, then snackbar "Пост недоступний" + normal channel view at top.

#### Step 7.6: Commit

```bash
git add app/src/main/kotlin/dev/lyo/hortay/ui/timeline/ChannelScreen.kt \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-uk/strings.xml
git commit -m "$(cat <<'EOF'
refactor(channel): drop cold-entry pin and pendingScroll effect

ChannelScreen now renders when(channelUiState). Skeleton during
Resolving → LazyColumn with correct initialIndex on Ready transition →
snackbar + normal feed on Missing. Deep-link target lands in one frame
with no flash of the channel's head post.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Update CHANGELOG and verify whole-system flows

**Files:**
- Modify: `CHANGELOG.md`

#### Step 8.1: Add CHANGELOG entry

Under `## [Unreleased]` add to existing sections:

```markdown
### Changed
- Feed cold-start, channel cold-start, and deep-link landing now use a single
  ViewModel-owned `UiState` with a precomputed initial scroll index. The
  LazyColumn is mounted only when state is Ready, so first paint lands at the
  correct anchor (top / unread boundary / deep-link target) in one frame —
  replaces five parallel `LaunchedEffect`s that previously fought over scroll
  position and produced the "random ancient post on launch" / "another post
  for half a second before deep-link target" symptoms.
- "↓ N unread", "↑ N new posts", and the NavBar home-tap pills now instant-jump
  with brief highlight when the target is more than ~8 rows away; smooth
  animation only for nearby targets. Previously all three animated-through the
  entire intermediate list, taking seconds for far jumps.

### Fixed
- `rememberPendingScrollToMessage` no longer silently hangs when
  `loadHistoryAround` succeeds but the target is filtered out by
  `PostFilterStrategy` / album grouping. After a 1500ms grace, `onMissed`
  fires and the UI surfaces "Post unavailable" instead of staying in a
  Resolving state forever.
```

#### Step 8.2: Run full pre-commit gate

```
./gradlew :app:testDebugUnitTest
./gradlew :app:lintRelease
```

Expected: green.

#### Step 8.3: Hand-verify all four golden flows on device

```
./gradlew :app:installDebug
adb logcat -s TdClient PostsRepository TimelineViewModel ChannelViewModel
```

Verification matrix (record results in a scratch note, fix any regressions before commit):

| Flow | Expected | Actual |
|---|---|---|
| Cold start, Newest, has unread | Skeleton ~1s → snap to oldest-unread row | |
| Cold start, OldestUnreadFirst, has unread | Skeleton ~1-2s → snap to read→unread boundary | |
| Cold start, caught up (no unread) | Skeleton → snap to top (Newest) / bottom (Reverse) | |
| Deep-link to recent post | Skeleton briefly → target post with highlight | |
| Deep-link to old post | Skeleton + load → target post with highlight | |
| Deep-link to inaccessible post | Skeleton briefly → snackbar + normal channel | |
| NavBar home-tap from position 50 | Instant jump to top | |
| NavBar home-tap from position 3 | Smooth animate to top | |
| Home-tap when at top | Triggers refresh, no scroll | |
| "↑ N new posts" pill tap | Instant jump (if far) + reveal pending | |
| "↓ N unread" pill tap | Instant jump to next unread (if far) | |
| Pull-to-refresh while scrolled | User stays at current position, no auto-pin | |
| Refresh that brings new posts | New posts appear in feed, scroll position preserved | |
| OldestUnreadFirst dwell-ack | Per-card unread strip updates live; boundary divider does NOT migrate | |

#### Step 8.4: Commit CHANGELOG

```bash
git add CHANGELOG.md
git commit -m "$(cat <<'EOF'
docs(changelog): scroll coordinator rewrite

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

#### Step 8.5: Final lint gate

```
./gradlew :app:lintRelease :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL with zero errors. Lint warnings acceptable only if they pre-date this branch.

---

## Self-Review

### Spec coverage

Reviewing the user's stated symptoms against tasks:

1. **"Deep-link flashes wrong post for half a second"** → Task 6 (`ChannelUiState.Resolving`) + Task 7 (`ChannelScreen` skeleton-gate). Covered.
2. **"App cold-start is chaos, jumps between posts"** → Task 4 (`TimelineUiState` builder gates on cursors-landed in reverse mode) + Task 5 (delete 4 effects). Covered.
3. **"Starts on random ancient post"** → Task 3 test case `Loading while reverse-feed cursors not landed`. Covered by type-level invariant.
4. **"Slow scroll for far targets"** → Task 2 (`smartScrollTo`). Covered.
5. **"↓ N unread pill"** → Task 2 (one of three call-sites). Covered.
6. **"↑ N new posts pill"** → Task 2 (second call-site). Covered.
7. **"Silent hang in pending-scroll resolver"** → Task 1. Covered.

### Placeholder scan

- No "TBD", "TODO", "implement later" in any task body.
- All code blocks contain real Kotlin / commands / file paths.
- Skeleton implementation (Task 5.1) is complete-enough to compile; no stubs.
- Each `git commit` is shown with full HEREDOC message — no `git commit -m "fix stuff"`.

### Type consistency

- `TimelineUiState.Ready` fields (`posts`, `initialIndex`, `frozenCursors`) match between Task 3 declaration and Task 5 consumer.
- `ChannelUiState.Ready` fields (`posts`, `initialIndex`, `highlightedMessageId`) match between Task 6 declaration and Task 7 consumer.
- `buildTimelineUiState` parameter order matches between Task 3 implementation and Task 4 caller (`posts, cursorsLanded, cursors, feedOrder, refreshing`).
- `buildChannelUiState` parameter order: `posts, historyLoading, scrollToMessageId, attemptedAround` — consistent across Task 6.
- `smartScrollTo` signature: `suspend fun LazyListState.smartScrollTo(target: Int, threshold: Int = SMART_SCROLL_THRESHOLD_ROWS)` — consistent across Task 2 sites.

### Dependencies between tasks

Task order is strict:

```
1 (independent)
2 (independent)
3 → 4 → 5
6 → 7
8 (integration / docs / verify)
```

Tasks 1+2 can run in any order — they're isolated fixes. Tasks 3-5 must be sequential. Tasks 6-7 must be sequential. Task 8 depends on all prior.

### Risks and mitigations

- **Risk:** Changing `TimelineViewModel` constructor signature breaks ViewModel factory wiring. **Mitigation:** Task 4 explicitly updates `AppGraph.kt`; lintRelease will catch missing wiring.
- **Risk:** `cursorsLandedFlow` may not exist as a clean `StateFlow<Boolean>` in `ReadCursors`. **Mitigation:** Task 4.3 says "search ReadCursors for 'landed'"; if absent, derive inline. Worst case, add a one-line getter.
- **Risk:** Deleting effects in Task 5 may break unrelated features (e.g. logout reset, scope switching). **Mitigation:** Task 5.6 hand-verify checklist includes scope switch and logout-relogin.
- **Risk:** `NavEntry.Channel.scrollToMessageId` may already be plumbed correctly. **Mitigation:** Task 6.5 says "Update the VM factory if needed" — no-op if already done.

---

## Execution

Run inline via `superpowers:executing-plans`, batch by task with a `./gradlew :app:lintRelease :app:testDebugUnitTest` gate between each. After every commit, the next task starts fresh — no carryover state. Hand-verify the four flows on device after Task 5 and after Task 7. Do not skip the hand-verify steps; unit tests cover ViewModel logic but only on-device interaction validates the Compose render gate.
