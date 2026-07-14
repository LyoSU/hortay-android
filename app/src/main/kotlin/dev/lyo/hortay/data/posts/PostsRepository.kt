package dev.lyo.hortay.data.posts

import android.util.Log
import dev.lyo.hortay.data.ChatPresence
import dev.lyo.hortay.data.ColdStartBackfillStore
import dev.lyo.hortay.data.ConnectionStatus
import dev.lyo.hortay.data.FeedSource
import dev.lyo.hortay.data.IgnoredChannelsStore
import dev.lyo.hortay.data.MessageContentMapper
import dev.lyo.hortay.data.MessageMapper
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostFilterStrategy
import dev.lyo.hortay.data.ReactionKind
import dev.lyo.hortay.data.ReactionTogglePolicy
import dev.lyo.hortay.data.Reactions
import dev.lyo.hortay.data.StringResolver
import dev.lyo.hortay.data.TdClient
import dev.lyo.hortay.data.TdSender
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.TimelineSnapshotStore
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.data.archive.ArchiveRepository
import dev.lyo.hortay.data.archive.ArchivedMediaStore
import dev.lyo.hortay.data.archive.ChatRef
import dev.lyo.hortay.data.archive.MediaFileFromContent
import dev.lyo.hortay.data.archive.PendingEditBuffer
import dev.lyo.hortay.data.archive.TdlibContentMetaExtractor
import dev.lyo.hortay.data.archive.TombstoneRecord
import dev.lyo.hortay.data.surfaceTo
import dev.lyo.hortay.data.warnUnlessCancelled
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Twitter-style chronological feed merged from every channel chat the user follows.
 *
 * Event-driven ingest (cold-start AND steady-state). [triggerInitialSync], called
 * once per session from [dev.lyo.hortay.AppGraph] when `auth.Ready` first lands,
 * drains [TdApi.LoadChats] for `ChatListMain` + `ChatListArchive`. That drain is a
 * trigger, not a fetch — TDLib responds by emitting `UpdateNewChat`,
 * `UpdateChatLastMessage`, `UpdateChatAddedToList` for every chat in the lists.
 * Our listeners catch those, route messages through [ingest], and populate
 * `_mainChatIds` / `_archivedChatIds` along the way. No `GetChats` round-trip,
 * no per-channel `chat.lastMessage` harvest pass, no 2 s "wait for chatCache"
 * timeout — Levin's canonical pattern (tdlib/td#3019: updates are the source of
 * truth; LoadChats is what makes them flow).
 *
 * On-demand paths (untouched by [triggerInitialSync]):
 *   • [loadChannelHistory] — when the user opens a single-channel filter.
 *   • [loadOlder] — when the user scrolls past the head of one channel.
 *   • [loadHistoryAround] — when a deep link lands on an older post.
 *
 * Race-buffer: an `UpdateChatLastMessage` that arrives before its matching
 * `UpdateNewChat` is stashed in [pendingLastMessages] and flushed in
 * [handleNewChat]. The previous shape dropped these and relied on the harvest
 * to recover; with the harvest gone, the buffer closes the window deterministically.
 *
 * Concurrency: [refreshMutex] serialises the batch refresh paths (initial sync,
 * pull-to-refresh, snapshot restore) against [clear]. Live update ingest runs
 * OUTSIDE this mutex via the CAS-loop semantics of [MutableStateFlow.update].
 *
 * Storage: the live feed is held in a [PersistentList]. The hot path
 * (UpdateMessageInteractionInfo) fires dozens of times per second on busy news days, and
 * a plain `List` makes us copy the whole 1000-entry feed on every event. PersistentList's
 * structural sharing turns the per-event mutation into O(log N) — a few KB of allocation
 * instead of ~50KB.
 *
 * File organisation. The class lives here; orthogonal helpers split out to keep this
 * file focused on the mutable-state surface:
 *   • [applyChatTitleToFeed] / [applyChatPhotoToFeed] in `ChannelMetadataSync.kt`
 *     own the per-row decision logic for `UpdateChatTitle` / `UpdateChatPhoto`
 *     (channel-as-sender vs personal-author split).
 *   • [foldRawIntoCurrent] in `SnapshotRestorer.kt` holds the canonical raw →
 *     feed fold (partial-album guard). Unit-tested in isolation.
 */
class PostsRepository(
    private val td: TdSender,
    private val mapper: MessageMapper,
    private val scope: CoroutineScope,
    private val userMessages: UserMessageBus,
    private val connection: kotlinx.coroutines.flow.StateFlow<ConnectionStatus>,
    private val snapshotStore: TimelineSnapshotStore,
    private val foreground: kotlinx.coroutines.flow.StateFlow<Boolean>,
    private val res: StringResolver,
    /**
     * Channels the user has hidden from the merged feed. Optional so tests +
     * historical call sites that don't care about the filter can keep
     * constructing a repository without wiring a DataStore. When null, the
     * filter is a no-op and [subscribedPosts] behaves identically to the
     * pre-feature implementation.
     */
    private val ignoredChannels: IgnoredChannelsStore? = null,
    /**
     * One-shot persisted flag for the first-sign-in history backfill (see
     * [runFirstSignInBackfill]). Optional for the same test-constructor
     * reason as [ignoredChannels]; when null, the backfill becomes a no-op.
     */
    private val coldStartBackfill: ColdStartBackfillStore? = null,
    /**
     * Archive capture sink AND the live archive-enabled gate. Optional so tests +
     * historical call sites that don't wire the archive feature keep constructing a
     * repository without it. When null, capture calls are no-ops and [handleDeleted]
     * falls back to the pre-feature remove-on-delete behaviour; when present and
     * [ArchiveRepository.isEnabled] is true, [handleDeleted] marks posts as
     * [TimelinePost.isDeleted] instead so a tombstone surfaces in the feed.
     */
    private val archiveRepository: ArchiveRepository? = null,
    /**
     * Optional companion to [archiveRepository] for storing media file bytes
     * locally. When non-null, captured snapshots get a Tier 2 file copy under
     * `filesDir/archive_media/` keyed by SHA-256 — survives TDLib LRU eviction
     * and post deletion. When null, only structured media references + inline
     * minithumb are persisted.
     */
    private val archiveMediaStore: ArchivedMediaStore? = null,
) : FeedSource {

    /**
     * Serialises the *batch* refresh paths (cold-start fan-out, pull-to-refresh,
     * snapshot restore) against each other and against [clear]. Only the
     * read-side ingest path (live TDLib updates: `UpdateNewMessage`,
     * `UpdateMessageInteractionInfo`, `UpdateMessageContent`, `UpdateDeleteMessages`,
     * `UpdateChatPhoto`, `UpdateChatTitle`, the optimistic-reaction overlay, etc.)
     * runs OUTSIDE this mutex and relies on the CAS-loop semantics of
     * [MutableStateFlow.update] for atomicity.
     *
     * **CAS-loop invariant (load-bearing — do not "fix" with a mutex):**
     * Every lambda passed to `_posts.update { current -> ... }` MUST be a pure
     * function of `current`. No side-effects outside the closure; no reads of
     * unrelated mutable state whose change between retry attempts could shift
     * the computed result. CAS-loop re-runs the lambda on a fresh snapshot when
     * a concurrent writer wins the race — so the second writer naturally sees
     * (and preserves) the first writer's mutation. Wrapping the read-side path
     * in [refreshMutex] would serialise every TDLib update into the refresh
     * critical section, blocking the user-facing feed for ~hundreds of ms during
     * cold-start RPC storms. The non-mutex design is intentional.
     */
    private val refreshMutex = Mutex()

    /**
     * Chat metadata mirror, fed by [TdApi.UpdateNewChat] and refreshed on
     * [TdApi.UpdateChatTitle] / [TdApi.UpdateChatPhoto] eviction. Two
     * load-bearing reasons it stays even after [MessageMapper] dropped its
     * own `User`/`Chat`/`Supergroup` mirrors:
     *
     *   1. **`UpdateChatLastMessage` race gate** (see the listener below):
     *      containment check short-circuits the per-update fallback that
     *      would otherwise re-create a `GetChat` × N fanout during the
     *      cold-start storm — the FLOOD_WAIT-class regression ARCHITECTURE.md's
     *      "Load-bearing" table forbids. The race is buffered in
     *      [pendingLastMessages] rather than dropped.
     *   2. **Synchronous reads** for [channelSubscribersCached] (seeds
     *      `ChannelHeaderBar` subtitle on the first frame) and the synchronous
     *      branches of [chatTitle] / [chatAvatar] / [resolveChatKind] /
     *      [loadChannelHistoryLocked] / [searchInChannel]. Skipping the JNI
     *      hop avoids contention with the `td.send` queue that handles
     *      user-driven RPCs during scroll. Cost is trivial (one Chat POJO
     *      per subscribed channel, ~hundreds of bytes).
     *
     * Invariant maintenance: [handleChatTitle] / [handleChatPhoto] evict
     * (`remove`) on the corresponding update instead of in-place mutation —
     * the shared POJO would otherwise be read mid-write by a concurrent
     * worker. Consumers that miss the cache fall back to `td.send(GetChat)`
     * and re-populate.
     */
    private val chatCache = ConcurrentHashMap<Long, TdApi.Chat>()

    /**
     * Supergroup metadata mirror, fed by [TdApi.UpdateSupergroup]. TDLib emits
     * one update per cached supergroup on cold-start session warm-up (when
     * `useChatInfoDatabase = true`, which we set in [TdClient]) and on every
     * live change to a field on the [TdApi.Supergroup] object — including
     * [TdApi.Supergroup.memberCount].
     *
     * Caching the full [TdApi.Supergroup] (not just the count) costs ~200B per
     * channel and lets every supergroup-derived read short-circuit the
     * [TdApi.GetSupergroup] RPC.
     *
     * **Why this stays after Wave 1** (mapper dropped its parallel
     * `User`/`Chat`/`Supergroup` caches in favour of direct `td.send`): the
     * sole load-bearing reader is [channelSubscribersCached], called from
     * [dev.lyo.hortay.ui.timeline.ChannelViewModel]`.init` to seed the
     * subscribers `StateFlow` *before* the first frame. A suspending
     * variant ([channelSubscribers]) is provided too, but the cached read
     * is what makes the [ChannelHeaderBar] subtitle paint a count on the
     * first frame instead of `null` followed by a recomposition once the
     * suspend resolves (see the CHANGELOG fix "Channel header subscriber
     * count appears on the first frame instead of with a delay"). The
     * suspending fallback could call `td.send(GetSupergroup)` directly,
     * but the cache is the only synchronous path.
     */
    private val supergroupCache = ConcurrentHashMap<Long, TdApi.Supergroup>()

    /**
     * Test-only accessor. Exposes the internal chat cache so unit tests can verify that
     * UpdateNewChat / UpdateChatLastMessage listeners populate it correctly. Not annotated
     * @VisibleForTesting because that requires an extra androidx dependency on this module;
     * the `ForTest` suffix is the convention used elsewhere in this codebase.
     */
    internal fun chatCacheForTest(chatId: Long): TdApi.Chat? = chatCache[chatId]

    /** Test-only mirror of [chatCacheForTest] for the supergroup cache. */
    internal fun supergroupCacheForTest(supergroupId: Long): TdApi.Supergroup? =
        supergroupCache[supergroupId]

    // Stamped on successful triggerInitialSync completion. refreshIfStale uses it to
    // skip re-running LoadChats when the previous drain is still warm. With the new
    // event-driven shape, "refresh" is just LoadChats — TDLib emits the updates and
    // our listeners stream them in.
    @Volatile
    private var lastRefreshAtMs: Long = 0L

    /**
     * Buffers an [TdApi.UpdateChatLastMessage] that arrived BEFORE the matching
     * [TdApi.UpdateNewChat] seeded [chatCache]. The previous shape dropped these
     * outright (to avoid a [TdApi.GetChat] × N fan-out storm on cold-start);
     * with the harvest gone, a drop here is a permanent miss for that message.
     *
     * Buffer cost: at most ~hundreds of entries (one per subscribed chat) during
     * the first cold-start tick; flushed and emptied as [TdApi.UpdateNewChat]
     * arrivals seed [chatCache]. Bounded by the chat list size — no eviction
     * needed.
     */
    private val pendingLastMessages = ConcurrentHashMap<Long, TdApi.Message>()

    /**
     * Flips to `true` when [triggerInitialSync]'s [TdApi.LoadChats] drain
     * completes (TDLib emits 404 = "no more chats to load"). At that point
     * [_mainChatIds] / [_archivedChatIds] are populated to "complete enough"
     * — every subscribed chat for which `LoadChats(Main)` and
     * `LoadChats(Archive)` resolved positions emitted an
     * `UpdateChatAddedToList`, which our listener fed into the sets. From
     * this edge on, [ingest] filters strictly; before it, we accept all to
     * avoid dropping live arrivals whose `UpdateChatAddedToList` is still
     * in flight.
     *
     * Reset to `false` on [clear] so a fresh login starts a fresh sync
     * window.
     */
    private val _initialSyncDone = MutableStateFlow(false)

    // Exposed for tests / potential future consumers; internally `_initialSyncDone`
    // gates ingest's onlyLocal decision. No external consumer at present.
    val initialSyncDone: StateFlow<Boolean> = _initialSyncDone.asStateFlow()

    // Album coalescing: TDLib emits one UpdateNewMessage per album member with no
    // "album complete" signal — confirmed upstream by tdlib/td#1482 (closed 2021
    // with "use a timeout") and reiterated in tdlib/td#2523 (closed 2023 with the
    // same verdict). The wire-level gap is permanent, so we treat album reassembly
    // as a layered defence with three independent rescue paths, each addressing a
    // different timing window:
    //
    //   1. `albumBuffers` + `albumDebounce` (this pair) — silence-based debounce
    //      on the live `UpdateNewMessage` burst. Members arriving within
    //      [ALBUM_DEBOUNCE_MS] of each other flush as one group. Cheap; first line.
    //   2. [coalesceAlbumFragments] — surround `GetChatHistory` fetch invoked from
    //      every refresh / pagination / deep-load / snapshot-restore path that
    //      maps raw messages, so an under-sized album group at the response
    //      boundary (cold-start lastMessage harvest, window edge on
    //      `loadOlderLocked`) reaches the mapper whole. Second line — costs an
    //      RPC per under-sized group, bounded by distinct album ids in the batch.
    //   3. [requestAlbumRepair] — Layer-2 visibility repair: a throttled,
    //      network-backed re-fetch of the residual degraded album when its card
    //      becomes visible, for the rare case the local index in layer 2 still
    //      came up short. The persisted snapshot is NO LONGER a membership
    //      source — it is a cold-paint fallback only ([mergeSnapshotIntoFeed]);
    //      [preserveDegradedAlbumSiblings] merely keeps a background save from
    //      shrinking that fallback below last-known-good.
    //
    // Removing any one of these reopens a corresponding CHANGELOG bug: "1 photo
    // → 2 photos → ..." flicker (layer 1), partial album on PTR or deep-load
    // (layer 2), "5-photo album restore on relaunch" / "Snapshot preserves saved
    // album siblings" / "Editing an album caption no longer collapses" (layer 3).
    private val albumBuffers = ConcurrentHashMap<Pair<Long, Long>, MutableList<TdApi.Message>>()
    private val albumDebounce = ConcurrentHashMap<Pair<Long, Long>, Job>()

    // Layer-2 album repair queue. Single-consumer Channel reducer (same idiom as
    // MediaCache.fileEvents): one repair dispatched per ALBUM_REPAIR_THROTTLE_MS, so a
    // fast fling over many degraded cards can't storm GetChatHistory. The queued-set
    // dedupes by (chatId, mediaAlbumId) so a card scrolling in/out/in enqueues once.
    // [AlbumRepairRequest] lives at file scope (below the class) per convention.
    private val albumRepairRequests = Channel<AlbumRepairRequest>(Channel.UNLIMITED)
    private val albumRepairQueued = ConcurrentHashMap.newKeySet<Pair<Long, Long>>()

    // Single-flight + cooldown for deep channel-history loads. Re-entering the same
    // channel filter within DEEP_LOAD_COOLDOWN_MS reuses the previous load (no second
    // GetChatHistory(80) round-trip). Failed loads do NOT mark cooldown, so transient
    // network blips don't lock a channel out for a full minute.
    // Boolean payload: true = at least one post landed, false = success-shaped
    // no-op (empty batch, non-channel chat). The cooldown gate honours that
    // distinction so a transient empty success doesn't strand a channel for
    // DEEP_LOAD_COOLDOWN_MS.
    private val deepLoadJobs = ConcurrentHashMap<Long, Deferred<Result<Boolean>>>()
    private val deepLoadCooldownUntilMs = ConcurrentHashMap<Long, Long>()

    // Coalescing buffer for UpdateMessageInteractionInfo. On busy days these arrive in
    // dozens-per-second bursts for *every* channel in the user's list (not just visible
    // ones). Each event used to fan out one O(N) `_posts.update` — at 1000 posts and 50
    // events/sec that's ~50MB/sec of garbage. Buffer per-message updates and flush all
    // pending mutations in a single `mutate {}` block every INTERACTION_INFO_COALESCE_MS.
    // Non-nullable values: ConcurrentHashMap forbids null. TDLib does occasionally emit
    // UpdateMessageInteractionInfo with null `interactionInfo` (which the original
    // per-field-fallback handler treated as a no-op), so we drop those at the entry.
    //
    // Inserts go through `merge`, not `put`: TDLib often emits two updates back-to-back
    // for the same message — first one carrying fresh views/replies with `reactions=null`
    // ("no change to reactions, see your local copy"), then one with the new `reactions`.
    // A naive `put` lets a later null-reactions heartbeat overwrite an earlier
    // non-null-reactions update inside the same 200 ms coalesce window, silently dropping
    // the reaction change. The merge keeps the latest non-null value per field — the same
    // null-preserve rule [flushPendingInteractionInfo] applies field-by-field against
    // [TimelinePost], lifted up so the buffer never loses information.
    private val pendingInteractionInfo =
        ConcurrentHashMap<Pair<Long, Long>, TdApi.MessageInteractionInfo>()
    private val interactionFlushScheduled = AtomicBoolean(false)

    /**
     * Per-chat debounce buffer for `UpdateDeleteMessages`. We coalesce all
     * ids that arrive within a 200 ms quiet window into one
     * `captureTdlibDeleteSmart` call so the archive can group album members
     * via its own VERSION history (`selectAlbumKeyForMessage`) regardless of
     * whether the live `_posts` had the post resident. The previous
     * per-`(chatId, albumId)` shape required the live `_posts` row to
     * recover `albumId`, which dropped catch-up deletes entirely.
     *
     * Sliding-window debounce: each fresh batch for the same chatId cancels
     * the previous timer and rearms — so a 200 ms quiet period from the LAST
     * arrival fires the consolidated capture, absorbing TDLib's split-pulse
     * delivery on slow networks.
     */
    private val pendingChatDeletions: ConcurrentHashMap<Long, MutableList<Long>> =
        ConcurrentHashMap()
    private val chatDeletionTimers: ConcurrentHashMap<Long, Job> =
        ConcurrentHashMap()

    /**
     * Pairs `UpdateMessageContent` (UMC) with `UpdateMessageEdited` (UME) so the
     * archive captures a VERSION only for real admin edits. Bare UMC events
     * (poll voter ticks, live-location coords, paid-media reveals) get stashed
     * here and dropped on TTL when no paired UME arrives — see
     * [PendingEditBuffer] for the full rationale (and tdlib TL schema:9844).
     */
    private val pendingArchiveEdits = PendingEditBuffer()

    /**
     * Monotonic session counter, bumped inside [clear] (logout). Suspend-then-write
     * ingest paths capture it on entry and re-check before touching [_posts]: a
     * [logOut]→[clear] can land while an [ingest] is parked on its networked
     * `GetChat` / album coalesce, and without this guard the resumed write would
     * re-inject the previous account's posts into the freshly-wiped feed. Mirrors
     * the `chatCache[...] == null` guard [performAlbumRepair] already uses, but
     * generalised so it survives even if `chatCache` gets re-seeded in between.
     */
    private val sessionEpoch = AtomicLong(0L)

    private val _posts = MutableStateFlow<PersistentList<TimelinePost>>(persistentListOf())
    override val posts: StateFlow<PersistentList<TimelinePost>> = _posts.asStateFlow()

    // Merged-feed surface: posts limited to chats actually in the user's
    // subscription lists (ChatListMain or ChatListArchive). `loadChannelHistory`
    // and `loadHistoryAround` write into `_posts` directly so the single-channel
    // screen has its rows, but those rows must NOT leak into TimelineScreen for
    // channels the user hasn't joined (deep-link drill into a comment-reply
    // channel, public-handle search preview, etc.). Single-channel surfaces
    // (ChannelScreen) keep using [posts] and filter per-chat themselves.
    //
    // Pre-bootstrap (`_mainChatIds` empty) we don't filter — the cold-start
    // refresh harvests `Chat.lastMessage` straight into `_posts` and the
    // ChatListMain list arrives concurrently; gating on an empty set would
    // produce a blank feed for the first ~1–2 s.
    // `by lazy` because `_mainChatIds` / `_archivedChatIds` are declared later
    // in this file (forward reference). Lazy defers `combine(...)` evaluation
    // to first access, by which point both fields are initialised. No race:
    // first access is from TimelineViewModel.init through `repo.subscribedPosts`,
    // long after PostsRepository's constructor returns.
    override val subscribedPosts: StateFlow<PersistentList<TimelinePost>> by lazy {
        // `ignored` participates in the same `combine` as the chat-list gates so
        // an un-hide propagates in a single coherent emission — no transient
        // frame where the chat is un-hidden but the filter hasn't recomputed.
        // No store wired (legacy callers) → empty set, filter is a no-op.
        val ignoredFlow = ignoredChannels?.ignored
            ?: kotlinx.coroutines.flow.flowOf(kotlinx.collections.immutable.persistentSetOf())
        // Tombstones flow — TDLib-only archived DELETED snapshots reconstructed as ghost
        // posts. Merges into the live feed in the same combine so chat-list gates and
        // ignored filtering apply uniformly. Empty when archive feature has no captures
        // OR no repository is wired.
        val tombstonesFlow: Flow<ImmutableList<TombstoneRecord>> =
            archiveRepository?.observeTdlibTombstones()
                ?: flowOf(persistentListOf())
        // Revision-count map from archive.db — seeds the EditedChip counter for posts
        // that were edited in a previous session. Without this, the chip vanishes on
        // every relaunch and only reappears after a fresh edit.
        val revisionCountsFlow: Flow<Map<Pair<Long, Long>, Int>> =
            archiveRepository?.observeTdlibRevisionCounts()
                ?: flowOf(emptyMap())
        // Pair tombstones + revCounts so the downstream `combine` stays within Kotlin's
        // typed 5-arg overload (there is no typed 6-arg form — the vararg variant erases
        // to Array<Any>, which we'd then have to cast at every read).
        val archiveAuxFlow = combine(tombstonesFlow, revisionCountsFlow) { t, r -> t to r }
        combine(_posts, _mainChatIds, _archivedChatIds, ignoredFlow, archiveAuxFlow) { all, mainIds, archivedIds, ignored, archiveAux ->
            val (tombstones, revCounts) = archiveAux
            val subscribed = if (mainIds.isEmpty() && archivedIds.isEmpty()) all
            else all.filter { it.chatId in mainIds || it.chatId in archivedIds }
            val gated = if (ignored.isEmpty()) subscribed
            else subscribed.filter { it.chatId !in ignored }
            // Seed revisionCount from archive — preserves the EditedChip counter across
            // relaunches. Live-session bumps via handleContentChanged already work; this
            // patches in the values for posts that were edited in earlier sessions and
            // are now being rebuilt from TDLib (which carries no archive metadata).
            // We use maxOf so a fresher in-memory count (e.g. an edit landed AFTER cold
            // start but BEFORE the archive flow re-emits) doesn't get clobbered.
            val filtered = if (revCounts.isEmpty()) gated else gated.map { p ->
                val seeded = revCounts[p.chatId to p.id] ?: 0
                if (seeded > p.revisionCount) p.copy(revisionCount = seeded) else p
            }
            if (tombstones.isEmpty()) {
                ghostCache.clear()
                return@combine filtered.toPersistentList()
            }
            // Drop cached ghosts for messages that are no longer tombstoned (un-deleted is
            // impossible, but a chat un-subscribe / archive purge can retract one).
            ghostCache.keys.retainAll(tombstones.mapTo(HashSet(tombstones.size)) { it.primaryMessageId })
            // Merge ghosts: skip any tombstone whose primary message id is already
            // present (live post is the source of truth — TDLib hasn't yet propagated
            // the delete OR the chat lacks the message). Apply chat-list gating to
            // ghosts too: archive may hold snapshots from a chat the user later
            // unsubscribed from; we don't surface them.
            val livePresence = HashSet<Long>(filtered.size).apply {
                filtered.forEach { add(it.id) }
            }
            val ghosts = tombstones.asSequence()
                .filter { t ->
                    t.primaryMessageId !in livePresence &&
                        (mainIds.isEmpty() && archivedIds.isEmpty() ||
                            t.chatId in mainIds || t.chatId in archivedIds) &&
                        t.chatId !in ignored
                }
                .map { reuseGhost(it) }
                .toList()
            if (ghosts.isEmpty()) filtered.toPersistentList()
            else (filtered + ghosts).sortedByDescending { it.date }.toPersistentList()
        }
            .stateIn(scope, SharingStarted.Eagerly, persistentListOf())
    }

    /**
     * Reference-stable ghost memo, keyed by [TombstoneRecord.primaryMessageId]. The
     * [subscribedPosts] combine re-runs on every `_posts` emission — and `_posts` re-emits
     * roughly once per second during scroll as `updateMessageInteractionInfo` heartbeats
     * land on the open chat. Without memoisation each heartbeat called [buildTombstoneGhost]
     * afresh for every tombstone, which (a) churned GC with a new ByteArray-bearing
     * [TimelinePost] per ghost and (b) — because [TimelinePost]'s data-class `equals` is
     * reference-based on its `avatarThumb` array — handed PostCard a "different" instance
     * every time, defeating its `@Immutable` skip and recomposing every visible ghost on
     * every heartbeat.
     *
     * The `===` check is deliberate: between heartbeats the `tombstones` list is the same
     * instance held by `combine`, so its records are identity-equal and we reuse the cached
     * ghost. Only a genuine archive-db re-emission produces fresh record instances, and that
     * is exactly when the ghost's content may have changed and a rebuild is warranted.
     *
     * Touched only inside the [subscribedPosts] combine, which `stateIn` collects serially,
     * so the plain [HashMap] needs no synchronisation. Pruned to the live tombstone set on
     * each pass and cleared when no tombstones remain.
     */
    private val ghostCache = HashMap<Long, Pair<TombstoneRecord, TimelinePost>>()

    private fun reuseGhost(t: TombstoneRecord): TimelinePost {
        ghostCache[t.primaryMessageId]?.let { (rec, ghost) -> if (rec === t) return ghost }
        return buildTombstoneGhost(t).also { ghostCache[t.primaryMessageId] = t to it }
    }

    /**
     * Build a minimal "ghost" [TimelinePost] from a [TombstoneRecord]. The feed shows it
     * with [PostCard]'s deleted-mode treatment (alpha 0.55, hidden reactions, "deleted"
     * badge). Tap routes through the existing onTapRevisions handler to open the
     * revision sheet — that's where the rich history lives.
     *
     * Always go through [reuseGhost] from the feed combine — calling this directly on every
     * emission reintroduces the recomposition churn its memo exists to prevent.
     */
    private fun buildTombstoneGhost(t: TombstoneRecord): TimelinePost = TimelinePost(
        id = t.primaryMessageId,
        chatId = t.chatId,
        mediaAlbumId = 0L,
        senderName = t.channelTitle,
        senderHandle = t.channelHandle,
        avatarThumb = t.channelPhotoMinithumb,
        avatarFileId = null,
        content = PostContent.Text(FormattedText(t.text, emptyList())),
        views = 0,
        date = t.originalSeenAtMs,
        editDate = 0L,
        forwardOrigin = null,
        authorSignature = null,
        reply = null,
        reactions = Reactions(totalCount = 0, items = emptyList()),
        commentCount = null,
        albumMessageIds = emptyList(),
        isDeleted = true,
        revisionCount = 0,
    )

    // Per-chat read cursors mirrored from TDLib's UpdateChatReadInbox stream and seeded
    // from UpdateNewChat. Single source of truth for "has the user read up to message X
    // in chat Y" — drives the UnreadStrip on PostCard and the FeedOrder.OldestUnreadFirst
    // sort. PersistentMap so the StateFlow re-emits a structurally-shared map on
    // per-cursor advances (O(log N) instead of full O(N) copy on every read ack), and
    // Compose treats the type as @Immutable for skippability.
    //
    // Why a separate flow (not a field on TimelinePost): the cursor advances on viewport
    // dwell ~1/sec per visible post AND on every external ack from the official TG
    // client. Folding into TimelinePost would re-emit the whole feed list on every
    // cursor change — every dependent (ViewModel.visiblePosts filter, autodownloader,
    // snapshot persister) re-runs for nothing. A sidecar map lets PostCard recompose
    // only for the chat whose cursor actually moved.
    private val _chatReadCursors = MutableStateFlow<PersistentMap<Long, Long>>(persistentMapOf())
    val chatReadCursors: StateFlow<PersistentMap<Long, Long>> = _chatReadCursors.asStateFlow()

    // Real-time *new* post stream — emits ONLY for posts that arrived via
    // [TdApi.UpdateNewMessage] (direct + album-debounce flush), i.e. through
    // [ingest]. Refresh / loadOlder / restoreFromSnapshot / loadChannelHistory /
    // search write to [_posts] without going through [ingest], so they do NOT
    // emit here.
    //
    // Why this matters: [posts] is a *state* of the merged feed, so any
    // observer (.onEach) sees the entire feed re-emitted on every fold.
    // Subscribing to [posts] for "what's new" is a category error — it makes
    // every cold-start refresh / pagination / snapshot rehydrate look like
    // 1000 freshly-arrived posts. [MediaAutoDownloader] consumes this delta
    // stream so auto-download policy applies *only* to genuinely new posts
    // (the same shape Telegram-Android uses), not to history that's already
    // visible-but-scrolled-past on the feed.
    //
    // Buffer policy:
    //   - extraBufferCapacity = 64: a single album burst can flush ~10
    //     members into a single ingest; a small handful of channels can post
    //     concurrently. 64 is comfortable headroom.
    //   - BufferOverflow.DROP_OLDEST: under an extreme burst we'd rather
    //     lose a stale prefetch hint than back-pressure ingest (which would
    //     stall the live feed itself). The viewport-driven prefetch in
    //     [TimelineScreen] picks up anything we missed the moment the user
    //     scrolls into it.
    private val _newArrivals = MutableSharedFlow<TimelinePost>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val newArrivals: SharedFlow<TimelinePost> = _newArrivals.asSharedFlow()

    init {
        // Live feed: any new channel post arrives via UpdateNewMessage and is folded in.
        td.updates.filterIsInstance<TdApi.UpdateNewMessage>()
            .onEach { update -> handleNewMessage(update.message) }
            .launchIn(scope)

        // Server-side counter sync: views, reactions, comment counts.
        td.updates.filterIsInstance<TdApi.UpdateMessageInteractionInfo>()
            .onEach { update -> handleInteractionInfo(update) }
            .launchIn(scope)

        // Edits surface as a new editDate; we just stamp it onto the post.
        td.updates.filterIsInstance<TdApi.UpdateMessageEdited>()
            .onEach { update -> handleEdited(update) }
            .launchIn(scope)

        // Mods can delete posts; drop them from the timeline immediately.
        td.updates.filterIsInstance<TdApi.UpdateDeleteMessages>()
            .onEach { update -> handleDeleted(update) }
            .launchIn(scope)

        // Channel admins edit the body of a post — swap the rendered content in place.
        td.updates.filterIsInstance<TdApi.UpdateMessageContent>()
            .onEach { update -> handleContentChanged(update) }
            .launchIn(scope)

        // Pin / unpin badge changes on a channel post.
        td.updates.filterIsInstance<TdApi.UpdateMessageIsPinned>()
            .onEach { update -> handleIsPinnedChanged(update) }
            .launchIn(scope)

        // Channel renamed → all visible posts of that chat update their senderName.
        td.updates.filterIsInstance<TdApi.UpdateChatTitle>()
            .onEach { update -> handleChatTitle(update) }
            .launchIn(scope)

        // Channel avatar changed → all visible posts of that chat refresh avatar.
        td.updates.filterIsInstance<TdApi.UpdateChatPhoto>()
            .onEach { update -> handleChatPhoto(update) }
            .launchIn(scope)

        // UpdateUser arrives whenever a user's profile (name, avatar, username) changes.
        // The mapper no longer holds a parallel User cache — every resolveSender hits
        // TDLib's own local cache via GetUser (offline read), so a rename surfaces on the
        // next mapping without any per-update bookkeeping here. The listener is kept off
        // the wire entirely.

        // Supergroup metadata changed: mirror the [TdApi.Supergroup] object into
        // [supergroupCache] so reads that only need this lightweight shape (subscriber
        // count today; potentially status / verification flags later) can resolve
        // synchronously without an RPC. TDLib emits one update per cached supergroup on
        // session warm-up — so by the time a channel that the merged feed already showed
        // is opened, its memberCount is in memory. Handle / verification lookups in the
        // mapper read through TDLib's own GetSupergroup cache, so no invalidation needed.
        td.updates.filterIsInstance<TdApi.UpdateSupergroup>()
            .onEach { update ->
                supergroupCache[update.supergroup.id] = update.supergroup
            }
            .launchIn(scope)

        // Single entry-point for chat metadata. We deliberately DO NOT issue
        // GetChatHistory here:
        //   - On startup TDLib re-emits the entire chat list as UpdateNewChat; auto-
        //     loading each one 429-rate-limits the server.
        //   - Many of those chats are private / archived / DM — every one is a
        //     guaranteed [400] Can't access the chat warning.
        //
        // Three things happen on every UpdateNewChat:
        //   1. Cache the chat POJO and seed the read cursor (when TDLib has a signal).
        //   2. Hydrate `_mainChatIds` / `_archivedChatIds` from `chat.positions`
        //      (TDLib may emit positions as part of UpdateNewChat directly when the
        //      chat is read from `useChatInfoDatabase`; for first-auth they fill in
        //      later via UpdateChatAddedToList, which our listener also handles).
        //   3. Ingest `chat.lastMessage` directly — no harvest, no 2 s wait. If a
        //      racing UpdateChatLastMessage stashed its payload in
        //      `pendingLastMessages` before chatCache was seeded, prefer it
        //      (freshest known message wins).
        td.updates.filterIsInstance<TdApi.UpdateNewChat>()
            .onEach { update -> handleNewChat(update.chat) }
            .launchIn(scope)

        // Canonical read-state stream from TDLib. Fires whenever the user reads in
        // ANY client (official Telegram-Android included), so an UnreadStrip on a
        // card the user has just read in TG-Android animates out within ~1 frame
        // of the server ack landing here. monotonic-only update — TDLib has been
        // observed to emit redundant resets with smaller ids during chat repair
        // flows; clamping to monoMax(...) keeps the cursor from rewinding under us.
        td.updates.filterIsInstance<TdApi.UpdateChatReadInbox>()
            .onEach { update -> advanceReadCursor(update.chatId, update.lastReadInboxMessageId) }
            .launchIn(scope)

        // UpdateChatLastMessage fires when TDLib (a) discovers a fresh lastMessage for
        // a previously-known chat (edit, delete cascade, or a new post on a chat we
        // haven't OpenChat'd) and (b) initial sync of late-arriving last-message data
        // for a chat whose UpdateNewChat carried `lastMessage = null`.
        //
        // If the chat isn't cached yet, BUFFER the payload in [pendingLastMessages]
        // rather than dropping it. The matching UpdateNewChat will arrive shortly
        // and [handleNewChat] flushes the buffer through ingest. This closes the
        // race that previously caused permanent misses on cold-start (the harvest
        // is gone — there's no later catch-up sweep).
        //
        // Why we DON'T fall back to `td.send(GetChat)` here: a cold-start storm
        // can drive UpdateChatLastMessage × N before any UpdateNewChat lands. A
        // per-update GetChat fan-out would re-introduce the FLOOD_WAIT-class
        // regression the lastMessage-harvest rework was specifically built to
        // avoid (ARCHITECTURE.md "Load-bearing" — "Cold-start contract").
        td.updates.filterIsInstance<TdApi.UpdateChatLastMessage>()
            .onEach { update ->
                val msg = update.lastMessage ?: return@onEach
                if (chatCache[update.chatId] == null) {
                    pendingLastMessages[update.chatId] = msg
                    return@onEach
                }
                // Live sessions route through [handleNewMessage] so an album member
                // joins the same debounce buffer as its UpdateNewMessage siblings —
                // TDLib echoes the newest member here for every album, and the
                // previous direct ingest fired an immediate networked surround
                // coalesce in parallel with the debounce flush: a duplicate
                // GetChatHistory per album, plus a transient partial card whenever
                // that coalesce was served from a not-yet-complete local history.
                // One flush now handles all members (the buffer dedupes by id).
                //
                // During the cold-start drain keep the direct ingest: a debounced
                // flush would land AFTER [_initialSyncDone] flips and take the
                // NETWORKED coalesce path for every album lastMessage in the storm
                // — the GetChatHistory × N fan-out the cold-start contract forbids.
                // The direct call stays on the offline (onlyLocal) path instead.
                if (_initialSyncDone.value) handleNewMessage(msg)
                else ingest(update.chatId, listOf(msg))
            }
            .launchIn(scope)

        // Keep [archivedChatIds] live: TDLib fires UpdateChatAddedToList /
        // UpdateChatRemovedFromList whenever the user archives/unarchives a channel in
        // ANY client. Without these the "Усі" tab leaks a freshly-archived channel until
        // the next pull-to-refresh.
        td.updates.filterIsInstance<TdApi.UpdateChatAddedToList>()
            .onEach { update ->
                when (update.chatList) {
                    is TdApi.ChatListArchive -> _archivedChatIds.update { it + update.chatId }
                    is TdApi.ChatListMain -> _mainChatIds.update { it + update.chatId }
                    else -> {}
                }
            }
            .launchIn(scope)

        td.updates.filterIsInstance<TdApi.UpdateChatRemovedFromList>()
            .onEach { update ->
                when (update.chatList) {
                    is TdApi.ChatListArchive -> _archivedChatIds.update { it - update.chatId }
                    is TdApi.ChatListMain -> _mainChatIds.update { it - update.chatId }
                    else -> {}
                }
            }
            .launchIn(scope)

        // Persist a tiny snapshot of the top of the feed whenever the app goes to the
        // background, so the next cold start can render real content in <100ms while
        // the full refresh runs in parallel. We save on background-transition rather
        // than on every _posts change because the typical session pattern is many
        // edits-per-second (UpdateMessageInteractionInfo) followed by a clean
        // foreground→background flip; the per-edit save would be wasteful disk I/O.
        scope.launch {
            foreground
                .drop(1) // Skip the initial value; only act on real transitions.
                .filter { !it }
                .collect { saveSnapshotNow() }
        }

        // Layer-2 album repair drain: single consumer, one dispatch per throttle window.
        // The (chatId, mediaAlbumId) dedup key is held until AFTER the throttle delay so
        // the dedup window IS the throttle window — a card re-focused within the window
        // (or a second concurrent request for the same album) coalesces into the one
        // in-flight repair instead of queuing a duplicate. A still-degraded album
        // re-enqueues on the next focus once the key clears.
        scope.launch {
            for (req in albumRepairRequests) {
                runCatching { performAlbumRepair(req) }.warnUnlessCancelled(TAG, "albumRepair")
                delay(ALBUM_REPAIR_THROTTLE_MS)
                albumRepairQueued.remove(req.chatId to req.mediaAlbumId)
            }
        }
    }

    private suspend fun saveSnapshotNow() {
        // Persist the subscribed-feed view, NOT the raw _posts buffer.
        // _posts can carry transient rows from a single-channel drill or a
        // deep-link preview (loadChannelHistory / loadHistoryAround write
        // straight to _posts to keep ChannelScreen rendering, then
        // subscribedPosts filters them back out for the merged feed). Saving
        // _posts.value would persist those transient rows into the snapshot,
        // and on the next cold start — if the live refresh fails before
        // _mainChatIds populates — the fallback restore would inject them
        // straight into the visible main feed.
        val current = subscribedPosts.value
        if (current.isEmpty()) return
        val newEntries = current.take(SNAPSHOT_SIZE).flatMap { post ->
            // Persist every album member id, not just the anchor — the snapshot restore's
            // GetMessage pass needs all ids to fetch every sibling, so that
            // coalesceAlbumFragments can reassemble the full album card on cold paint.
            val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
            ids.map { post.chatId to it }
        }
        val merged = preserveDegradedAlbumSiblings(current, newEntries)
        runCatching { snapshotStore.save(merged) }.warnUnlessCancelled(TAG, "saveSnapshot")
    }

    /**
     * Anti-poisoning guard for [saveSnapshotNow]. If [current] contains any
     * degraded album (`mediaAlbumId != 0 && albumMessageIds.size <= 1`), the
     * natural save would shrink that album's member set on disk to a single id
     * — destroying the last-known-good membership the next cold start's
     * [mergeSnapshotIntoFeed] cold-paint fallback relies on.
     *
     * The save typically runs on a foreground→background transition. If the
     * in-memory feed was momentarily degraded at that moment (Layer-1 local
     * rehydration hadn't finished, or Layer-2 visibility repair hadn't fired),
     * overwriting the on-disk snapshot with the shrunken set is irreversible.
     *
     * Mitigation: for any chat that currently holds a degraded album, carry the
     * previous snapshot's entries for that chat that aren't already in
     * [newEntries] forward, so the on-disk member set is never smaller than
     * last-known-good. No `GetMessage` rescue — membership is owned by Layer 1 +
     * Layer 2 at read time; here we only avoid actively destroying the saved
     * fallback.
     *
     * No-op when [current] has no degraded albums — the common path saves
     * exactly the fresh view with zero extra I/O.
     */
    private suspend fun preserveDegradedAlbumSiblings(
        current: PersistentList<TimelinePost>,
        newEntries: List<Pair<Long, Long>>,
    ): List<Pair<Long, Long>> {
        val degradedChats = current
            .asSequence()
            .filter { it.mediaAlbumId != 0L && it.albumMessageIds.size <= 1 }
            .mapTo(HashSet()) { it.chatId }
        if (degradedChats.isEmpty()) return newEntries
        val previous = runCatching { snapshotStore.load() }
            .warnUnlessCancelled(TAG, "loadSnapshotForSave").getOrDefault(emptyList())
        if (previous.isEmpty()) return newEntries
        val newSet = newEntries.toHashSet()
        val carried = previous.filter { it.first in degradedChats && it !in newSet }
        return if (carried.isEmpty()) newEntries else newEntries + carried
    }

    /**
     * Restore the persisted top-of-feed by asking TDLib for each cached message id.
     * GetMessage on a known id is served from TDLib's local DB synchronously — for a
     * 50-post snapshot the whole pass is typically < 100ms.
     *
     * Idempotent + safe to overlap with [triggerInitialSync]: both the live ingest
     * stream and this restore fold into `_posts` through [foldRawIntoCurrent], so the
     * two are order-independent — whichever lands first, the result is the union with
     * the fresher copy winning per-id. See [restoreFromSnapshotInternal].
     */
    override suspend fun restoreFromSnapshot() {
        restoreFromSnapshotInternal()
    }

    /**
     * Returns the number of NEW posts added to the feed — exposed for callers that care.
     *
     * Cold-start history rehydration. The previous session's persisted top-of-feed is
     * folded into `_posts` as the WEAKER side: the live cold-start ingest (one
     * `chat.lastMessage` per channel) wins on every id it covers, and the snapshot only
     * fills the deeper history those stubs lack. Routing through [foldRawIntoCurrent]
     * makes the restore order-independent w.r.t. [triggerInitialSync] — whether the live
     * stub or the snapshot lands first, the union is the same and the deep history
     * survives.
     *
     * This previously bailed whenever `_posts` was already non-empty. The live cold-start
     * ingest writes `_posts` on the first `UpdateNewChat` (immediate), while this restore
     * has to await a `GetMessage` batch before its first write, so the stub almost always
     * won that race — the bail then discarded the snapshot and the feed collapsed to one
     * post per channel after every restart (tdlib/td#3019 keeps the cold-start stream at
     * one post per channel by design; the snapshot is the only thing carrying deep history
     * across warm restarts once the one-shot [runFirstSignInBackfill] is done).
     *
     * Album completeness is still owned by the Layer-1 local rehydration in
     * [coalesceAlbumFragments] plus the Layer-2 visibility repair ([requestAlbumRepair]);
     * the fold's partial-album guard only keeps it from regressing here.
     */
    suspend fun restoreFromSnapshotInternal(): Int {
        val snapshot = runCatching { snapshotStore.load() }
            .warnUnlessCancelled(TAG, "loadSnapshot")
            .getOrDefault(emptyList())
        if (snapshot.isEmpty()) return 0
        return mergeSnapshotIntoFeed(snapshot)
    }

    private suspend fun mergeSnapshotIntoFeed(snapshot: List<Pair<Long, Long>>): Int {
        val messages = fetchSnapshotMessages(snapshot)
        if (messages.isEmpty()) return 0
        val mapped = mapSnapshotMessages(messages)
        if (mapped.isEmpty()) return 0

        val mappedFeed = mapped.toPersistentList()
        var added = 0
        _posts.update { current ->
            // Snapshot is the weaker side: the live feed (fresher) wins every id it
            // already holds, the snapshot only contributes the history those cold-start
            // stubs are missing. Pure function of `current` — safe in the StateFlow CAS
            // loop (re-applied verbatim on contention).
            val merged = foldRawIntoCurrent(current = mappedFeed, raw = current)
            added = (merged.size - current.size).coerceAtLeast(0)
            merged
        }
        return added
    }

    private suspend fun fetchSnapshotMessages(
        snapshot: List<Pair<Long, Long>>,
    ): List<TdApi.Message> {
        // Parallel GetMessage. Bound concurrency so a 200-message snapshot
        // doesn't spawn 200 concurrent JNI calls and overflow TDLib's request
        // queue. GetMessage uses TDLib's per-message local index and works on
        // a cold chat-history cache.
        val semaphore = Semaphore(SNAPSHOT_RESTORE_CONCURRENCY)
        return coroutineScope {
            snapshot.map { (chatId, msgId) ->
                async {
                    semaphore.withPermit {
                        runCatching { td.send(TdApi.GetMessage(chatId, msgId)) }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun mapSnapshotMessages(
        messages: List<TdApi.Message>,
    ): List<TimelinePost> {
        // Group by chat so each channel is mapped against a single Chat
        // object — saves one GetChat per message in the cold-cache case.
        val byChat = messages.groupBy { it.chatId }
        return byChat.flatMap { (chatId, msgs) ->
            val chat = chatCache[chatId]
                ?: runCatching { td.send(TdApi.GetChat(chatId)) }.getOrNull()?.also { chatCache[chatId] = it }
                ?: return@flatMap emptyList()
            if (!chat.isChannel()) emptyList()
            // Snapshot restore is a cold-start path and already has every member
            // id (fetched via local GetMessage upstream), so coalesce stays
            // offline — no networked GetChatHistory while the feed bootstraps.
            else coalesceAlbumFragments(chatId, msgs, onlyLocal = true).map { mapper.toChannelPost(it, chat) }
        }
    }

    /**
     * Pull-to-refresh entry point. With the event-driven shape, "refresh" is just
     * re-running `LoadChats` so TDLib re-emits any stragglers it has held back.
     * The feed itself is kept live by the update-stream listeners; there is no
     * harvest to re-run. Idempotent: TDLib short-circuits a fully-drained list
     * with 404 immediately (~ms), so calling this on a warm feed costs nothing.
     */
    override suspend fun refresh() {
        runTriggerInitialSync("refresh", surfaceErrors = true)
    }

    /**
     * Soft refresh: skip if the last successful drain is within [REFRESH_STALE_MS].
     */
    override suspend fun refreshIfStale() {
        if (System.currentTimeMillis() - lastRefreshAtMs <= REFRESH_STALE_MS) return
        runTriggerInitialSync("refreshIfStale", surfaceErrors = true)
    }

    /**
     * One-shot session-start sync. Called once from [dev.lyo.hortay.AppGraph]
     * when `auth.Ready` first lands, before any user-facing screen even has a
     * chance to call `refresh*`. Idempotent against re-entry (serialised by
     * [refreshMutex]).
     *
     * What it does:
     *   1. Drains `LoadChats(ChatListMain)` until TDLib returns 404. This is
     *      the trigger that makes TDLib emit `UpdateNewChat` /
     *      `UpdateChatLastMessage` / `UpdateChatAddedToList` for every chat
     *      in the main list. Our listeners ingest those into the feed.
     *   2. Drains `LoadChats(ChatListArchive)` for the same reason — the
     *      Archive tab needs its `_archivedChatIds` populated, and the only
     *      way TDLib emits those updates is in response to LoadChats on the
     *      archive list.
     *   3. Flips [_initialSyncDone] so [ingest]'s subscription filter
     *      activates and any subsequent `UpdateNewMessage` for a non-
     *      subscribed chat is correctly dropped.
     *
     * What it deliberately doesn't do:
     *   - No `GetChats` call. The set is built incrementally from the
     *     listener stream, which is the canonical TDLib pattern (Levin in
     *     tdlib/td#3019: updates are the source of truth).
     *   - No 2 s wait for chatCache to fill. Updates either arrived
     *     synchronously during the LoadChats drain (warm cache) or will
     *     arrive on the wire (first-auth) — in both cases our listeners
     *     handle them.
     *   - No per-channel `chat.lastMessage` harvest pass. Each
     *     UpdateNewChat ingests its `lastMessage` directly in
     *     [handleNewChat]; the buffered-race case is covered by
     *     [pendingLastMessages].
     */
    suspend fun triggerInitialSync() {
        runTriggerInitialSync("triggerInitialSync", surfaceErrors = false)
    }

    private suspend fun runTriggerInitialSync(label: String, surfaceErrors: Boolean) {
        refreshMutex.withLock {
            runCatching {
                drainChatList(TdApi.ChatListMain())
                drainChatList(TdApi.ChatListArchive())
                _initialSyncDone.value = true
            }
                .onSuccess { lastRefreshAtMs = System.currentTimeMillis() }
                .warnUnlessCancelled(label)
                .onFailure {
                    if (surfaceErrors) {
                        it.surfaceTo(userMessages, res, dev.lyo.hortay.R.string.op_refresh_feed, connection.value)
                    }
                }
        }
    }

    /**
     * First-sign-in history backfill. Cold-start contract emits exactly one post
     * per channel (`chat.lastMessage` from `UpdateNewChat`) — perfect for
     * Telegram's chat-list UX but visibly thin for a feed reader. This method
     * fetches a few extra posts for the user's most-active channels so the feed
     * lands "already full" on a fresh sign-in.
     *
     * **Budget, per Aliaksei Levin (tdlib/td#743): `GetChatHistory` is capped
     * server-side at 30 requests / 30 seconds — sustained 1 RPC/sec.** Going
     * above that earns FLOOD_WAIT (code 420/429) on the whole account for
     * minutes-to-hours. We pick top-[BACKFILL_TOP_K] channels by
     * `chat.positions[ChatListMain].order` desc (TDLib's own activity sort
     * signal — same one Telegram uses to rank the chat list), throttle
     * [BACKFILL_THROTTLE_MS] between calls (1 s + 100 ms margin so a
     * concurrent user RPC doesn't push us over the bucket edge), and fetch
     * [BACKFILL_POSTS_PER_CHAT] messages per channel. Total runtime
     * ~[BACKFILL_TOP_K] × [BACKFILL_THROTTLE_MS] ≈ 22 s of background work.
     *
     * **Circuit-break on FLOOD_WAIT.** If a single call returns 420/429, stop
     * the loop and DO NOT mark the flag done. Reason: the account-global
     * `awaitFloodGate` will already pause every subsequent `td.send` for the
     * server's retry-after window — including user-driven RPCs (open chat,
     * load older). Continuing the backfill would extend the user-visible
     * stall for no UX gain. Next sign-in (or next launch after this one, if
     * we never marked done) retries.
     *
     * **One-shot per auth session.** Persisted via [coldStartBackfill]; reset
     * on TDLib `loggedOut` via the standard cleanup fan-out in
     * [dev.lyo.hortay.AppGraph]. Re-runs only on a fresh sign-in (different
     * account, or same account after logout) — which is exactly the
     * cold-start UX we're protecting; warm restarts already have TDLib's
     * `useMessageDatabase` cache.
     *
     * Idempotent if the app is killed mid-loop: [ingest] dedupes messages by
     * id, so on retry we may double-fetch a handful of GetChatHistory calls
     * but the feed converges to the same state.
     *
     * **What this is NOT.** Not a substitute for `loadChannelHistory` (the
     * single-channel deep load on drill). Not a periodic refresh. Not a
     * cure-all for "feed looks empty" — dormant channels in the long tail
     * still show one post each, and that's correct: backfilling 200 channels
     * is exactly the FLOOD_WAIT regression
     * [ARCHITECTURE.md → "PostsRepository cold-start contract"] forbids.
     */
    suspend fun runFirstSignInBackfill() {
        val store = coldStartBackfill ?: return
        if (store.isDone()) return

        // Snapshot top-K under the live state. Not held under refreshMutex —
        // the throttled fetch loop is too long (~22 s) to block pull-to-refresh
        // or [clear] on. A stale snapshot is fine: chat positions can shift
        // mid-backfill (a new UpdateNewMessage moves a channel up), but the
        // version we captured at sync-completion is a reasonable approximation
        // of "the user's top channels" and the live update stream picks up
        // post-snapshot deltas anyway.
        val topK = _mainChatIds.value.asSequence()
            .mapNotNull { chatCache[it] }
            .filter { it.isChannel() }
            .sortedByDescending { chat ->
                chat.positions?.firstOrNull { it.list is TdApi.ChatListMain }?.order ?: 0L
            }
            .take(BACKFILL_TOP_K)
            .toList()
        if (topK.isEmpty()) {
            // No subscribed channels — nothing to backfill but also nothing to
            // retry. Mark done so we don't re-walk the empty list on every
            // foreground transition; logout/login will reset the flag.
            store.markDone()
            return
        }

        for (chat in topK) {
            val res = runCatching {
                td.send(
                    TdApi.GetChatHistory(
                        chat.id,
                        /* fromMessageId = */ 0,
                        /* offset = */ 0,
                        /* limit = */ BACKFILL_POSTS_PER_CHAT,
                        /* onlyLocal = */ false,
                    )
                )
            }
            val err = res.exceptionOrNull()
            if (err is CancellationException) throw err
            if (err is TdClient.TdException && TdClient.isFloodWaitCode(err.code)) {
                // runCatching around Log.w to stay unit-testable — android.util.Log's
                // static stubs throw "not mocked" on the JVM. Same pattern as
                // [SnapshotRestorer.foldRawIntoCurrent].
                runCatching {
                    Log.w(
                        TAG,
                        "First-sign-in backfill hit FLOOD_WAIT (code=${err.code}) at chat=${chat.id}; " +
                            "circuit-break, retry next session"
                    )
                }
                return
            }
            // Other per-chat failures (chat became inaccessible, transient
            // TDLib hiccup) are non-fatal — log and move on. The next channel
            // in the top-K list is independent.
            err?.let { runCatching { Log.w(TAG, "Backfill GetChatHistory failed for chat=${chat.id}: ${it.message}") } }
            res.getOrNull()?.messages?.toList()?.takeIf { it.isNotEmpty() }
                ?.let { messages -> ingest(chat.id, messages) }
            delay(BACKFILL_THROTTLE_MS)
        }
        store.markDone()
    }

    /**
     * Tells TDLib the user is actively focused on [chatId]. The daemon prioritises updates
     * for this chat, prefetches history and treats subsequent [viewMessages] calls as
     * authoritative. Always pair with [closeChat] when focus moves away. Internally a
     * thin proxy to [ChatPresence] so all OpenChat/CloseChat traffic in the app flows
     * through one place.
     */
    suspend fun openChat(chatId: Long) = ChatPresence.openChat(td, chatId)

    /**
     * Loads up to [limit] additional history entries for [chatId] and folds them into the
     * shared feed. Used when the user filters to a single channel — a global refresh only
     * fetches a few latest posts per channel, so deep browsing one channel needs more.
     *
     * Single-flight: if a deep load is already in flight for [chatId], all callers await
     * the same [Deferred]. Cooldown: a successful load suppresses re-fetches for
     * [DEEP_LOAD_COOLDOWN_MS] — entering and leaving the channel filter back-to-back no
     * longer triggers a fresh GetChatHistory each time. Failed loads skip the cooldown
     * mark so the next entry retries.
     */
    /**
     * True when [loadChannelHistory] for [chatId] completed successfully within
     * the [DEEP_LOAD_COOLDOWN_MS] window — i.e. the in-memory `_posts` slice for
     * this channel is "warm" (the full per-channel head has landed, not just
     * the cold-start harvest artifact).
     *
     * Used by [dev.lyo.hortay.ui.timeline.ChannelViewModel] to seed
     * `_historyLoading` synchronously on construction: warm re-entry means
     * we already have the full head, so the channel screen lands Ready on
     * frame one without gating through `Resolving`. Cold first entry
     * returns false → the screen stays in `Resolving` until the deep load
     * lands.
     *
     * Note: the channel-open push site
     * ([dev.lyo.hortay.ui.main.MainScaffold]'s `pushChannel`) now awaits
     * [loadChannelHistory] with a short timeout BEFORE pushing
     * [dev.lyo.hortay.data.nav.ChannelKey], so in practice cold cases
     * still mount with a warm slice unless the network is genuinely slow.
     *
     * Read-only against the cooldown map; no side effects.
     */
    fun hasWarmChannelHistory(chatId: Long): Boolean {
        val until = deepLoadCooldownUntilMs[chatId] ?: return false
        return System.currentTimeMillis() < until
    }

    suspend fun loadChannelHistory(chatId: Long, limit: Int = 80): Result<Unit> {
        val now = System.currentTimeMillis()
        deepLoadCooldownUntilMs[chatId]?.let { until ->
            if (now < until) return Result.success(Unit)
        }
        val deferred = deepLoadJobs.computeIfAbsent(chatId) {
            scope.async { runCatching { loadChannelHistoryLocked(chatId, limit) } }
                // Compare-remove on completion, not after await(): if every awaiter
                // is cancelled the `deepLoadJobs.remove` below never runs, leaving the
                // finished Deferred cached — the next caller would await() a completed
                // result once (stale). invokeOnCompletion fires regardless of who is
                // (or isn't) still awaiting; the 2-arg remove only drops the entry if
                // it's still this exact Deferred (a fresh computeIfAbsent may already
                // have replaced it).
                .also { d -> d.invokeOnCompletion { deepLoadJobs.remove(chatId, d) } }
        }
        val result = deferred.await()
        // Mark cooldown only if we actually loaded posts. A "successful empty
        // batch" result (chat became inaccessible mid-load, transient TDLib
        // reject swallowed by getOrNull, GetChatHistory returned empty list)
        // shouldn't pin the channel out of fetches for 60 s — the user might
        // have just joined and is waiting for first content. The
        // [Result<Boolean>] contract: true = at least one mapped post landed,
        // false = success-shaped no-op.
        if (result.getOrNull() == true) {
            deepLoadCooldownUntilMs[chatId] = System.currentTimeMillis() + DEEP_LOAD_COOLDOWN_MS
        }
        return result
            .map { Unit }
            .warnUnlessCancelled(TAG, "loadChannelHistory($chatId)")
            .onFailure { it.surfaceTo(userMessages, res, dev.lyo.hortay.R.string.op_load_channel, connection.value) }
    }

    private suspend fun loadChannelHistoryLocked(chatId: Long, limit: Int): Boolean {
        val epoch = sessionEpoch.get()
        val chat = chatCache[chatId] ?: td.send(TdApi.GetChat(chatId)).also { chatCache[chatId] = it }
        if (!chat.isChannel()) return false

        val history = td.send(TdApi.GetChatHistory(chatId, /* fromMessageId */ 0, 0, limit, false))
        // On-demand single-channel open: one chat at a time, so a networked
        // surround fetch is within the FLOOD_WAIT budget.
        val raw = coalesceAlbumFragments(chatId, history.messages.orEmpty().toList(), onlyLocal = false)
        val mapped = raw.map { mapper.toChannelPost(it, chat) }
        if (mapped.isEmpty()) return false

        // Session-epoch guard (see [ingest]): a logout that wiped the feed while the
        // networked fetch above was in flight must not re-inject account A's history.
        if (sessionEpoch.get() != epoch) return false
        _posts.update { current -> foldRawIntoCurrent(current, mapped) }
        return true
    }

    /**
     * Load a window of history centered on [anchorMessageId]. Used when the user follows
     * a deep link to a specific old message — that message lives below the
     * [loadChannelHistory] head load, so we have to ask TDLib for the context around the
     * anchor explicitly.
     *
     * Canonical TDLib pattern (per docs on `GetChatHistory`):
     *   - `fromMessageId = anchorMessageId` — pivot.
     *   - `offset = -limit / 2` — fetch `limit/2` messages newer than the anchor and
     *     `limit/2` older. Negative offset is TDLib's "go forward in time" operator.
     *   - `onlyLocal = false` — allow a server round-trip; the whole point is to load
     *     messages that aren't in our local cache.
     *
     * Returns true if at least one mapped post landed, mirroring [loadChannelHistory]'s
     * contract (so callers can branch on emptiness — chat became inaccessible,
     * permission revoked, etc.).
     */
    suspend fun loadHistoryAround(chatId: Long, anchorMessageId: Long, limit: Int = 80): Boolean {
        val epoch = sessionEpoch.get()
        val chat = chatCache[chatId]
            ?: runCatching { td.send(TdApi.GetChat(chatId)) }
                .warnUnlessCancelled(TAG, "loadHistoryAround/getChat")
                .getOrNull()
                ?.also { chatCache[chatId] = it }
            ?: return false
        if (!chat.isChannel()) return false

        // Prime TDLib's local cache for the anchor BEFORE the history fetch. Per
        // Aliaksei Levin on tdlib/td#702: when TDLib has `have_full_history = true`
        // locally but the anchor isn't in the local DB,
        // `GetChatHistory(fromMessageId = anchor)` enters the "Have a gap near
        // message to get chat history from" loop and returns just the read-cursor
        // message — the anchor itself is silently missing from the response, the
        // around-load lands empty, and the deep-link UI falls through to Missing.
        // `GetMessage` forces the daemon to fetch the specific message into local
        // DB (or returns it from cache if already present); the subsequent
        // `GetChatHistory` then has a valid iterator point to walk back from.
        // Cheap when the anchor is already cached (offline lookup), one server
        // round-trip when it isn't.
        runCatching { td.send(TdApi.GetMessage(chatId, anchorMessageId)) }
            .warnUnlessCancelled(TAG, "loadHistoryAround/getMessage($chatId, $anchorMessageId)")

        val history = runCatching {
            td.send(TdApi.GetChatHistory(chatId, anchorMessageId, -(limit / 2), limit, false))
        }
            .warnUnlessCancelled(TAG, "loadHistoryAround($chatId, $anchorMessageId)")
            .getOrNull() ?: return false
        // On-demand deep-link / next-unread jump: networked surround fetch is fine.
        val raw = coalesceAlbumFragments(chatId, history.messages.orEmpty().toList(), onlyLocal = false)
        val mapped = raw.map { mapper.toChannelPost(it, chat) }
        if (mapped.isEmpty()) return false

        // Session-epoch guard (see [ingest]): don't re-inject a logged-out account's
        // history if a logout landed during the networked fetch above.
        if (sessionEpoch.get() != epoch) return false
        _posts.update { current -> foldRawIntoCurrent(current, mapped) }
        return true
    }

    suspend fun closeChat(chatId: Long) = ChatPresence.closeChat(td, chatId)

    /** Per-channel "we already paginated to the bottom of TDLib's local store" sentinel. */
    private val pageEnded = ConcurrentHashMap.newKeySet<Long>()
    private val pageJobs = ConcurrentHashMap<Long, Deferred<Result<Int>>>()

    /**
     * Pull older posts for a channel, anchored on the oldest post we currently render.
     * Used by the timeline when the user scrolls near the bottom of a single-channel
     * feed and wants to read further back.
     *
     * Returns the number of newly added posts. Single-flight + sticky end-of-history flag
     * so an over-eager scroll listener can't fan out duplicate round-trips and won't keep
     * pinging TDLib once we've already learnt the channel has nothing older to give.
     */
    suspend fun loadOlder(chatId: Long, limit: Int = 30): Int {
        if (chatId in pageEnded) return 0
        val deferred = pageJobs.computeIfAbsent(chatId) {
            scope.async {
                runCatching { loadOlderLocked(chatId, limit) }
            }
                // Compare-remove on completion (see [loadChannelHistory]): all-awaiters-
                // cancelled would otherwise strand the finished Deferred in the map and
                // hand the next caller a stale completed result.
                .also { d -> d.invokeOnCompletion { pageJobs.remove(chatId, d) } }
        }
        val result = deferred.await()
        return result
            .warnUnlessCancelled(TAG, "loadOlder($chatId)")
            .onFailure { it.surfaceTo(userMessages, res, dev.lyo.hortay.R.string.op_load_older, connection.value) }
            .getOrDefault(0)
    }

    private suspend fun loadOlderLocked(chatId: Long, limit: Int): Int {
        val epoch = sessionEpoch.get()
        val oldestId = _posts.value
            .filter { it.chatId == chatId }
            .minOfOrNull { it.id }
            ?: return 0
        val chat = chatCache[chatId] ?: td.send(TdApi.GetChat(chatId)).also { chatCache[chatId] = it }
        if (!chat.isChannel()) return 0

        val history = td.send(
            TdApi.GetChatHistory(
                chatId,
                /* fromMessageId */ oldestId,
                /* offset */ 0,
                limit,
                /* onlyLocal */ false,
            ),
        )
        val raw = history.messages.orEmpty().toList()
        // TDLib returns an empty page once we've walked off the end of its locally-stored
        // history. Mark the sentinel so the scroll listener stops pinging.
        if (raw.isEmpty()) {
            pageEnded += chatId
            return 0
        }
        // Pagination scroll-down inside one channel: networked surround fetch is fine.
        val coalesced = coalesceAlbumFragments(chatId, raw, onlyLocal = false)
        val mapped = coalesced.map { mapper.toChannelPost(it, chat) }

        // Session-epoch guard (see [ingest]): a logout during the fetch above must not
        // re-inject the previous account's older posts into the wiped feed.
        if (sessionEpoch.get() != epoch) return 0
        var prevChannelSize = 0
        var nextChannelSize = 0
        _posts.update { current ->
            prevChannelSize = current.count { it.chatId == chatId }
            val result = foldRawIntoCurrent(current, mapped)
            nextChannelSize = result.count { it.chatId == chatId }
            result
        }
        // End-of-history detection: GetChatHistory(fromMessageId, offset=0, …) is
        // INCLUSIVE on the boundary message — at the channel's first-ever post TDLib
        // keeps returning that single message in a non-empty page. Treat "no net new
        // posts of this channel landed" as the sentinel and stop pinging. Counting on
        // chatId-specific cardinality (not whole-feed delta) keeps unrelated ingest
        // racing alongside this update from masking the actual end-of-history.
        val added = (nextChannelSize - prevChannelSize).coerceAtLeast(0)
        if (added == 0) pageEnded += chatId
        return added
    }

    /**
     * Full-text search inside a single channel. Returns mapped posts ordered newest-first,
     * which is how `SearchChatMessages` itself returns them — TDLib already paginates with
     * the offset/limit we pass, so this method is a single round-trip.
     *
     * Coalesces fragments from the same media album just like the regular timeline pipeline,
     * so a hit on a caption-bearing photo doesn't appear without its sibling photos.
     */
    suspend fun searchInChannel(chatId: Long, query: String, limit: Int = 50): List<TimelinePost> {
        if (query.isBlank()) return emptyList()
        val chat = chatCache[chatId] ?: runCatching { td.send(TdApi.GetChat(chatId)) }
            .warnUnlessCancelled(TAG, "searchInChannel/getChat")
            .onFailure { it.surfaceTo(userMessages, res, dev.lyo.hortay.R.string.op_search, connection.value) }
            .getOrNull()?.also { chatCache[chatId] = it } ?: return emptyList()
        // Search failures used to be silently swallowed → empty list, leaving the
        // user wondering whether the channel really has nothing matching or
        // whether the query failed (FLOOD_WAIT, transient TDLib reject…).
        // Surface the failure to the same message bus that other user-initiated
        // operations route through; callers still get the empty list so the
        // UI's "no results" state renders normally.
        val result = runCatching {
            td.send(
                TdApi.SearchChatMessages(
                    chatId,
                    /* topicId */ null,
                    query,
                    /* senderId */ null,
                    /* fromMessageId */ 0,
                    /* offset */ 0,
                    limit,
                    /* filter */ null,
                ),
            )
        }
            .warnUnlessCancelled(TAG, "searchInChannel")
            .onFailure { it.surfaceTo(userMessages, res, dev.lyo.hortay.R.string.op_search, connection.value) }
            .getOrNull() ?: return emptyList()

        val raw = result.messages.orEmpty().toList()
        // In-channel search result: networked surround fetch is fine.
        val coalesced = coalesceAlbumFragments(chatId, raw, onlyLocal = false)
        val mapped = coalesced.map { mapper.toChannelPost(it, chat) }
        return PostFilterStrategy.apply(mapped)
    }

    /**
     * Synchronous subscriber count read for a channel chat. Returns `null` when
     * either the [chatCache] or [supergroupCache] mirror is missing the chat /
     * supergroup, or the chat is not a supergroup, or TDLib has not yet delivered
     * a positive count for it.
     *
     * Why a separate function from [channelSubscribers]: this one is called from
     * [ChannelViewModel]`.init` to seed [ChannelViewModel.channelSubscribers]'s
     * [StateFlow] **before** the first composition collects it — which is the
     * only way to make the subtitle paint with the count already populated
     * instead of as a `null` for one frame followed by a recomposition once
     * the suspend variant resolves. The suspend variant is then launched as a
     * fallback for the cold-cache case.
     *
     * For any channel that has surfaced in the merged feed at least once this
     * session (the common case for every non-deep-link entry point) both
     * mirrors are warm and this returns the count immediately.
     */
    fun channelSubscribersCached(chatId: Long): Int? {
        val chat = chatCache[chatId] ?: return null
        val supergroupId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: return null
        return supergroupCache[supergroupId]?.memberCount?.takeIf { it > 0 }
    }

    /**
     * Subscriber count for a channel chat. Returns `null` when the chat is not a
     * supergroup (private 1:1, basic group) or TDLib reports an unknown count.
     *
     * Resolution order, fast → slow:
     *   1. [channelSubscribersCached] — synchronous mirror read; common case
     *      because [TdApi.UpdateNewChat] / [TdApi.UpdateSupergroup] populate
     *      both mirrors eagerly on session warm-up.
     *   2. [TdApi.GetSupergroup] RPC fallback — for the rare cold-cache case
     *      (channel opened via deep-link before any feed refresh; freshly
     *      joined channel whose Supergroup update is still in flight). Served
     *      from TDLib's own local cache in steady state, so even this branch
     *      avoids the network in the common case.
     */
    suspend fun channelSubscribers(chatId: Long): Int? {
        channelSubscribersCached(chatId)?.let { return it }
        // Cold-cache fallback. Skip [GetChat] when the cached Chat already
        // yielded the supergroupId — only [GetSupergroup] is missing.
        val cachedChat = chatCache[chatId]
        val supergroupId = (cachedChat?.type as? TdApi.ChatTypeSupergroup)?.supergroupId
            ?: run {
                val chat = runCatching { td.send(TdApi.GetChat(chatId)) }
                    .warnUnlessCancelled(TAG, "channelSubscribers/getChat")
                    .getOrNull() ?: return null
                chatCache[chatId] = chat
                (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: return null
            }
        val sg = runCatching { td.send(TdApi.GetSupergroup(supergroupId)) }
            .warnUnlessCancelled(TAG, "channelSubscribers/getSupergroup")
            .getOrNull() ?: return null
        // Mirror into [supergroupCache] so subsequent reads (re-entry into the
        // same channel within this session) hit the fast path even when the
        // [TdApi.UpdateSupergroup] burst was missed at session start.
        supergroupCache[supergroupId] = sg
        return sg.memberCount.takeIf { it > 0 }
    }

    /**
     * Chat title as TDLib knows it. Cheap: TDLib serves [TdApi.GetChat] from its local
     * cache after the first resolve (SearchPublicChat, JoinChat, or any other chat
     * touch). Used by the channel-filter top bar to show a non-subscribed channel's
     * name before [loadChannelHistory] populates the merged feed — same UX
     * Telegram-Android offers when you open a public channel preview.
     */
    suspend fun chatTitle(chatId: Long): String? {
        val chat = chatCache[chatId]
            ?: runCatching { td.send(TdApi.GetChat(chatId)) }
                .warnUnlessCancelled(TAG, "chatTitle")
                .getOrNull()
                ?.also { chatCache[chatId] = it }
        return chat?.title?.takeIf { it.isNotBlank() }
    }

    /**
     * Channel avatar source for the [ChannelScreen] header. Returns
     * (smallFileId, minithumbBytes) — both nullable, both fed into [TdAvatar]
     * which paints the 3-tier ladder (minithumb → file → initial letter). Same
     * `chatCache → GetChat` lookup pattern as [chatTitle] / [channelSubscribers]:
     * served from TDLib's local cache after the first touch, no network in
     * steady state. Either field may be null for channels whose [TdApi.Chat.photo]
     * is itself null (rare — channel without a profile photo).
     */
    suspend fun chatAvatar(chatId: Long): Pair<Int?, ByteArray?>? {
        val chat = chatCache[chatId]
            ?: runCatching { td.send(TdApi.GetChat(chatId)) }
                .warnUnlessCancelled(TAG, "chatAvatar")
                .getOrNull()
                ?.also { chatCache[chatId] = it }
            ?: return null
        return chat.photo?.small?.id to chat.photo?.minithumbnail?.data
    }

    /**
     * Resolve a Telegram public `@handle` (without the leading `@`) to a TDLib chat id.
     * Used by the deep-link dispatcher (`tg://resolve` / `https://t.me/<handle>`) so a
     * tap on a shared link inside Hortay routes the user to the channel filter — no
     * round-trip through the official Telegram client. TDLib serves the resolved chat
     * from cache when known, otherwise hits the server once and writes through.
     *
     * Returns null when the handle doesn't exist, points at a user/bot we can't surface
     * as a channel filter, or the request fails. Callers fall through to a generic
     * "open external" action in that case.
     */
    suspend fun resolvePublicChat(handle: String): Long? {
        val cleaned = handle.removePrefix("@").trim()
        if (cleaned.isBlank()) return null
        return runCatching { td.send(TdApi.SearchPublicChat(cleaned)).id }
            .warnUnlessCancelled(TAG, "resolvePublicChat($cleaned)")
            .getOrNull()
    }

    /**
     * Typed variant of [resolvePublicChat] used by the deep-link dispatcher. Returns:
     *
     *   - [PublicHandleResult.Channel] — the handle resolves to a broadcast channel
     *     (`ChatTypeSupergroup` with `isChannel = true`). Caller switches the feed
     *     filter to [chatId].
     *
     *   - [PublicHandleResult.User] — the handle resolves to a 1:1 user or bot
     *     (`ChatTypePrivate`). Caller opens the in-app
     *     [dev.lyo.hortay.ui.users.UserProfileSheet] so an `@username` mention lands
     *     on the same surface as an in-text `TextEntityTypeMentionName` tap.
     *
     *   - [PublicHandleResult.Unsupported] — the handle is a real Telegram entity but
     *     not something Hortay can render today (basic group, supergroup that isn't
     *     a channel). Caller surfaces a user-facing message and offers to hand off
     *     via the OS chooser.
     *
     *   - [PublicHandleResult.NotFound] — TDLib couldn't resolve the handle at all
     *     (`SearchPublicChat` 4xx / network failure). Caller treats as silent miss.
     */
    suspend fun resolvePublicHandle(handle: String): PublicHandleResult {
        val cleaned = handle.removePrefix("@").trim()
        if (cleaned.isBlank()) return PublicHandleResult.NotFound
        val chat = runCatching { td.send(TdApi.SearchPublicChat(cleaned)) }
            .warnUnlessCancelled(TAG, "resolvePublicHandle($cleaned)")
            .getOrNull() ?: return PublicHandleResult.NotFound
        chatCache.putIfAbsent(chat.id, chat)
        return chat.toPublicHandleResult()
    }

    /**
     * Twin of [resolvePublicHandle] keyed on a TDLib chat id rather than a public handle.
     * Used by deep-link variants that already carry an id (`DeepLink.Message`,
     * `DeepLink.PrivateChannel`) so the dispatcher can verify the target IS a channel
     * before flipping `channelFilter`. Without the check, a `t.me/c/.../<msg>` link
     * that points at a basic group / supergroup-chat / user DM would land the user on
     * a channel filter that never paginates (loadChannelHistory short-circuits on
     * `!chat.isChannel()`), reading as a frozen empty view.
     *
     * Returns [PublicHandleResult.NotFound] when TDLib has no record of the chat
     * (private chat the user lost access to, transient network failure on GetChat).
     */
    suspend fun resolveChatKind(chatId: Long): PublicHandleResult {
        val chat = chatCache[chatId]
            ?: runCatching { td.send(TdApi.GetChat(chatId)) }
                .warnUnlessCancelled(TAG, "resolveChatKind($chatId)")
                .getOrNull()
                ?.also { chatCache[chatId] = it }
            ?: return PublicHandleResult.NotFound
        return chat.toPublicHandleResult()
    }

    private fun TdApi.Chat.toPublicHandleResult(): PublicHandleResult {
        val t = type
        return when {
            isChannel() -> PublicHandleResult.Channel(id)
            // Private chat = 1:1 user / bot. Carry the userId through so the deep-link
            // dispatcher can route the tap to UserProfileSheet instead of bouncing the
            // user out to the official Telegram client. Reading from `ChatTypePrivate`
            // keeps this correct even if TDLib ever decouples chat.id from user.id.
            t is TdApi.ChatTypePrivate -> PublicHandleResult.User(t.userId)
            t is TdApi.ChatTypeBasicGroup -> PublicHandleResult.Unsupported(PublicHandleKind.Group)
            t is TdApi.ChatTypeSupergroup -> PublicHandleResult.Unsupported(PublicHandleKind.Group)
            else -> PublicHandleResult.Unsupported(PublicHandleKind.Unknown)
        }
    }

    /**
     * Canonical `https://t.me/...` share URL for a post, minted by TDLib's offline
     * `GetMessageLink`. TDLib owns the correct format for albums (`?single=…` markers,
     * forum-topic prefixes, comment-thread suffixes) we'd otherwise re-implement; this
     * one call defers all of that to the daemon.
     *
     * Returns null when:
     *   - The post belongs to a synthesised guest-mode chat ([chatId] below
     *     [dev.lyo.hortay.ui.actions.PostActions]'s guest threshold) — there is no
     *     TDLib chat to query against.
     *   - TDLib refuses (`messageProperties.canGetLink == false`, restricted source).
     *   - The native send fails for any other reason.
     *
     * Callers fall back to a hand-rolled URL in those cases. The method is suspending
     * but `GetMessageLink` is offline (microseconds over JNI), so latency is invisible
     * inside the share / open-in-Telegram flow.
     *
     * For album posts we anchor on the first member id with `forAlbum = true`, matching
     * Telegram-Android's "copy link to album" behaviour.
     */
    suspend fun canonicalShareUrl(post: TimelinePost): String? {
        val anchorId = post.albumMessageIds.firstOrNull() ?: post.id
        val forAlbum = post.albumMessageIds.size > 1
        val query = TdApi.GetMessageLink(post.chatId, anchorId, 0, 0, "", forAlbum, false)
        val response = runCatching { td.send(query) }
            .warnUnlessCancelled(TAG, "canonicalShareUrl(${post.chatId}, ${post.id})")
            .getOrNull()
        return response?.link?.takeIf { it.isNotBlank() }
    }

    /**
     * Registers that the user has seen the given messages in [chatId]. Bumps the
     * server-side view counter AND advances [TdApi.Chat.lastReadInboxMessageId] —
     * i.e. the channel's unread badge in the official Telegram client clears as the
     * user reads here.
     *
     * Maintainer-aligned design (TDLib's `Aliaksei Levin` aka levlam):
     *   - tdlib/td#2695: "Usually, users have at most one chat opened." → we DO NOT
     *     hold OpenChat for every visible chat in the global feed; only the focused
     *     chat in TimelineScreen and the active channel-filter screen open their
     *     single chat (see [openChat]). Multi-open is a fight against the API design
     *     and risks burst FLOOD_WAIT on a 200-channel scroll.
     *   - tdlib/td#46 + tdlib/td#219: when the chat isn't opened, the canonical way to
     *     advance read state is `force_read=true` on `ViewMessages`. That's the
     *     non-focused case in the merged feed.
     *   - tdlib/td#136: `ViewMessages` is filtered server-side to messages TDLib
     *     considers "seen" (since 1.3.0), so calling it for a viewport-stable batch is
     *     safe even if the user only briefly glanced.
     *   - tdlib/td#2312: "Only a few messages can be viewed in a time." Caller must
     *     batch sensibly — TimelineScreen passes the visible viewport (3-7 posts), well
     *     within bounds. Do NOT bulk-ack the whole feed from this function.
     *   - `force_read` flag is decided per-call from [ChatPresence.isOpen] rather than
     *     hardcoded `true`: for an OPEN chat (focus-tracker or ChannelScreen target),
     *     `force_read=true` is API misuse — the documented contract scopes the flag
     *     to closed chats only, and observed TDLib behaviour (per the tdlib/td#2312
     *     thread) is to deprioritise the interaction-info stream when the daemon
     *     treats the call as a background batch-ack. With `force_read=false` on open
     *     chats, reaction / view / reply updates flow live; with `force_read=true` on
     *     closed chats, read state still advances even though we won't see live
     *     reactions for those posts. The branching lives here because it's a TDLib
     *     contract detail, not a UX policy.
     *
     * For album posts: [TimelinePost.id] is the oldest album member's id, which matches
     * tdlib/td#2312's note that "only the first message in an album can receive
     * reactions" — the same id is the canonical one for view/read tracking too.
     *
     * The dwell gate (≥1s viewport-stable before this is called) lives in
     * [TimelineScreen]: that's a UX policy, not a TDLib invariant, so it stays at the
     * call site.
     *
     * **Optimistic local advance.** Before the RPC we advance [_chatReadCursors] to the
     * highest id in [messageIds] (monotonic — see [advanceReadCursor]). The cursor is the
     * single source of truth the UI reads (unread strip, ↓N counter, next-unread jump
     * target), and the only other producer — TDLib's `UpdateChatReadInbox` echo — is
     * monotonic-clamped too, so the echo reconciles as a no-op when it lands. Without this
     * the cursor moved ONLY on the server round-trip; with `force_read=false` on an open
     * (focused) chat TDLib defers that echo, so the read posts stayed lit and the
     * next-unread pill kept re-landing on the same post until something else churned the
     * feed. This mirrors guest mode (`WebRepository.markChannelRead` writes the cursor
     * locally) and the optimistic reaction flips elsewhere in the app.
     */
    suspend fun viewMessages(chatId: Long, messageIds: List<Long>) {
        messageIds.maxOrNull()?.let { advanceReadCursor(chatId, it) }
        val open = ChatPresence.isOpen(chatId)
        ChatPresence.viewMessages(
            td = td,
            chatId = chatId,
            messageIds = messageIds,
            // ChatHistory: the user is reading the channel feed (merged global view
            // or single-channel filter). Both look like history scrolling to TDLib.
            source = TdApi.MessageSourceChatHistory(),
            // See KDoc: closed → force_read=true (advances read state via the
            // documented closed-chat path); open → force_read=false (canonical
            // "user is actively reading", which is what gates the interaction-info
            // stream we depend on for live reactions / views / replies).
            forceRead = !open,
        )
    }

    /**
     * Canonical handler for [TdApi.UpdateNewChat]. Three responsibilities, in order:
     *
     *  1. **Cache + read cursor seed.** Mirror the chat POJO and (when TDLib has a
     *     positive signal) seed [_chatReadCursors] from `lastReadInboxMessageId`.
     *     The `0/0` shape is ambiguous and intentionally NOT seeded — see
     *     tdlib/td#1419 for the outgoing-only-channel invariant.
     *
     *  2. **List-membership hydration.** Read [TdApi.Chat.positions] and add the
     *     chat id to [_mainChatIds] / [_archivedChatIds] for each [TdApi.ChatList]
     *     match. Positions may be empty on first-auth (filled in later via
     *     UpdateChatPosition / UpdateChatAddedToList — handled by a separate
     *     listener). When non-empty, this gives the ingest filter early data
     *     so we can flip [_initialSyncDone] sooner.
     *
     *  3. **Lead-message ingest.** Prefer a payload buffered by
     *     UpdateChatLastMessage that raced this update; fall back to
     *     `chat.lastMessage`. When non-null and the chat is a channel, route
     *     through [ingest] — no waiting, no harvest, no semaphore.
     *
     * Called from the [TdApi.UpdateNewChat] listener; suspending because [ingest]
     * may suspend on its own GetChat fallback or on the [_posts] CAS loop.
     */
    private suspend fun handleNewChat(chat: TdApi.Chat) {
        chatCache[chat.id] = chat
        seedReadCursor(chat)
        hydrateChatListMembership(chat)
        val msg = pendingLastMessages.remove(chat.id) ?: chat.lastMessage
        if (msg != null && chat.isChannel()) ingest(chat.id, listOf(msg))
    }

    /**
     * Monotonic advance of the local read cursor for [chatId] to [cursor]. The single
     * clamp site for the two ADVANCE producers — TDLib's `UpdateChatReadInbox` echo and
     * the optimistic on-ack advance in [viewMessages]. A put that would rewind the cursor
     * (id ≤ the stored value) is dropped, so the echo and the optimistic advance can fire
     * in any order without re-lighting already-read posts. TDLib has been observed to emit
     * redundant inbox resets with smaller ids during chat-repair flows; the clamp keeps the
     * cursor from rewinding under us.
     *
     * Distinct from [seedReadCursor], which is the SEED producer: it may create an entry at
     * `cursor == 0` for a freshly-joined channel that has `unreadCount > 0` but no read
     * position yet, so its posts render the unread strip. This advance path requires a
     * positive id (`cursor <= 0` is a no-op) — a zero here would never advance anything.
     */
    private fun advanceReadCursor(chatId: Long, cursor: Long) {
        if (cursor <= 0L) return
        _chatReadCursors.update { current ->
            val existing = current[chatId] ?: 0L
            if (cursor <= existing) current else current.put(chatId, cursor)
        }
    }

    private fun seedReadCursor(chat: TdApi.Chat) {
        val cursor = chat.lastReadInboxMessageId
        val hasReadState = cursor > 0L || chat.unreadCount > 0
        if (!hasReadState) return
        _chatReadCursors.update { current ->
            val existing = current[chat.id]
            // Monotonic clamp: a stale UpdateNewChat arriving after a fresh
            // UpdateChatReadInbox must NOT roll the cursor backwards, else
            // already-read posts re-appear as unread.
            if (existing != null && cursor <= existing) current
            else current.put(chat.id, cursor)
        }
    }

    private fun hydrateChatListMembership(chat: TdApi.Chat) {
        val positions = chat.positions ?: return
        if (positions.isEmpty()) return
        for (pos in positions) {
            when (pos.list) {
                is TdApi.ChatListMain -> _mainChatIds.update { it + chat.id }
                is TdApi.ChatListArchive -> _archivedChatIds.update { it + chat.id }
                else -> Unit
            }
        }
    }

    private fun handleNewMessage(message: TdApi.Message) {
        if (message.mediaAlbumId == 0L) {
            scope.launch { ingest(message.chatId, listOf(message)) }
            return
        }
        // Album member: stash in the per-album buffer and (re)arm a short debounce.
        // Each subsequent sibling resets the timer; once the burst quietens we flush
        // every accumulated member in a single _posts.update so PostFilterStrategy
        // sees them as one group.
        val key = message.chatId to message.mediaAlbumId
        albumBuffers.compute(key) { _, existing ->
            (existing ?: mutableListOf()).also { buf ->
                // Dedup by id: the same member can arrive via UpdateNewMessage AND
                // the UpdateChatLastMessage echo; double-adding would duplicate the
                // album item after the merge.
                if (buf.none { it.id == message.id }) buf += message
            }
        }
        albumDebounce[key]?.cancel()
        // Compare-remove idiom (see MediaCache.schedulePostCompletionResync): a stale
        // job that survived cancellation must not `remove(key)` the NEW timer's entry.
        // If it did, a third sibling arriving next would find no timer to cancel and
        // arm a second job → two jobs race to flush the same album, one against a
        // half-drained buffer. The 2-arg remove only clears the entry if it's still
        // this exact job.
        lateinit var jobRef: Job
        jobRef = scope.launch {
            delay(ALBUM_DEBOUNCE_MS)
            albumDebounce.remove(key, jobRef)
            val batch = albumBuffers.remove(key) ?: return@launch
            ingest(key.first, batch)
        }
        albumDebounce[key] = jobRef
    }

    private suspend fun ingest(chatId: Long, messages: List<TdApi.Message>) {
        // Capture the session epoch before any suspend point. A logout→clear() that
        // lands while we're parked on the GetChat fallback / networked album coalesce
        // below bumps this; we re-check before writing so a resumed ingest can't
        // re-populate the wiped feed with the previous account's data.
        val epoch = sessionEpoch.get()
        val chat = chatCache[chatId] ?: runCatching { td.send(TdApi.GetChat(chatId)) }
            .getOrNull()
            ?.also { chatCache[it.id] = it }
            ?: return
        if (!chat.isChannel()) return
        // No subscription filter at ingest. `_posts` is the global post pool —
        // every chat the daemon resolves (subscribed channels, deep-link
        // previews, linked discussion-group parents) contributes through this
        // single path. The merged feed surface ([subscribedPosts]) filters
        // strictly against [_mainChatIds] / [_archivedChatIds] downstream, so
        // a non-subscribed chat's lastMessage landing in `_posts` is invisible
        // to TimelineScreen.
        //
        // The previous shape filtered at ingest time and ran into a race:
        // `UpdateNewChat` typically arrives before `UpdateChatAddedToList`,
        // so a chat whose membership hadn't yet been signaled was filtered
        // out, then the membership-signal arrived too late to retroactively
        // ingest. Trusting the downstream filter sidesteps the race entirely
        // at a memory cost of a few transient rows (typically 0–3 per
        // session for side-resolved chats).

        // If a real-time burst still left an album fragmented (e.g. members spread across
        // >600 ms by upstream), probe the chat for the missing siblings before mapping.
        // Cheap if there are no fragments — early-returns immediately.
        //
        // Stay OFFLINE during the cold-start drain ([_initialSyncDone] still
        // false): ~200 channels stream their lastMessage at once, so a networked
        // surround fetch per album member would be the GetChatHistory x N storm
        // the cold-start contract forbids (FLOOD_WAIT — levlam, tdlib/td#743).
        // Locally-cached siblings still merge; the rest are repaired by the
        // post-drain snapshot pass or an on-demand open. After the drain settles
        // a live arrival is one chat at a time, so networking is back on.
        val full = coalesceAlbumFragments(chatId, messages, onlyLocal = !_initialSyncDone.value)

        val newPosts = full
            .map { mapper.toChannelPost(it, chat) }
            .filter { it.content !is PostContent.Unsupported }
        if (newPosts.isEmpty()) return

        // A logout wiped the feed while we were suspended above (GetChat /
        // coalesceAlbumFragments). Bail before writing so account A's posts don't
        // land in account B's (or the empty AuthScreen's) freshly-cleared feed.
        if (sessionEpoch.get() != epoch) return

        // Route the live ingest through the same album-aware merge helper as the
        // on-demand paths (loadChannelHistory / loadOlder / loadHistoryAround /
        // restoreFromSnapshot). The helper:
        //   - rejects partial album batches that would downgrade an existing
        //     complete merged card (the third converging fix for the
        //     "5-photo card becomes 2-photo" class of bugs);
        //   - dedups raw against every albumMessageIds entry, not just the
        //     anchor id, so a late sibling doesn't stack on top of the merged
        //     anchor and produce a duplicated items list;
        //   - re-runs PostFilterStrategy so album members re-merge cleanly and
        //     the feed cap is honoured.
        //
        // Earlier this method had its own bespoke prune-then-PostFilterStrategy
        // logic that lacked the partial-album-downgrade guard — so live arrivals
        // (UpdateNewMessage debounce flush, UpdateChatLastMessage) could replace
        // a complete 5-photo merged card with a partial 2-photo batch. That
        // regression is closed by routing through [foldRawIntoCurrent].
        //
        // newArrivals semantics. We emit only posts whose anchor id is not in
        // the pre-fold snapshot, computed from the (chatId, anchorId) key
        // before the update. The diff is captured inside the `update` lambda
        // so the final successful retry's value wins (earlier retries may
        // overwrite — correct, because we want the addition computed against
        // the state that actually got written).
        var addedForEmit: List<TimelinePost> = emptyList()
        _posts.update { current ->
            val before = current.mapTo(HashSet()) { it.chatId to it.id }
            val next = foldRawIntoCurrent(current, newPosts)
            addedForEmit = next.filter { (it.chatId to it.id) !in before }
            next
        }
        // Emit AFTER the state write so any listener sees a consistent feed.
        // tryEmit can never block under DROP_OLDEST, so we don't risk back-pressuring
        // the ingest path on a slow downstream collector.
        for (post in addedForEmit) _newArrivals.tryEmit(post)

        // Archive baseline capture: write a "first-observed" VERSION row so
        // every post that reaches the feed has at least one snapshot in the
        // archive. Two shapes:
        //   * `editDate == 0` — genuine as-published anchor (priorEditedAtMs = null).
        //   * `editDate != 0` — post was already edited before we saw it; we
        //     record THIS state with `priorEditedAtMs = msg.editDate` so the
        //     row is truthfully tagged as "first observed in edited state".
        //
        // Pre-fix, the `editDate != 0` branch was skipped entirely. That left
        // any later `UpdateDeleteMessages` for those posts producing an
        // **orphan DELETED row** — no VERSION to JOIN against, so the
        // tombstone-feed rendered an empty card (no title, no text, no
        // media). The reader saw mysterious blank rows at the bottom of the
        // feed. selectTombstonesJoined is now INNER JOIN so any remaining
        // orphans are hidden, and this branch prevents new orphans by
        // capturing the pre-edited state instead of dropping it.
        // Same epoch guard before the archive baseline fan-out — a logout landing
        // between the _posts write above and here must not spawn baseline captures
        // for account A into the archive account B is about to reuse.
        if (archiveRepository?.isEnabled() == true && addedForEmit.isNotEmpty() && sessionEpoch.get() == epoch) {
            val addedIds = addedForEmit.mapTo(HashSet()) { it.chatId to it.id }
            for (raw in full) {
                if ((raw.chatId to raw.id) !in addedIds) continue
                scope.launch { captureBaselineSnapshot(raw, chat) }
            }
        }
    }

    /**
     * Captures the as-published baseline VERSION row for a freshly-ingested
     * message. The archive repository pins `seen_at_ms = originalDateMs` and
     * uses `selectFirstSeenForMessage` existence — not content hash — as its
     * idempotency check, so this call is race-safe against an
     * `UpdateMessageEdited` that lands for the same message before the
     * baseline coroutine wins `writeMutex` (see
     * [ArchiveRepository.captureTdlibBaseline] KDoc).
     *
     * Errors are swallowed (caller has no recovery): archive misses on a
     * single post are recoverable on the next user-visible edit.
     */
    private suspend fun captureBaselineSnapshot(
        message: TdApi.Message,
        chat: TdApi.Chat,
    ) {
        val repo = archiveRepository ?: return
        val mediaSha = archiveMediaStore?.let { store ->
            MediaFileFromContent.extract(message.content)?.let { store.copyIfAvailable(it) }
        }
        val baseMeta = TdlibContentMetaExtractor.extract(message.content)
        val meta = if (mediaSha != null && baseMeta.mediaRef != null) {
            baseMeta.copy(mediaRef = baseMeta.mediaRef.copy(localArchiveSha = mediaSha))
        } else baseMeta
        runCatching {
            repo.captureTdlibBaseline(
                chat = ChatRef.tdlib(chat.id),
                messageKey = message.id.toString(),
                albumKey = message.mediaAlbumId.takeIf { it != 0L }?.toString(),
                meta = meta,
                originalDateMs = message.date.toLong() * 1000L,
                isComment = false,
                priorEditedAtMs = message.editDate
                    .takeIf { it > 0 }
                    ?.toLong()
                    ?.times(1000L),
            )
            val livePost = _posts.value.firstOrNull { it.chatId == chat.id && it.id == message.id }
            if (livePost != null) {
                repo.upsertChannel(
                    chat = ChatRef.tdlib(chat.id),
                    title = livePost.senderName,
                    handle = livePost.senderHandle,
                    photoMinithumb = livePost.avatarThumb,
                    isVerified = livePost.verification != null,
                )
            }
        }.warnUnlessCancelled(TAG, "captureBaselineSnapshot(${chat.id},${message.id})")
    }

    /**
     * Layer-2 visibility repair: request a networked rebuild of a degraded album
     * (one member, `mediaAlbumId != 0`) that just became focused. Deduped per
     * (chatId, mediaAlbumId) and throttled by the single-consumer drain below; a
     * no-op for non-albums. The dedup key is held for the whole throttle window
     * (cleared after the post-dispatch delay), so concurrent requests and a card
     * scrolling in/out/in within the window coalesce into one repair; a still-cold
     * album re-enqueues on the next focus after the key clears.
     */
    fun requestAlbumRepair(chatId: Long, anchorId: Long, mediaAlbumId: Long) {
        if (mediaAlbumId == 0L) return
        if (albumRepairQueued.add(chatId to mediaAlbumId)) {
            albumRepairRequests.trySend(AlbumRepairRequest(chatId, anchorId, mediaAlbumId))
        }
    }

    private suspend fun performAlbumRepair(req: AlbumRepairRequest) {
        val chat = chatCache[req.chatId] ?: return
        if (!chat.isChannel()) return
        // The anchor is the degraded card already in _posts, so this GetMessage is
        // a local cache hit (per-message index); the only call that actually reaches
        // the server is the sibling window below (coalesceAlbumFragments onlyLocal=false).
        val anchor = runCatching { td.send(TdApi.GetMessage(req.chatId, req.anchorId)) }
            .warnUnlessCancelled(TAG, "albumRepairAnchor(${req.chatId},${req.anchorId})").getOrNull() ?: return
        // The surround GetChatHistory may legitimately under-return: TDLib serves
        // the locally-available slice — often exactly the anchor the GetMessage
        // above just cached — without filling the whole window from the server in
        // one pass ("the number of returned messages ... can be smaller than the
        // specified limit"; the documented client pattern is to repeat the call).
        // Retry a bounded number of times: the first call warms TDLib's history
        // around the anchor, the retry reads the warmed cache. Without this, a
        // single under-return left the degraded 1-photo card with no later rescue
        // — re-enqueue only happens on a focus change back to this card.
        var coalesced: List<TdApi.Message> = emptyList()
        for (attempt in 1..ALBUM_REPAIR_FETCH_ATTEMPTS) {
            coalesced = coalesceAlbumFragments(req.chatId, listOf(anchor), onlyLocal = false)
            if (coalesced.size > 1) break
            if (attempt < ALBUM_REPAIR_FETCH_ATTEMPTS) delay(ALBUM_REPAIR_RETRY_DELAY_MS)
        }
        if (coalesced.size <= 1) return // still couldn't complete it; leave the degraded card
        val mapped = coalesced.map { mapper.toChannelPost(it, chat) }
            .filter { it.content !is PostContent.Unsupported }
        if (mapped.isEmpty()) return
        // Logout may have wiped state during the networked coalesce/map suspension;
        // chatCache is cleared by clear(), so a miss here means don't re-inject the
        // previous account's posts into a freshly-cleared feed.
        if (chatCache[req.chatId] == null) return
        _posts.update { foldRawIntoCurrent(it, mapped) }
    }

    /**
     * Telegram albums (2–10 messages sharing one `mediaAlbumId`) have no completion
     * signal on the wire — TDLib maintainer levlam confirms in
     * [tdlib/td#1482](https://github.com/tdlib/td/issues/1482): *"There is no way
     * to know this. You need to use some timeout."* Any batch we receive may
     * therefore carry 1..N members of an album whose remaining siblings are
     * still pending. Three paths drop us into the partial case:
     *  - **Window edge.** `GetChatHistory(N)` returns the latest N messages; an
     *    album straddling positions N..N+k is split between this response and
     *    the next page.
     *  - **Debounce flush race.** [handleNewMessage] buffers album members for
     *    [ALBUM_DEBOUNCE_MS]; on slow mobile networks (3G/Roaming) per-member
     *    arrival can exceed the silence window, flushing 2..9 members while
     *    siblings are still in flight. Same failure mode as openclaw#1811 (their
     *    500 ms wasn't enough; their fix bumped to 1000–1500 ms — we use 1000).
     *  - **UpdateNewChat lead-message ingest.** [handleNewChat] routes the
     *    chat's `lastMessage` (or a buffered `UpdateChatLastMessage` payload)
     *    through this method on every chat appearing in the session; if
     *    `lastMessage` is an album member, the batch is trivially `size=1`.
     *
     * **Coalesce trigger.** Surround-fetch fires for any album group whose
     * member count is `<` [TELEGRAM_MAX_ALBUM_SIZE]. The previous `size == 1`
     * filter only rescued single fragments — a 2..9 partial fell through and
     * the merged card landed with the partial item count. Triggering up to
     * size-1 is correct because Telegram's protocol caps an album at 10 members
     * (see TDLib `sendMessageAlbum`), so a batch of exactly 10 is provably
     * complete and skips the fetch.
     *
     * **Window sizing.** `offset = -(MAX_ALBUM - 1)`, `limit = 2 * MAX_ALBUM - 1`
     * (i.e. -9, 19). TDLib semantics: returns up to 9 newer than the anchor,
     * the anchor itself, and up to 9 older — covering every possible anchor
     * position inside a 10-member album. The previous `-5, 10` reached only
     * 5 newer + 4 older around the anchor, so a 10-member album whose
     * `lastMessage` is the highest-id member (the canonical cold-start case)
     * returned only 5 of 10.
     *
     * Concurrency: each fragment fires parallel requests — [TdApi.GetMessageLocally]
     * on the cold-start (onlyLocal) path, [TdApi.GetChatHistory] on the networked
     * path — merged synchronously after [awaitAll] so the seen-set has no race.
     * Bounded by distinct album ids in the input — typically 0..2 per batch.
     */
    private suspend fun coalesceAlbumFragments(
        chatId: Long,
        messages: List<TdApi.Message>,
        // `false` allows a server round-trip (on-demand paths + Layer-2 visibility
        // repair: one chat at a time, within the 30 req / 30 s budget). `true` is the
        // cold-start path: ~200 channels stream their lastMessage at once, so it must
        // stay strictly offline. Rather than GetChatHistory(onlyLocal=true) — which
        // reads the chat's in-memory history list, unbuilt before OpenChat, and so
        // returns just the lastMessage even when siblings are cached — derive the
        // sibling ids from the channel id-stride invariant and pull them through the
        // per-message local index ([GetMessageLocally]). That index works on a cold
        // history cache (same reason snapshot restore uses GetMessage). Members not in
        // the local DB stay degraded until the Layer-2 visibility repair fetches them.
        onlyLocal: Boolean,
    ): List<TdApi.Message> {
        val fragments = messages
            .filter { it.mediaAlbumId != 0L }
            .groupBy { it.mediaAlbumId }
            .values
            .filter { it.size < TELEGRAM_MAX_ALBUM_SIZE }
            .map { it.first() }
        if (fragments.isEmpty()) return messages

        val seen = messages.mapTo(hashSetOf()) { it.id }
        val extras = coroutineScope {
            fragments.map { fragment ->
                async {
                    if (onlyLocal) fetchAlbumSiblingsLocally(chatId, fragment)
                    else fetchAlbumSiblingsNetworked(chatId, fragment)
                }
            }.awaitAll()
        }

        if (extras.all { it.isEmpty() }) return messages
        val merged = messages.toMutableList()
        for (group in extras) {
            for (m in group) if (seen.add(m.id)) merged += m
        }
        return merged
    }

    /**
     * Offline sibling recovery for the cold-start path: address each computed
     * candidate id via [TdApi.GetMessageLocally] (404 → not in the local DB). Capped
     * concurrency so a batch of albums doesn't spike TDLib's worker thread during the
     * drain. Keeps only resolved messages that share the fragment's mediaAlbumId,
     * so deletions/gaps and a back-to-back neighbouring album filter out naturally.
     */
    private suspend fun fetchAlbumSiblingsLocally(
        chatId: Long,
        fragment: TdApi.Message,
    ): List<TdApi.Message> = coroutineScope {
        val semaphore = Semaphore(SNAPSHOT_RESTORE_CONCURRENCY)
        albumCandidateIds(fragment.id).map { candidateId ->
            async {
                semaphore.withPermit {
                    runCatching { td.send(TdApi.GetMessageLocally(chatId, candidateId)) }
                        .getOrElse { e -> if (e is kotlin.coroutines.cancellation.CancellationException) throw e else null }
                        ?.takeIf { it.mediaAlbumId == fragment.mediaAlbumId }
                }
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * Networked sibling recovery (on-demand opens + Layer-2 repair): the surround
     * GetChatHistory window. offset=-(MAX-1), limit=2*MAX-1 spans a full 10-member
     * album from any anchor position.
     */
    private suspend fun fetchAlbumSiblingsNetworked(
        chatId: Long,
        fragment: TdApi.Message,
    ): List<TdApi.Message> {
        val resp = runCatching {
            td.send(
                TdApi.GetChatHistory(
                    chatId,
                    /* fromMessageId */ fragment.id,
                    /* offset */ -(TELEGRAM_MAX_ALBUM_SIZE - 1),
                    /* limit */ 2 * TELEGRAM_MAX_ALBUM_SIZE - 1,
                    /* onlyLocal */ false,
                ),
            )
        }.warnUnlessCancelled(TAG, "coalesceAlbum($chatId,${fragment.id})").getOrNull()
        return resp?.messages.orEmpty().filter { it.mediaAlbumId == fragment.mediaAlbumId }
    }

    /**
     * Buffer the update; if a flush isn't already scheduled, schedule one. A single
     * coroutine drains the buffer after [INTERACTION_INFO_COALESCE_MS] and writes all
     * pending mutations in one `_posts.update { mutate { ... } }` call — the persistent
     * list builds the new snapshot once, regardless of how many keys we touch.
     */
    private fun handleInteractionInfo(update: TdApi.UpdateMessageInteractionInfo) {
        // null interactionInfo: original handler resolved every field to its current value
        // (effectively no-op). Drop here so the buffer stays non-null for ConcurrentHashMap.
        val info = update.interactionInfo ?: return
        pendingInteractionInfo.merge(update.chatId to update.messageId, info) { existing, incoming ->
            TdApi.MessageInteractionInfo().apply {
                // viewCount is monotonic per Telegram; take max so a slightly-stale heartbeat
                // can't downgrade a fresher value already in the buffer.
                viewCount = maxOf(existing.viewCount, incoming.viewCount)
                // forwardCount: TDLib emits 0 to mean "not changed" in heartbeats too,
                // so keep the prior non-zero value when the incoming says zero.
                forwardCount = if (incoming.forwardCount != 0) incoming.forwardCount
                else existing.forwardCount
                // reactions / replyInfo: null in the incoming update means "see your local
                // copy" — fall through to the existing buffered value, NOT overwrite it.
                reactions = incoming.reactions ?: existing.reactions
                replyInfo = incoming.replyInfo ?: existing.replyInfo
            }
        }
        if (interactionFlushScheduled.compareAndSet(false, true)) {
            scope.launch {
                delay(INTERACTION_INFO_COALESCE_MS)
                interactionFlushScheduled.set(false)
                flushPendingInteractionInfo()
            }
        }
    }

    private fun flushPendingInteractionInfo() {
        if (pendingInteractionInfo.isEmpty()) return
        // Single-pass drain: snapshot what's there now, atomically remove only those
        // entries. Updates that arrive *after* the snapshot stay in the map and trip the
        // next compareAndSet, so we don't lose any.
        val drained = HashMap<Pair<Long, Long>, TdApi.MessageInteractionInfo>()
        val it = pendingInteractionInfo.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            drained[e.key] = e.value
            it.remove()
        }
        if (drained.isEmpty()) return

        // Album-aware lookup: an update's messageId may target ANY member of an
        // already-merged album, but post.id is the anchor (oldest member). Build a
        // (chatId, memberId) → postIdx index covering the anchor AND every
        // albumMessageIds entry once, then dispatch each drained event in O(1).
        // Without this fallback, views / reactions / commentCount updates for
        // non-anchor album members were silently dropped — the user-visible
        // symptom was reactions never appearing on photo-album posts, because
        // TDLib only fills MessageReactions via these updates after the initial
        // GetChatHistory response (which returns interactionInfo with reactions=null).
        // Mirrors the same album-id normalisation handleEdited /
        // handleIsPinnedChanged / handleContentChanged already do via
        // updateOnePostByAnyMemberId.
        _posts.update { current ->
            current.mutate { list ->
                val byMessageId = HashMap<Pair<Long, Long>, Int>(list.size * 2)
                for (i in list.indices) {
                    val post = list[i]
                    byMessageId[post.chatId to post.id] = i
                    for (memberId in post.albumMessageIds) {
                        if (memberId != post.id) byMessageId[post.chatId to memberId] = i
                    }
                }
                for ((key, info) in drained) {
                    val idx = byMessageId[key] ?: continue
                    val post = list[idx]
                    list[idx] = post.copy(
                        // Max instead of overwrite: for an album, every member can fire
                        // its own UpdateMessageInteractionInfo against this anchor's idx,
                        // and the per-member viewCount can lag (TDLib catching up after
                        // a reconnect, individual member view count slightly behind the
                        // aggregate). Telegram view counts are monotonically
                        // non-decreasing per message, so taking the max — both against
                        // the post's previous value AND across the burst of member
                        // updates that flow through this loop — never downgrades a card
                        // that already showed a higher number.
                        views = maxOf(post.views, info.viewCount),
                        // forwardCount is monotonic like views; the same per-album-member
                        // burst can fire against this anchor's idx, so take the max so a
                        // lagging member's update never downgrades the shown count. The
                        // drained buffer already kept the prior non-zero value when a
                        // heartbeat reported 0 (see handleInteractionInfo merge).
                        forwardCount = maxOf(post.forwardCount, info.forwardCount),
                        // Preserve current reactions/comments when the inner field is null —
                        // TDLib often omits sub-fields it hasn't recomputed. Per
                        // tdlib/td#2312, only the first album member ever carries non-null
                        // reactions / replyInfo, so the null-preserve branch is what
                        // protects the merged card against non-first members' updates
                        // overwriting the live aggregate with empties.
                        reactions = info.reactions?.let(::reactionsFromUpdate) ?: post.reactions,
                        commentCount = info.replyInfo?.replyCount ?: post.commentCount,
                    )
                }
            }
        }
    }

    private fun handleEdited(update: TdApi.UpdateMessageEdited) {
        // For an album the edit (almost always a caption tweak) targets one specific
        // sub-message id, but our merged anchor's id may be a different sibling. Stamp
        // editDate on the anchor whose albumMessageIds contains the touched id so the
        // "edited" badge refreshes regardless of which member the edit landed on.
        //
        // editDate semantics (tdlib/td#2294): UME with editDate == 0 fires for
        // non-edit changes (reactions appearing in big supergroups, fact-check
        // additions, etc.) — it's an internal "something changed" signal, not a
        // human/bot edit. Only editDate > 0 should stamp the badge AND trigger
        // archive capture.
        if (update.editDate <= 0) return

        // editDate stamp + revisionCount bump together — UME(editDate > 0) is the
        // single canonical signal for "this post was edited", so the EditedChip's
        // count comes from the same path as the badge itself. Counting per
        // UpdateMessageContent instead would inflate on poll votes / live-loc /
        // paid-media reveals (any non-edit UMC).
        updateOnePostByAnyMemberId(update.chatId, update.messageId) {
            it.copy(
                editDate = update.editDate.toLong() * 1000L,
                revisionCount = it.revisionCount + 1,
            )
        }

        // Archive: capture VERSION row regardless of `_posts` membership. The
        // `livePost` lookup is best-effort metadata only — it carries
        // `mediaAlbumId` (needed to tag the row with `album_key`) and the
        // sender info for upsertChannel. **Crucially, a null livePost must NOT
        // skip the archive write**, because cold-start catch-up via
        // `getChannelDifference` emits `UpdateMessageEdited` for posts that
        // never entered `_posts` this session (top-30 backfill + lastMessage
        // ingest doesn't cover older messages). Pre-fix, those edits were
        // silently dropped from the archive.
        //
        // The mapped TimelinePost in _posts is the wrong source for the content
        // itself — it strips entities and would produce a different hash than
        // the canonical TdApi.MessageContent path (see TdlibContentMetaExtractor
        // KDoc on the phantom-edit regression). Content comes from
        // [pendingArchiveEdits] (UMC arrived first, common path) or
        // [TdApi.GetMessage] as a fallback when UME beat UMC.
        if (archiveRepository == null) return
        val livePost = _posts.value.firstOrNull { p ->
            p.chatId == update.chatId &&
                (p.id == update.messageId || update.messageId in p.albumMessageIds)
        }
        val buffered = pendingArchiveEdits.commitOnEdited(update.chatId, update.messageId)
        scope.launch {
            val content: TdApi.MessageContent = buffered
                ?: runCatching { td.send(TdApi.GetMessage(update.chatId, update.messageId)) }
                    .warnUnlessCancelled(TAG, "getMessage(archive,${update.chatId},${update.messageId})")
                    .getOrNull()?.content
                ?: return@launch
            // Tier 2 media copy: while TDLib still has the file locally, snapshot
            // the bytes into archive storage. The resulting SHA is folded into
            // mediaRef so the revision sheet can render the media even after the
            // original message is deleted or TDLib evicts the file from cache.
            val mediaSha = archiveMediaStore?.let { store ->
                MediaFileFromContent.extract(content)?.let { store.copyIfAvailable(it) }
            }
            val baseMeta = TdlibContentMetaExtractor.extract(content)
            val meta = if (mediaSha != null && baseMeta.mediaRef != null) {
                baseMeta.copy(mediaRef = baseMeta.mediaRef.copy(localArchiveSha = mediaSha))
            } else baseMeta
            archiveRepository.captureTdlibEdit(
                chat = ChatRef.tdlib(update.chatId),
                messageKey = update.messageId.toString(),
                albumKey = livePost?.mediaAlbumId?.takeIf { it != 0L }?.toString(),
                editedAtMs = update.editDate.toLong() * 1000L,
                meta = meta,
                isComment = false,
            )
            // upsertChannel only when we have live metadata. Channels seen
            // earlier in the session already have rows in ArchivedChannel from
            // their baseline captures; missing this upsert on a catch-up edit
            // just leaves stale (but valid) channel metadata.
            if (livePost != null) {
                archiveRepository.upsertChannel(
                    chat = ChatRef.tdlib(update.chatId),
                    title = livePost.senderName,
                    handle = livePost.senderHandle,
                    photoMinithumb = livePost.avatarThumb,
                    isVerified = livePost.verification != null,
                )
            }
        }
    }

    private fun handleDeleted(update: TdApi.UpdateDeleteMessages) {
        if (!update.isPermanent) return

        // Single read of the archive-enabled gate up front. Capture and feed-side
        // branches use the SAME observation, so a Settings flip between them can't
        // strand a ghost in the feed without a backing snapshot, or vice versa.
        val archiveEnabled = archiveRepository?.isEnabled() == true

        // Archive: append the deleted ids into a per-chat debounce buffer, then
        // drain through `captureTdlibDeleteSmart` after a 200 ms quiet pause.
        // **Debounce is per chat, not per album.** The previous shape grouped
        // by `_posts.value`'s `mediaAlbumId`, which was unreachable on
        // cold-start catch-up — `getChannelDifference` emits
        // `UpdateDeleteMessages` for posts the user never scrolled to, leaving
        // `mediaAlbumId` unrecoverable from the feed and the entire delete
        // event dropped from the archive. Routing every id through
        // `captureTdlibDeleteSmart` lets the archive recover album grouping
        // from its own VERSION history (`selectAlbumKeyForMessage`) instead;
        // the per-chat debounce still collapses split pulses for live admin
        // deletes (same 200 ms window as before).
        if (archiveEnabled && archiveRepository != null) {
            val msgIds = update.messageIds.toList()
            pendingChatDeletions.compute(update.chatId) { _, prev ->
                (prev ?: mutableListOf()).also { buf -> buf.addAll(msgIds) }
            }
            chatDeletionTimers[update.chatId]?.cancel()
            // Compare-remove (see handleNewMessage): a stale surviving job must not
            // clear the fresh timer's entry, or a later delete batch would find no
            // timer to cancel and race a second flush against a half-drained buffer.
            lateinit var deletionJobRef: Job
            deletionJobRef = scope.launch {
                delay(ALBUM_DELETE_DEBOUNCE_MS)
                chatDeletionTimers.remove(update.chatId, deletionJobRef)
                val drained = pendingChatDeletions.remove(update.chatId) ?: return@launch
                archiveRepository.captureTdlibDeleteSmart(
                    chat = ChatRef.tdlib(update.chatId),
                    messageKeys = drained.map { msgId -> msgId.toString() },
                    isComment = false,
                )
                // Best-effort channel metadata from any feed-resident sample
                // for this chat — channels that have a baseline already have
                // an ArchivedChannel row anyway, so a miss here is fine.
                _posts.value.firstOrNull { it.chatId == update.chatId }?.let { sample ->
                    archiveRepository.upsertChannel(
                        chat = ChatRef.tdlib(update.chatId),
                        title = sample.senderName,
                        handle = sample.senderHandle,
                        photoMinithumb = sample.avatarThumb,
                        isVerified = sample.verification != null,
                    )
                }
            }
            chatDeletionTimers[update.chatId] = deletionJobRef
        }

        val ids = update.messageIds.toHashSet()
        if (archiveEnabled) {
            // Archive mode: tombstone deleted posts with isDeleted=true so a
            // DeletedBadge can surface in the feed instead of the post vanishing silently.
            // Albums whose anchor is fully deleted also get tombstoned; partially-deleted
            // albums are trimmed the same as non-archive mode (individual members have no
            // separate card the user could read, so trimming is lossless from UX perspective).
            _posts.update { current ->
                current.mutate { list ->
                    val toRemove = mutableListOf<Int>()
                    for (i in list.indices) {
                        val post = list[i]
                        if (post.chatId != update.chatId) continue
                        val albumIds = post.albumMessageIds
                        if (albumIds.isEmpty()) {
                            if (post.id in ids) list[i] = post.copy(isDeleted = true)
                            continue
                        }
                        // Album: if every member was deleted, tombstone the anchor.
                        val survivedIds = albumIds.filterNot { it in ids }
                        if (survivedIds.size == albumIds.size) continue
                        if (survivedIds.isEmpty()) {
                            list[i] = post.copy(isDeleted = true)
                            continue
                        }
                        // Partial album deletion: trim members that were removed.
                        val keepIdx = albumIds.withIndex()
                            .filter { (_, id) -> id !in ids }
                            .map { (idx, _) -> idx }
                            .toSet()
                        val content = post.content
                        if (content is PostContent.PhotoAlbum) {
                            val newItems = content.items.filterIndexed { idx, _ -> idx in keepIdx }
                            list[i] = post.copy(
                                content = content.copy(items = newItems),
                                albumMessageIds = survivedIds,
                            )
                        } else {
                            toRemove += i
                        }
                    }
                    for (idx in toRemove.asReversed()) list.removeAt(idx)
                }
            }
        } else {
            _posts.update { current ->
                current.mutate { list ->
                    val toRemove = mutableListOf<Int>()
                    for (i in list.indices) {
                        val post = list[i]
                        if (post.chatId != update.chatId) continue
                        val albumIds = post.albumMessageIds
                        if (albumIds.isEmpty()) {
                            if (post.id in ids) toRemove += i
                            continue
                        }
                        // Album: trim deleted members from items[] (mergeAlbumMembers builds
                        // items in albumMessageIds order, so they correspond by index). Drop
                        // the whole post if every member was deleted.
                        val survivedIds = albumIds.filterNot { it in ids }
                        if (survivedIds.size == albumIds.size) continue
                        if (survivedIds.isEmpty()) {
                            toRemove += i
                            continue
                        }
                        val keepIdx = albumIds.withIndex()
                            .filter { (_, id) -> id !in ids }
                            .map { (idx, _) -> idx }
                            .toSet()
                        val content = post.content
                        if (content is PostContent.PhotoAlbum) {
                            val newItems = content.items.filterIndexed { idx, _ -> idx in keepIdx }
                            list[i] = post.copy(
                                content = content.copy(items = newItems),
                                albumMessageIds = survivedIds,
                            )
                        } else {
                            // Album with non-PhotoAlbum content (shouldn't happen given how
                            // mergeAlbumMembers builds groups, but guard anyway). Drop it.
                            toRemove += i
                        }
                    }
                    for (idx in toRemove.asReversed()) list.removeAt(idx)
                }
            }
        }
    }

    private fun handleContentChanged(update: TdApi.UpdateMessageContent) {
        // Locate the post the update targets — either by anchor id or by
        // membership in an already-merged album's `albumMessageIds`. We do
        // the lookup BEFORE deciding the strategy because the right branch
        // depends on whether the post is an album at all, not just on
        // whether its anchor id matches the update.
        //
        // The previous shape gated on "anchor.id == update.messageId" via
        // [updateOnePost] and treated that as solo. That misclassified
        // album anchor edits: TDLib emits `UpdateMessageContent(messageId
        // = M1, newContent = MessagePhoto)` whenever an admin edits the
        // caption (Telegram attaches captions to the anchor — per
        // tdlib/td#2312, the first album member is the caption-carrier).
        // The naive `.copy(content = mapper.map(newContent))` replaced
        // the merged `PhotoAlbum(items = [5 photos])` with
        // `PhotoAlbum(items = [1 photo from M1])` while `albumMessageIds`
        // still claimed 5 siblings — inconsistent state, UI rendered
        // `content.items`, the 5-photo card visibly collapsed to 1 photo.
        // The user-visible regression was "card was correct, then a
        // second later it shrank to one image".
        //
        // The right gate is [TimelinePost.mediaAlbumId]: anything with a
        // non-zero album id must re-ingest the whole group, regardless of
        // which member id the update names.
        // Archive: stash the new content into the pairing buffer. Capture itself
        // happens in handleEdited when a paired UpdateMessageEdited(editDate > 0)
        // confirms this is a real admin edit (not a poll vote / live loc / etc.).
        // See PendingEditBuffer KDoc for the schema-based rationale.
        if (archiveRepository != null) {
            pendingArchiveEdits.stash(update.chatId, update.messageId, update.newContent)
        }

        val target = _posts.value.firstOrNull { post ->
            post.chatId == update.chatId &&
                (post.id == update.messageId || update.messageId in post.albumMessageIds)
        } ?: return

        if (target.mediaAlbumId == 0L) {
            // Solo post — fast path, swap content in place. revisionCount is
            // NOT bumped here: UMC fires for any content mutation (poll votes,
            // live-location ticks, paid-media reveals); the chip's counter is
            // bumped from handleEdited(editDate > 0) only.
            updateOnePost(update.chatId, update.messageId) {
                it.copy(content = MessageContentMapper.map(update.newContent, res))
            }
            return
        }

        // Album member edit (anchor OR sibling). Re-ingest the whole album
        // so the merged card stays consistent: GetMessage(touched id) →
        // handleNewMessage → debounce → ingest → coalesceAlbumFragments
        // rescues the siblings → foldRawIntoCurrent replaces the merged
        // anchor cleanly with the freshly-coalesced 5-member set carrying
        // the new caption.
        //
        // Note: we deliberately do not bump editDate here — the paired
        // UpdateMessageEdited event arrives separately and is the
        // authoritative source. The re-ingest resyncs the full album
        // content; any intermediate "edited" badge would beat the
        // official update only by a frame and isn't worth the
        // read-modify-write on _posts.
        scope.launch {
            val msg = runCatching { td.send(TdApi.GetMessage(update.chatId, update.messageId)) }
                .warnUnlessCancelled(TAG, "getMessage(${update.chatId},${update.messageId})")
                .getOrNull() ?: return@launch
            handleNewMessage(msg)
            // revisionCount intentionally not bumped here. The paired
            // UpdateMessageEdited(editDate > 0) is the canonical edit signal —
            // handleEdited bumps the count once per real admin edit. Bumping in
            // both places (UMC + UME) double-counted album edits.
        }
    }

    /**
     * Optimistic reaction toggle for the feed. The UI applies the obvious local effect
     * (chip flips, count adjusts by one) BEFORE the RPC, then dispatches the real
     * `AddMessageReaction` / `RemoveMessageReaction` via [ChannelActionsRepository].
     * The eventual `UpdateMessageInteractionInfo` overwrites this state with server
     * truth on the same `_posts.update` path the live update stream uses
     * ([flushPendingInteractionInfo]) — so a slightly-off optimistic guess can never
     * stick. On RPC failure, the caller invokes this method again with the inverted
     * [nowChosen] to roll back the visual change.
     *
     * Album-aware: pass any member id and the anchor's reactions are flipped — keeps
     * one card in lockstep with however TDLib happens to address the message.
     */
    fun applyOptimisticReaction(
        chatId: Long,
        messageId: Long,
        kind: ReactionKind,
        nowChosen: Boolean,
    ) {
        updateOnePostByAnyMemberId(chatId, messageId) {
            it.copy(reactions = ReactionTogglePolicy.apply(it.reactions, kind, nowChosen))
        }
    }

    /**
     * Optimistic poll-vote flip. The UI marks selected rows with [PollOption.isBeingChosen]
     * BEFORE the RPC, so the tap feels instant (a Material progress shimmer rides the row).
     * The eventual `UpdateMessageContent` from TDLib overwrites the local guess with server
     * truth via [handleContentChanged] — percentages and counts settle there. On RPC failure
     * the caller invokes [clearPollPending] with `revert=true` to undo the flip.
     *
     * [chosenIndices] is the post-tap selection set keyed off [PollOption.index]:
     *   * `[]` → user is retracting (regular polls only — quiz rejects server-side);
     *   * `[i]` → single-answer poll, or one pick in a multi-answer commit;
     *   * `[i, j, …]` → multi-answer poll commit.
     */
    fun applyOptimisticPollAnswer(
        chatId: Long,
        messageId: Long,
        chosenIndices: IntArray,
    ) {
        val selection: Set<Int> = chosenIndices.toHashSet()
        updateOnePostByAnyMemberId(chatId, messageId) { post ->
            val poll = post.content as? PostContent.Poll ?: return@updateOnePostByAnyMemberId post
            val newOptions = poll.options.map { opt ->
                opt.copy(
                    isChosen = opt.index in selection,
                    isBeingChosen = opt.index in selection,
                )
            }
            post.copy(
                content = poll.copy(
                    options = newOptions.toPersistentList(),
                    hasVoted = newOptions.any { it.isChosen },
                ),
            )
        }
    }

    /**
     * Drop [PollOption.isBeingChosen] shimmer set by [applyOptimisticPollAnswer]. Called from
     * the action repository's coroutine when the SetPollAnswer RPC settles — on success the
     * eventual `UpdateMessageContent` carries the final percentages so we just drop the
     * shimmer. On failure ([revert]=true) we also reset `isChosen` on previously-being-chosen
     * rows so the visible flip is undone.
     */
    fun clearPollPending(chatId: Long, messageId: Long, revert: Boolean) {
        updateOnePostByAnyMemberId(chatId, messageId) { post ->
            val poll = post.content as? PostContent.Poll ?: return@updateOnePostByAnyMemberId post
            if (poll.options.none { it.isBeingChosen }) return@updateOnePostByAnyMemberId post
            val newOptions = poll.options.map { opt ->
                if (!opt.isBeingChosen) opt
                else opt.copy(
                    isBeingChosen = false,
                    isChosen = if (revert) false else opt.isChosen,
                )
            }
            post.copy(
                content = poll.copy(
                    options = newOptions.toPersistentList(),
                    hasVoted = newOptions.any { it.isChosen },
                ),
            )
        }
    }

    private fun handleIsPinnedChanged(update: TdApi.UpdateMessageIsPinned) {
        // Pin badge: TDLib pins one specific message; for an album in our timeline that
        // can be any sibling (Telegram typically pins the caption-carrier, but admins
        // can pin any). Match by either anchor id or any album member id and set the
        // anchor's isPinned to the update's value.
        updateOnePostByAnyMemberId(update.chatId, update.messageId) {
            it.copy(isPinned = update.isPinned)
        }
    }

    /**
     * Single-post update helper. PersistentList's `set(idx, value)` returns a new
     * snapshot in O(log N) via structural sharing — none of the unchanged entries are
     * copied. Compare with the old `current.toMutableList().also { it[idx] = ... }`
     * which copied the whole array on every event.
     *
     * Returns true iff a matching post was found and updated, so callers can chain a
     * fallback (e.g. album-aware lookup) without re-walking the list.
     */
    private inline fun updateOnePost(
        chatId: Long,
        messageId: Long,
        crossinline transform: (TimelinePost) -> TimelinePost,
    ): Boolean {
        var hit = false
        _posts.update { current ->
            val idx = current.indexOfFirst { it.chatId == chatId && it.id == messageId }
            if (idx == -1) current
            else { hit = true; current.set(idx, transform(current[idx])) }
        }
        return hit
    }

    /**
     * Same as [updateOnePost] but matches by anchor id OR any of the post's
     * [TimelinePost.albumMessageIds]. Use this for events whose [messageId] can refer
     * to any member of an already-merged album (UpdateMessageEdited,
     * UpdateMessageIsPinned, UpdateMessageContent on a non-anchor sibling).
     */
    private inline fun updateOnePostByAnyMemberId(
        chatId: Long,
        messageId: Long,
        crossinline transform: (TimelinePost) -> TimelinePost,
    ): Boolean {
        var hit = false
        _posts.update { current ->
            val idx = current.indexOfFirst { post ->
                post.chatId == chatId &&
                    (post.id == messageId || messageId in post.albumMessageIds)
            }
            if (idx == -1) current
            else { hit = true; current.set(idx, transform(current[idx])) }
        }
        return hit
    }

    private fun handleChatTitle(update: TdApi.UpdateChatTitle) {
        // Drop the cached TdApi.Chat instead of mutating its `title` in place.
        // Multiple workers read chatCache[chatId] concurrently (handleNewChat,
        // ingest, coalesceAlbum…) and TdApi.Chat is a plain Java POJO without
        // internal synchronisation — a mid-flight reader could otherwise see
        // torn fields (fresh title paired with stale photo). The next reader
        // falls back to `td.send(GetChat(...))`, which is an offline read off
        // TDLib's own local cache and returns a fully-constructed new object.
        chatCache.remove(update.chatId)
        val newTitle = update.title.orEmpty()
        // Per-row decision logic (channel-as-sender vs personal-author) lives in
        // [applyChatTitleToFeed] in `ChannelMetadataSync.kt`.
        _posts.update { current -> applyChatTitleToFeed(current, update.chatId, newTitle) }
    }

    private fun handleChatPhoto(update: TdApi.UpdateChatPhoto) {
        // See [handleChatTitle] — same rationale for invalidating the entry
        // rather than mutating it in place.
        chatCache.remove(update.chatId)
        val newThumb = update.photo?.minithumbnail?.data
        val newFileId = update.photo?.small?.id
        // Per-row decision logic in [applyChatPhotoToFeed] (`ChannelMetadataSync.kt`).
        _posts.update { current -> applyChatPhotoToFeed(current, update.chatId, newThumb, newFileId) }
    }

    private fun reactionsFromUpdate(reactions: TdApi.MessageReactions): Reactions =
        MessageContentMapper.mapReactions(reactions)

    /**
     * Set of chatIds the user has archived in Telegram. Populated by the same
     * dual path as [_mainChatIds]: [handleNewChat] reads `chat.positions`
     * for the warm cache, and UpdateChatAddedToList catches the late-fill
     * first-auth case after [triggerInitialSync]'s `LoadChats(Archive)`
     * drain. Stays a [StateFlow] so the "Архів" tab can show / hide based
     * on whether the user actually has anything archived.
     */
    private val _archivedChatIds = MutableStateFlow<Set<Long>>(emptySet())
    val archivedChatIds: StateFlow<Set<Long>> = _archivedChatIds.asStateFlow()

    // Set of chatIds in the user's main chat list. Populated incrementally by
    // [handleNewChat] (reads `chat.positions` snapshot) and the
    // UpdateChatAddedToList listener (catches the late-fill case where
    // positions were empty at UpdateNewChat time — the classic cold-start
    // race). Filter source for [ingest] — live UpdateNewMessage from a chat
    // not in this set OR [archivedChatIds] is dropped.
    private val _mainChatIds = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * Wipe every piece of per-account in-memory state held by this repo.
     * Called from [AppGraph]'s logout handler in response to
     * [TdClient.loggedOut] so account A's cached posts / chat metadata /
     * pagination cursors don't leak into account B if the user signs into a
     * different Telegram account in the same process.
     *
     * Cancels in-flight per-channel deep loads + album debounces so they
     * can't land after the wipe and re-pollute the cleaned state.
     * Also drops the snapshot the previous session persisted so a cold
     * restart between sign-out and sign-in doesn't paint stale content.
     */
    suspend fun clear() {
        refreshMutex.withLock {
            _posts.value = persistentListOf()
            chatCache.clear()
            supergroupCache.clear()
            _chatReadCursors.value = persistentMapOf()
            pendingInteractionInfo.clear()
            interactionFlushScheduled.set(false)
            albumBuffers.clear()
            albumDebounce.values.forEach { it.cancel() }
            albumDebounce.clear()
            albumRepairQueued.clear()
            // Drain any queued repair requests so a logout→login doesn't replay account A's.
            while (albumRepairRequests.tryReceive().isSuccess) { /* discard */ }
            deepLoadJobs.values.forEach { it.cancel() }
            deepLoadJobs.clear()
            deepLoadCooldownUntilMs.clear()
            pageEnded.clear()
            pageJobs.values.forEach { it.cancel() }
            pageJobs.clear()
            lastRefreshAtMs = 0L
            _archivedChatIds.value = emptySet()
            _mainChatIds.value = emptySet()
            pendingLastMessages.clear()
            pendingArchiveEdits.clear()
            pendingChatDeletions.clear()
            chatDeletionTimers.values.forEach { it.cancel() }
            chatDeletionTimers.clear()
            _initialSyncDone.value = false
            // Bump last, inside the mutex: any ingest that captured the old epoch and
            // is parked on a suspend point will see the change and bail before writing.
            sessionEpoch.incrementAndGet()
        }
        runCatching { snapshotStore.clear() }
            .warnUnlessCancelled(TAG, "snapshotStore.clear")
    }

    /**
     * Repeatedly call [TdApi.LoadChats] until TDLib runs out of pages for [list]. TDLib
     * signals "no more chats to load" with a `404 Not Found` error specifically. Outcomes:
     *   - **404** → the list is fully drained; return normally (success).
     *   - **success** → a page loaded, more may remain; clear the error latch and loop.
     *   - **[CancellationException]** → rethrow immediately. Swallowing it (the old
     *     shape did) detached a cancelled PTR from its parent and let the caller stamp
     *     `lastRefreshAtMs` / flip `_initialSyncDone` as if the drain had succeeded.
     *   - **other error** (network down, auth race) → latch it, pause
     *     [DRAIN_RETRY_DELAY_MS] so an unhealthy TDLib isn't hammered back-to-back, and
     *     retry on the next iteration.
     *
     * If the bounded loop exits WITHOUT ever seeing a 404 AND the last attempt errored,
     * the latched error is rethrown. This is the load-bearing fix for offline
     * pull-to-refresh: previously every iteration's failure was swallowed and the method
     * returned normally, so [runTriggerInitialSync] stamped success — the `.onFailure`
     * surface was dead code, and `refreshIfStale` then skipped retries for 60 s. Throwing
     * here surfaces the error to the user AND leaves `lastRefreshAtMs` / `_initialSyncDone`
     * unstamped (ingest stays on the offline `onlyLocal` album-coalesce path — the safe
     * default).
     *
     * Bounded by [MAX_LOAD_CHATS_PAGES] — 10 pages × 200 hint = up to 2000 chats per list,
     * which is well past the realistic ceiling and protects against a TDLib bug ever
     * returning success indefinitely (that case exits normally: latch is null after the
     * trailing success).
     */
    private suspend fun drainChatList(list: TdApi.ChatList) {
        var lastError: Throwable? = null
        repeat(MAX_LOAD_CHATS_PAGES) { attempt ->
            val err = runCatching { td.send(TdApi.LoadChats(list, CHAT_LIST_HINT)) }.exceptionOrNull()
            when {
                err == null -> lastError = null // a page loaded — progress; keep draining
                err is CancellationException -> throw err
                err is TdClient.TdException && err.code == 404 -> return // fully drained
                else -> {
                    lastError = err
                    if (attempt < MAX_LOAD_CHATS_PAGES - 1) delay(DRAIN_RETRY_DELAY_MS)
                }
            }
        }
        // Never reached 404 and the tail errored: persistent failure — surface it so the
        // caller doesn't record a false success.
        lastError?.let { throw it }
    }

    private companion object {
        const val TAG = "PostsRepository"
        const val CHAT_LIST_HINT = 200
        const val MAX_LOAD_CHATS_PAGES = 10
        // Pause between failed LoadChats retries in [drainChatList]. Short enough that
        // an online-blip recovers within the bounded loop, long enough that an offline
        // drain doesn't spin all 10 iterations back-to-back before surfacing the error.
        const val DRAIN_RETRY_DELAY_MS = 300L
        // Mirrors the FeedSource.refreshIfStale window: skip the round-trip
        // when last successful refresh was within the last minute. 60s tracks
        // the WebFeedSource staleness gate so both modes feel equally responsive
        // to foreground re-entry.
        const val REFRESH_STALE_MS = 60_000L

        // TELEGRAM_MAX_ALBUM_SIZE promoted to top-level — see below the class closing brace.

        /**
         * How long [handleNewMessage] waits for the rest of an album's
         * UpdateNewMessage burst before flushing. Each new member resets the
         * timer (silence-based debounce) so a steady stream of sibling
         * messages keeps the album whole.
         *
         * Calibration. Telegram-Android uses ~600 ms — the canonical lower bound,
         * fine on stable Wi-Fi but regress-prone under cellular jitter (see
         * [openclaw#1811](https://github.com/openclaw/openclaw/issues/1811): a
         * 500 ms window dropped images on 4+ image groups; Telethon's Lonami
         * concedes in [#4075](https://github.com/LonamiWebs/Telethon/issues/4075)
         * that 1–2 s is closer to the practical floor). 1000 ms is the
         * industry sweet spot: it absorbs ~99% of mobile-network jitter while
         * keeping perceived album latency tolerable.
         *
         * Trade-off. +400 ms over the old 600 ms means an album posted to a
         * channel takes ~400 ms longer to surface in the feed than before. The
         * cost is invisible against the ALBUM-coalesce TDLib RPC tail anyway
         * (50–200 ms) and dwarfed by the user-facing benefit of never
         * downgrading a complete album to a partial card.
         *
         * Defence-in-depth. Even when this window is too short and a partial
         * flush happens, [coalesceAlbumFragments]' size<MAX criterion still
         * triggers a surround fetch, and [foldRawIntoCurrent] still refuses to
         * replace a complete card with a partial one. So this constant is the
         * cheap first line; the next two layers are the safety net.
         */
        const val ALBUM_DEBOUNCE_MS = 1_000L
        /**
         * Quiet-window length after which an album's accumulated DELETED ids are
         * flushed as a single archive row. Sliding-window — every new arrival for
         * the same album resets the timer. 200 ms matches the existing
         * `pendingInteractionInfo` coalesce window and is large enough to absorb
         * a typical slow-network album delete fan-out (~tens of ms between
         * siblings), small enough not to delay the user-visible tombstone.
         */
        const val ALBUM_DELETE_DEBOUNCE_MS = 200L
        // 60s is long enough that quick back-and-forth between channels reuses the cached
        // history, short enough that a deliberate "refresh by re-entering" still works
        // within a normal browsing session.
        const val DEEP_LOAD_COOLDOWN_MS = 60_000L
        // 200ms balances perceived latency (counters update fast enough to feel live)
        // against burst suppression. Telegram's official Android client coalesces in a
        // similar window.
        const val INTERACTION_INFO_COALESCE_MS = 200L
        // Snapshot persistence: top-N posts saved on background. 50 covers a typical
        // first-screen view with comfortable scroll headroom; bigger payloads make the
        // DataStore write feel measurable on the way out without UX benefit because
        // refresh fills the rest in parallel.
        const val SNAPSHOT_SIZE = 50
        // GetMessage is local but still costs a JNI round-trip; cap parallelism so the
        // snapshot restore doesn't spike TDLib's worker thread on cold start.
        const val SNAPSHOT_RESTORE_CONCURRENCY = 8

        // Min gap between Layer-2 networked album repairs. Sized like BACKFILL_THROTTLE_MS
        // (1100 ms): the trigger is human scroll/dwell over the rare degraded card, so the
        // natural rate is already far under 30 req/30 s — this is the fast-fling backstop.
        const val ALBUM_REPAIR_THROTTLE_MS = 1_100L

        // Surround-window attempts per repair (see performAlbumRepair). 3 × the
        // throttle-gated trigger keeps the worst case (3 GetChatHistory per
        // degraded album, ≥ 1.1 s apart per album) far inside the 30 req / 30 s
        // budget while covering TDLib's "local slice first, server on repeat"
        // under-return.
        const val ALBUM_REPAIR_FETCH_ATTEMPTS = 3
        const val ALBUM_REPAIR_RETRY_DELAY_MS = 350L
    }
}

internal fun TdApi.Chat.isChannel(): Boolean {
    val type = this.type
    return type is TdApi.ChatTypeSupergroup && type.isChannel
}

/** Layer-2 album repair queue item: the focused degraded album to network-rebuild. */
private data class AlbumRepairRequest(val chatId: Long, val anchorId: Long, val mediaAlbumId: Long)

/**
 * Hard cap on members per Telegram album, per the protocol. Drives
 * [PostsRepository.coalesceAlbumFragments]: a batch with exactly this many members of one
 * `mediaAlbumId` is provably complete and skips the surround fetch;
 * anything smaller is potentially partial and triggers a rescue query.
 * Source: TDLib `sendMessageAlbum` spec.
 *
 * Declared at top level (not inside [PostsRepository]'s `private companion object`) so
 * unit tests in the same module can reference it without reflection.
 */
internal const val TELEGRAM_MAX_ALBUM_SIZE = 10

/**
 * TDLib client-side message id for a channel/supergroup message is the server
 * message id shifted left 20 bits (`serverId shl 20`). Album members are posted
 * atomically and carry consecutive server ids, so siblings of an album member
 * sit at exactly `anchor.id ± k * ALBUM_ID_STRIDE`. Load-bearing for the
 * cold-start local rehydration in [PostsRepository.coalesceAlbumFragments]: it
 * lets us address siblings by computed id through the per-message local index
 * ([TdApi.GetMessageLocally]) instead of [TdApi.GetChatHistory], which needs the
 * chat's in-memory history list — unbuilt before OpenChat on cold start.
 */
internal const val ALBUM_ID_STRIDE: Long = 1L shl 20

/**
 * Candidate sibling ids for an album member at [anchorId]: up to
 * `2 * (TELEGRAM_MAX_ALBUM_SIZE - 1)` ids straddling the anchor in both
 * directions (the anchor is not guaranteed to be the first or last member),
 * excluding the anchor itself and any non-positive id. Over-generated on
 * purpose — callers fetch each via [TdApi.GetMessageLocally] and keep only the
 * ones that resolve AND share the anchor's `mediaAlbumId`, so deletions, gaps,
 * and a neighbouring album posted back-to-back all filter out naturally.
 */
internal fun albumCandidateIds(anchorId: Long): List<Long> {
    val span = TELEGRAM_MAX_ALBUM_SIZE - 1
    return ((-span)..span)
        .asSequence()
        .filter { it != 0 }
        .map { anchorId + it * ALBUM_ID_STRIDE }
        .filter { it > 0L }
        .toList()
}

/**
 * First-sign-in backfill calibration. See [PostsRepository.runFirstSignInBackfill]
 * for the full rationale; these are at top-level (not in the class's
 * private companion) so unit tests in the same module can reference them
 * without reflection.
 *
 *   - [BACKFILL_TOP_K] = 20. With [BACKFILL_THROTTLE_MS] = 1100 ms → ~22 s
 *     background runtime. Short enough that the backfill is "mostly done"
 *     before the user scrolls past the top of the feed; long enough to land
 *     a few historical posts per "favourite" channel a typical user reads.
 *   - [BACKFILL_POSTS_PER_CHAT] = 10. RPC cost is per-call, not per-message
 *     (TDLib caps `GetChatHistory.limit` at 100 — anything ≤ that is free
 *     vs N=1). At TOP_K × POSTS_PER_CHAT = 200 unique posts, the merged
 *     feed has ~25-30 screens of scroll headroom before paginating. Higher N
 *     risks pushing live arrivals out of the [PostsRepository] snapshot
 *     top-N persistent window.
 *   - [BACKFILL_THROTTLE_MS] = 1100. Aliaksei Levin (tdlib/td#743): server
 *     limits `GetChatHistory` to 30 requests / 30 seconds = 1 RPC/sec
 *     sustained. We add 100 ms so a concurrent user-driven `td.send`
 *     (open chat, load older, etc.) sharing the same bucket doesn't push
 *     our cadence past the edge.
 */
const val BACKFILL_TOP_K = 20
const val BACKFILL_POSTS_PER_CHAT = 10
const val BACKFILL_THROTTLE_MS = 1100L

/** Result of [PostsRepository.resolvePublicHandle]. See its KDoc for semantics. */
sealed interface PublicHandleResult {
    data class Channel(val chatId: Long) : PublicHandleResult
    /** Handle resolves to a 1:1 user (or bot). Carries TDLib `userId` so callers can
     *  open the in-app [dev.lyo.hortay.ui.users.UserProfileSheet] without re-resolving. */
    data class User(val userId: Long) : PublicHandleResult
    data class Unsupported(val kind: PublicHandleKind) : PublicHandleResult
    data object NotFound : PublicHandleResult
}

/** Kind discriminator carried by [PublicHandleResult.Unsupported]. Users are routed via
 *  [PublicHandleResult.User] directly, so this enum only covers things Hortay surfaces
 *  to the OS / a snackbar (groups, supergroups, unrecognised entities). */
enum class PublicHandleKind { Group, Unknown }
