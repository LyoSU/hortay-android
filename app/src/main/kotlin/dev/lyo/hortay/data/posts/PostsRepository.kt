package dev.lyo.hortay.data.posts

import dev.lyo.hortay.data.ChatPresence
import dev.lyo.hortay.data.ConnectionStatus
import dev.lyo.hortay.data.FeedSource
import dev.lyo.hortay.data.IgnoredChannelsStore
import dev.lyo.hortay.data.MessageContentMapper
import dev.lyo.hortay.data.MessageMapper
import dev.lyo.hortay.data.PostContent
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
import dev.lyo.hortay.data.surfaceTo
import dev.lyo.hortay.data.warnUnlessCancelled
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * Twitter-style chronological feed merged from every channel chat the user follows.
 *
 * Pull model (cold-start):
 *   1. [TdApi.LoadChats] drains [TdApi.ChatListMain]; TDLib emits [TdApi.UpdateNewChat]
 *      per chat with `lastMessage` server-side populated.
 *   2. [TdApi.GetChats] returns the canonical chat-id list (local-only, fast).
 *   3. Harvest `chatCache[id].lastMessage` for each chat — no per-channel
 *      `GetChatHistory` fan-out on cold-start. Each harvested message is routed
 *      through [ingest] which already handles channel-filter, album coalesce
 *      (the only residual `GetChatHistory` calls, deferred for album-member
 *      last-messages only), and feed dedup.
 *   4. Raw messages → [MessageMapper] → [PostFilterStrategy] → [posts].
 *
 * On-demand paths (NOT touched by refresh):
 *   • [loadChannelHistory] — when the user opens a single-channel filter.
 *   • [loadOlder] — when the user scrolls past the head of one channel.
 *   • [loadHistoryAround] — when a deep link lands on an older post.
 *
 * Concurrency: a single [Mutex] guards refreshes so that pull-to-refresh + incremental
 * updates from TDLib never interleave and produce phantom duplicates.
 *
 * Storage: the live feed is held in a [PersistentList]. The hot path
 * (UpdateMessageInteractionInfo) fires dozens of times per second on busy news days, and
 * a plain `List` makes us copy the whole 1000-entry feed on every event. PersistentList's
 * structural sharing turns the per-event mutation into O(log N) — a few KB of allocation
 * instead of ~50KB.
 *
 * Cold-start rework rationale: see
 * `docs/superpowers/specs/2026-05-11-tdlib-cold-start-lastmessage-only-design.md`.
 *
 * File organisation. The class lives here; orthogonal helpers split out to keep this
 * file focused on the mutable-state surface:
 *   • [applyChatTitleToFeed] / [applyChatPhotoToFeed] in `ChannelMetadataSync.kt`
 *     own the per-row decision logic for `UpdateChatTitle` / `UpdateChatPhoto`
 *     (channel-as-sender vs personal-author split).
 *   • [foldRawIntoCurrent] and [suspendUntilOrTimeout] in `SnapshotRestorer.kt`
 *     hold the canonical raw → feed fold (partial-album guard) and the
 *     cold-start `UpdateNewChat` wait helper. Both unit-tested in isolation.
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
     * Chat metadata mirror, fed by [TdApi.UpdateNewChat]. Three load-bearing
     * reasons this cache is kept even after [MessageMapper] dropped its own
     * `User`/`Chat`/`Supergroup` mirrors (Wave 1: mapper now reads through
     * direct `td.send(GetUser/GetChat/GetSupergroup)`, which are offline calls
     * per TDLib docs):
     *
     *   1. **Cold-start harvest** ([refreshLocked]). The per-chat-id loop reads
     *      `chatCache[chatId].lastMessage` for ~200 chats. Replacing this with
     *      `td.send(GetChat)` reintroduces `GetChat × N` — the FLOOD_WAIT-class
     *      regression CLAUDE.md's "Load-bearing" table explicitly forbids.
     *   2. **`UpdateChatLastMessage` gate** (see this file's
     *      `td.updates.filterIsInstance<UpdateChatLastMessage>()` listener).
     *      Containment check short-circuits before the chat is resolved; without
     *      it, the per-update fallback to `GetChat` re-creates the same fanout
     *      during cold-start update storms.
     *   3. **Cold-start sync barrier** ([suspendUntilOrTimeout] over
     *      `chatCache.containsKey(it)` in [refreshLocked]). The cache is the
     *      observable for `UpdateNewChat` arrivals — there is no other signal
     *      that `LoadChats` has drained for a given id. A `GetChat` lookup here
     *      would always succeed (TDLib resolves it locally) and we'd never know
     *      whether the chat-list page actually loaded.
     *
     * Steady-state reads ([chatTitle], [chatAvatar], [resolveChatKind],
     * [loadChannelHistoryLocked], [searchInChannel], …) could in principle
     * suspend on `GetChat` directly post-Wave-1, but they still benefit from
     * the cache: skipping the JNI hop avoids contention with the same
     * `td.send` queue that handles user-driven RPCs during scroll. Cost is
     * trivial (one Chat POJO per subscribed channel, ~hundreds of bytes).
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

    // Stamped on successful refreshLocked completion. refreshIfStale uses it to skip
    // re-opening the app from hammering 200+ TDLib calls when the feed is still warm.
    @Volatile
    private var lastRefreshAtMs: Long = 0L

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
    //   3. [SnapshotRestorer.foldRawIntoCurrent] + [upgradeDegradedAlbums] —
    //      partial-album-downgrade guard + per-member-id GetMessage rescue from
    //      the persisted snapshot, so a degraded card (anchor present but
    //      siblings dropped) cannot poison the on-disk snapshot AND a degraded
    //      anchor on cold restart upgrades to the full card via the
    //      last-known-good member ids saved in the previous session. Third line
    //      — works even when the chat-history hydration race makes layers 1+2
    //      come up short.
    //
    // Removing any one of these reopens a corresponding CHANGELOG bug: "1 photo
    // → 2 photos → ..." flicker (layer 1), partial album on PTR or deep-load
    // (layer 2), "5-photo album restore on relaunch" / "Snapshot preserves
    // saved album siblings" / "Editing an album caption no longer collapses"
    // (layer 3).
    private val albumBuffers = ConcurrentHashMap<Pair<Long, Long>, MutableList<TdApi.Message>>()
    private val albumDebounce = ConcurrentHashMap<Pair<Long, Long>, Job>()

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
    private val pendingInteractionInfo =
        ConcurrentHashMap<Pair<Long, Long>, TdApi.MessageInteractionInfo>()
    private val interactionFlushScheduled = AtomicBoolean(false)

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
        combine(_posts, _mainChatIds, _archivedChatIds, ignoredFlow) { all, mainIds, archivedIds, ignored ->
            val subscribed = if (mainIds.isEmpty() && archivedIds.isEmpty()) all
            else all.filter { it.chatId in mainIds || it.chatId in archivedIds }
            if (ignored.isEmpty()) subscribed.toPersistentList()
            else subscribed.filter { it.chatId !in ignored }.toPersistentList()
        }
            .stateIn(scope, SharingStarted.Eagerly, persistentListOf())
    }

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

        // Brand-new chat appeared. We deliberately DO NOT issue GetChatHistory here:
        //   - On startup TDLib re-emits the entire chat list as UpdateNewChat; auto-loading
        //     each one 429-rate-limits the server.
        //   - Many of those chats are private / archived / DM — every one is a guaranteed
        //     [400] Can't access the chat warning.
        // Posts from a freshly-joined channel reach us anyway via UpdateNewMessage; the user
        // can pull-to-refresh for back-history. Just cache the metadata.
        td.updates.filterIsInstance<TdApi.UpdateNewChat>()
            .onEach { update ->
                chatCache[update.chat.id] = update.chat
                // Seed the read cursor from the chat's server-side state so cards
                // entering the feed during cold-start render with the correct
                // unread/read decoration immediately — without this, every visible
                // post would flash "unread" for the first frame after auth.
                val cursor = update.chat.lastReadInboxMessageId
                if (cursor > 0L) {
                    // Monotonic clamp: a stale UpdateNewChat arriving after a fresh
                    // UpdateChatReadInbox must NOT roll the cursor backwards, else
                    // already-read posts re-appear as unread.
                    _chatReadCursors.update { current ->
                        val existing = current[update.chat.id] ?: 0L
                        if (cursor <= existing) current
                        else current.put(update.chat.id, cursor)
                    }
                }
            }
            .launchIn(scope)

        // Canonical read-state stream from TDLib. Fires whenever the user reads in
        // ANY client (official Telegram-Android included), so an UnreadStrip on a
        // card the user has just read in TG-Android animates out within ~1 frame
        // of the server ack landing here. monotonic-only update — TDLib has been
        // observed to emit redundant resets with smaller ids during chat repair
        // flows; clamping to monoMax(...) keeps the cursor from rewinding under us.
        td.updates.filterIsInstance<TdApi.UpdateChatReadInbox>()
            .onEach { update ->
                _chatReadCursors.update { current ->
                    val existing = current[update.chatId] ?: 0L
                    if (update.lastReadInboxMessageId <= existing) current
                    else current.put(update.chatId, update.lastReadInboxMessageId)
                }
            }
            .launchIn(scope)

        // UpdateChatLastMessage fires when TDLib (a) discovers a fresh lastMessage for
        // a previously-known chat (edit, delete cascade, or a new post on a chat we
        // haven't OpenChat'd) and (b) initial sync of late-arriving last-message data.
        //
        // Routes the message through ingest so the feed picks it up. ingest is
        // idempotent on (chatId, messageId) — a duplicate arrival via UpdateNewMessage
        // racing UpdateChatLastMessage cannot dupe a card.
        //
        // Early-return if the chat is not yet cached: UpdateChatLastMessage racing
        // UpdateNewChat on a fresh-login storm would otherwise drive ingest into its
        // `td.send(GetChat(id))` fallback for every chat, undoing the cold-start
        // FLOOD_WAIT win. UpdateNewChat will arrive shortly with chat.lastMessage
        // populated server-side; the next refreshLocked harvest picks it up. We do
        // NOT mutate the cached Chat's lastMessage field — that would be a write-through
        // race on a shared mutable object held by both this listener and the
        // UpdateNewChat handler. A stale cache.lastMessage on the next refresh just
        // re-routes a known message through ingest (no-op via the (chatId, messageId)
        // dedup); feed correctness depends on ingest, not on the cache field.
        td.updates.filterIsInstance<TdApi.UpdateChatLastMessage>()
            .onEach { update ->
                val msg = update.lastMessage ?: return@onEach
                if (chatCache[update.chatId] == null) return@onEach
                ingest(update.chatId, listOf(msg))
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
            // Persist every album member id, not just the anchor — restoreFromSnapshot
            // re-runs PostFilterStrategy.mergeAlbums, which needs all siblings present
            // to rebuild the merged card.
            val ids = post.albumMessageIds.ifEmpty { listOf(post.id) }
            ids.map { post.chatId to it }
        }
        val merged = preserveDegradedAlbumSiblings(current, newEntries)
        runCatching { snapshotStore.save(merged) }.warnUnlessCancelled(TAG, "saveSnapshot")
    }

    /**
     * Anti-poisoning guard for [saveSnapshotNow]. If [current] contains any
     * degraded album (`mediaAlbumId != 0 && albumMessageIds.size <= 1`),
     * the natural save would shrink that album's member set on disk to a
     * single id — destroying the last-known-good membership the next cold
     * start relies on for [upgradeDegradedAlbums].
     *
     * The save typically runs on a foreground→background transition. If
     * the in-memory feed was already degraded at that moment — either
     * because [restoreFromSnapshot]'s upgrade pass hadn't fired yet, or
     * because the matching album wasn't in the previous snapshot's top
     * slice — overwriting the on-disk snapshot is irreversible. The next
     * cold start has no source-of-truth to recover from.
     *
     * Mitigation: load the previous snapshot, look up the mediaAlbumId of
     * each id not already in [newEntries] via `GetMessage` (TDLib's
     * per-message local index — sub-millisecond, network-free), and
     * preserve any whose `(chatId, mediaAlbumId)` matches a currently-
     * degraded album. The next cold start's [upgradeDegradedAlbums] will
     * regroup them by mediaAlbumId and rebuild the full card.
     *
     * No-op when [current] has no degraded albums — the common path saves
     * exactly the fresh view with zero extra I/O.
     */
    private suspend fun preserveDegradedAlbumSiblings(
        current: PersistentList<TimelinePost>,
        newEntries: List<Pair<Long, Long>>,
    ): List<Pair<Long, Long>> {
        val degradedAlbums = current
            .filter { it.mediaAlbumId != 0L && it.albumMessageIds.size <= 1 }
            .mapTo(HashSet()) { it.chatId to it.mediaAlbumId }
        if (degradedAlbums.isEmpty()) return newEntries

        val previous = runCatching { snapshotStore.load() }
            .warnUnlessCancelled(TAG, "loadSnapshotForSave")
            .getOrDefault(emptyList())
        if (previous.isEmpty()) return newEntries

        val newSet = newEntries.toHashSet()
        val degradedChats = degradedAlbums.mapTo(HashSet()) { it.first }
        val candidates = previous.filter { entry -> entry.first in degradedChats && entry !in newSet }
        if (candidates.isEmpty()) return newEntries

        val rescued = coroutineScope {
            val semaphore = Semaphore(SNAPSHOT_RESTORE_CONCURRENCY)
            candidates.map { (chatId, msgId) ->
                async {
                    semaphore.withPermit {
                        val msg = runCatching { td.send(TdApi.GetMessage(chatId, msgId)) }.getOrNull()
                        if (msg != null && msg.mediaAlbumId != 0L &&
                            (chatId to msg.mediaAlbumId) in degradedAlbums
                        ) chatId to msgId else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return if (rescued.isEmpty()) newEntries else newEntries + rescued
    }

    /**
     * Restore the persisted top-of-feed by asking TDLib for each cached message id.
     * GetMessage on a known id is served from TDLib's local DB synchronously — for a
     * 50-post snapshot the whole pass is typically < 100ms.
     *
     * Idempotent + safe to overlap with [refresh]: the same `_posts.update` merge
     * policy in [refreshLocked] keeps refresh's authoritative result on top of any
     * snapshot rows that landed first; concurrent ingest of brand-new posts also
     * survives.
     *
     * Bails when `_posts` is already non-empty so a pull-to-refresh that beat us to
     * the punch wins — there's no point spending GetMessage round-trips to recreate
     * the same data we already have, fresher.
     */
    override suspend fun restoreFromSnapshot() {
        restoreFromSnapshotInternal()
    }

    /**
     * Returns the number of posts written — exposed for callers that care.
     *
     * Two modes, picked by the current state of [_posts]:
     *  - **Empty feed**: refresh failed (offline, RPC error, no subscriptions
     *    drained yet). Restore the snapshot in full as a last-known-good
     *    fallback.
     *  - **Non-empty feed with degraded albums**: refresh produced a 1-photo
     *    card for an album because TDLib's local message DB hadn't finished
     *    hydrating when [coalesceAlbumFragments]' surround fetch fired. The
     *    snapshot from the previous healthy session has every member id saved
     *    per-post (see [saveSnapshotNow]), and `GetMessage` uses TDLib's
     *    per-message local index — working even when the chat history slice
     *    isn't loaded yet. Fetch the saved siblings directly and let
     *    [foldRawIntoCurrent] swap the degraded card for the rebuilt
     *    full-album card cleanly.
     *
     * A healthy non-empty feed is a no-op.
     */
    suspend fun restoreFromSnapshotInternal(): Int {
        val snapshot = runCatching { snapshotStore.load() }
            .warnUnlessCancelled(TAG, "loadSnapshot")
            .getOrDefault(emptyList())
        if (snapshot.isEmpty()) return 0

        val current = _posts.value
        return if (current.isEmpty()) fullRestore(snapshot)
            else upgradeDegradedAlbums(current, snapshot)
    }

    private suspend fun fullRestore(snapshot: List<Pair<Long, Long>>): Int {
        val messages = fetchSnapshotMessages(snapshot)
        if (messages.isEmpty()) return 0
        val mapped = mapSnapshotMessages(messages)
        if (mapped.isEmpty()) return 0

        var added = 0
        _posts.update { current ->
            // Refresh may have raced ahead; if so, keep its result intact and
            // abandon the full restore — fresh always wins for the cold paint.
            if (current.isNotEmpty()) current
            else {
                added = mapped.size
                PostFilterStrategy.apply(mapped).toPersistentList()
            }
        }
        return added
    }

    private suspend fun upgradeDegradedAlbums(
        current: PersistentList<TimelinePost>,
        snapshot: List<Pair<Long, Long>>,
    ): Int {
        // Index degraded albums by (chatId, mediaAlbumId). The snapshot stores
        // (chatId, msgId) pairs flat — we need mediaAlbumId per snapshot
        // message to know which saved ids belong to which degraded album, so
        // we fetch first, then filter.
        val degradedAlbumKeys = current
            .asSequence()
            .filter { it.mediaAlbumId != 0L && it.albumMessageIds.size <= 1 }
            .mapTo(HashSet()) { it.chatId to it.mediaAlbumId }
        if (degradedAlbumKeys.isEmpty()) return 0

        val candidateChats = degradedAlbumKeys.mapTo(HashSet()) { it.first }
        val candidates = snapshot.filter { (chatId, _) -> chatId in candidateChats }
        if (candidates.isEmpty()) return 0

        val messages = fetchSnapshotMessages(candidates)
        // Keep only messages that belong to a currently-degraded album. Solo
        // posts and unrelated albums saved in the same chat's snapshot slice
        // are noise here — we don't want to re-add yesterday's top-of-feed
        // standalones the user has already scrolled past.
        val albumMembers = messages.filter { msg ->
            msg.mediaAlbumId != 0L && (msg.chatId to msg.mediaAlbumId) in degradedAlbumKeys
        }
        if (albumMembers.isEmpty()) return 0

        val mapped = mapSnapshotMessages(albumMembers)
        if (mapped.isEmpty()) return 0

        var upgraded = 0
        _posts.update { live ->
            val before = live.mapTo(HashSet()) { it.chatId to it.id }
            val next = foldRawIntoCurrent(live, mapped)
            upgraded = next.count { (it.chatId to it.id) !in before }
            next
        }
        return upgraded
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
            else coalesceAlbumFragments(chatId, msgs).map { mapper.toChannelPost(it, chat) }
        }
    }

    // 30 (not 20) so an album sitting on the limit boundary doesn't get split: most
    // albums are 2–6 members, so 30 is enough headroom for the latest few channel
    // posts to arrive whole. GetChatHistory itself returns members one-per-message, so
    // a 5-photo album consumes 5 of the 30 slots.
    override suspend fun refresh() {
        refreshMutex.withLock {
            runCatching { refreshLocked() }
                .onSuccess { lastRefreshAtMs = System.currentTimeMillis() }
                .warnUnlessCancelled("refresh")
                .onFailure { it.surfaceTo(userMessages, res, dev.lyo.hortay.R.string.op_refresh_feed, connection.value) }
        }
    }

    /**
     * Refresh only if the last successful refresh is older than [REFRESH_STALE_MS].
     * Reuses the same mutex as [refresh] so a concurrent pull-to-refresh isn't stomped.
     */
    override suspend fun refreshIfStale() {
        refreshMutex.withLock {
            if (System.currentTimeMillis() - lastRefreshAtMs <= REFRESH_STALE_MS) return@withLock
            runCatching { refreshLocked() }
                .onSuccess { lastRefreshAtMs = System.currentTimeMillis() }
                .warnUnlessCancelled("refreshIfStale")
                .onFailure { it.surfaceTo(userMessages, res, dev.lyo.hortay.R.string.op_refresh_feed, connection.value) }
        }
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
     * Pre-warm the per-channel slice ahead of a navigation push.
     *
     * Returns `true` once `_posts` contains at least one row from [chatId] —
     * either because the merged feed already had a slice (fast path; the
     * common case for any channel the user has seen in the feed since the
     * session warmed), or because [loadChannelHistory] resolved inside
     * [timeoutMs]. Returns `false` when neither condition was satisfied
     * within the grace window — the caller pushes anyway (the user already
     * paid a perceptible wait; further blocking the source view past
     * [timeoutMs] reads as "tap didn't register").
     *
     * The launched [loadChannelHistory] is single-flight via [deepLoadJobs],
     * so when the user lands on [ChannelScreen] and [ChannelViewModel] runs
     * its own `loadChannelHistory(chatId)` call, it awaits the SAME
     * Deferred. No duplicate `GetChatHistory` round-trip, no extra
     * FLOOD_WAIT pressure.
     *
     * This is the "wait-for-content navigation" pattern (Apple HIG / iOS
     * Messages / Telegram-iOS): tap → brief source-side preload → mount
     * target with content already populated, so the LazyColumn renders
     * Ready in its first frame instead of flashing a skeleton. The
     * companion contract on the target side: when this returns `false`,
     * [ChannelScreen] must show its skeleton immediately (no further grace)
     * — the user already waited [timeoutMs] on the source, stacking grace
     * windows would surface as a long blank that reads as a freeze.
     */
    suspend fun primeChannelForOpen(chatId: Long, timeoutMs: Long = 300L): Boolean {
        if (_posts.value.any { it.chatId == chatId }) return true
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            loadChannelHistory(chatId)
        }
        return _posts.value.any { it.chatId == chatId }
    }

    suspend fun loadChannelHistory(chatId: Long, limit: Int = 80): Result<Unit> {
        val now = System.currentTimeMillis()
        deepLoadCooldownUntilMs[chatId]?.let { until ->
            if (now < until) return Result.success(Unit)
        }
        val deferred = deepLoadJobs.computeIfAbsent(chatId) {
            scope.async { runCatching { loadChannelHistoryLocked(chatId, limit) } }
        }
        val result = deferred.await()
        deepLoadJobs.remove(chatId, deferred)
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
        val chat = chatCache[chatId] ?: td.send(TdApi.GetChat(chatId)).also { chatCache[chatId] = it }
        if (!chat.isChannel()) return false

        val history = td.send(TdApi.GetChatHistory(chatId, /* fromMessageId */ 0, 0, limit, false))
        val raw = coalesceAlbumFragments(chatId, history.messages.orEmpty().toList())
        val mapped = raw.map { mapper.toChannelPost(it, chat) }
        if (mapped.isEmpty()) return false

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
        val raw = coalesceAlbumFragments(chatId, history.messages.orEmpty().toList())
        val mapped = raw.map { mapper.toChannelPost(it, chat) }
        if (mapped.isEmpty()) return false

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
        }
        val result = deferred.await()
        pageJobs.remove(chatId, deferred)
        return result
            .warnUnlessCancelled(TAG, "loadOlder($chatId)")
            .onFailure { it.surfaceTo(userMessages, res, dev.lyo.hortay.R.string.op_load_older, connection.value) }
            .getOrDefault(0)
    }

    private suspend fun loadOlderLocked(chatId: Long, limit: Int): Int {
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
        val coalesced = coalesceAlbumFragments(chatId, raw)
        val mapped = coalesced.map { mapper.toChannelPost(it, chat) }

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
        val coalesced = coalesceAlbumFragments(chatId, raw)
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
     *   - [PublicHandleResult.Unsupported] — the handle is a real Telegram entity but
     *     not something Hortay can render today (1:1 user, bot, basic group,
     *     supergroup that isn't a channel). Caller surfaces a user-facing message
     *     ("profile / bot / group links open in Telegram") and offers to hand off via
     *     the OS chooser.
     *
     *   - [PublicHandleResult.NotFound] — TDLib couldn't resolve the handle at all
     *     (`SearchPublicChat` 4xx / network failure). Caller treats as silent miss.
     *
     * Hortay's UX scope is read-only channel-feed today; users, bots and groups need a
     * full chat surface we don't have. Differentiating here lets the deep-link
     * collector tell the user *why* a tap on `@durov` (a user) doesn't open the feed
     * filter, instead of leaving them on a blank skeleton-then-empty screen.
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

    private fun TdApi.Chat.toPublicHandleResult(): PublicHandleResult = when {
        isChannel() -> PublicHandleResult.Channel(id)
        type is TdApi.ChatTypePrivate -> PublicHandleResult.Unsupported(PublicHandleKind.User)
        type is TdApi.ChatTypeBasicGroup -> PublicHandleResult.Unsupported(PublicHandleKind.Group)
        type is TdApi.ChatTypeSupergroup -> PublicHandleResult.Unsupported(PublicHandleKind.Group)
        else -> PublicHandleResult.Unsupported(PublicHandleKind.Unknown)
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
     *     hold OpenChat for every visible chat in the global feed; only the active
     *     channel-filter screen opens its single chat (see [openChat]). Multi-open
     *     is a fight against the API design and risks burst FLOOD_WAIT on a 200-channel
     *     scroll.
     *   - tdlib/td#46 + tdlib/td#219: when the chat isn't opened, the canonical way to
     *     advance read state is `force_read=true` on `ViewMessages`. That's exactly the
     *     case here — every channel except the filter target is closed.
     *   - tdlib/td#136: `ViewMessages` is filtered server-side to messages TDLib
     *     considers "seen" (since 1.3.0), so calling it for a viewport-stable batch is
     *     safe even if the user only briefly glanced.
     *   - tdlib/td#2312: "Only a few messages can be viewed in a time." Caller must
     *     batch sensibly — TimelineScreen passes the visible viewport (3-7 posts), well
     *     within bounds. Do NOT bulk-ack the whole feed from this function.
     *
     * For album posts: [TimelinePost.id] is the oldest album member's id, which matches
     * tdlib/td#2312's note that "only the first message in an album can receive
     * reactions" — the same id is the canonical one for view/read tracking too.
     *
     * The dwell gate (≥1s viewport-stable before this is called) lives in
     * [TimelineScreen]: that's a UX policy, not a TDLib invariant, so it stays at the
     * call site.
     */
    suspend fun viewMessages(chatId: Long, messageIds: List<Long>) =
        ChatPresence.viewMessages(
            td = td,
            chatId = chatId,
            messageIds = messageIds,
            // ChatHistory: the user is reading the channel feed (merged global view
            // or single-channel filter). Both look like history scrolling to TDLib.
            source = TdApi.MessageSourceChatHistory(),
            // Closed chats in the global feed need force_read=true to advance read
            // state — the maintainer-canonical alternative to OpenChat-per-channel.
            // For the channel-filter case, the chat is already opened by the screen
            // so force_read is a no-op; setting it true uniformly keeps the call
            // site free of mode branching.
            forceRead = true,
        )

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
            (existing ?: mutableListOf()).also { it += message }
        }
        albumDebounce[key]?.cancel()
        albumDebounce[key] = scope.launch {
            delay(ALBUM_DEBOUNCE_MS)
            albumDebounce.remove(key)
            val batch = albumBuffers.remove(key) ?: return@launch
            ingest(key.first, batch)
        }
    }

    private suspend fun ingest(chatId: Long, messages: List<TdApi.Message>) {
        val chat = chatCache[chatId] ?: runCatching { td.send(TdApi.GetChat(chatId)) }
            .getOrNull()
            ?.also { chatCache[it.id] = it }
            ?: return
        if (!chat.isChannel()) return
        // Filter live arrivals to chats actually in the user's subscriptions
        // (Main or Archive list). TDLib emits UpdateNewMessage for any chat
        // it has resolved — deep-link previews, public-username lookups,
        // linked discussion-group parents — so without this gate the feed
        // leaked posts from channels the user never subscribed to. The
        // `_mainChatIds` set is the authoritative ChatListMain membership
        // populated by [refreshLocked] from `GetChats` (and kept live by
        // the UpdateChatAddedToList / UpdateChatRemovedFromList listeners
        // above), which sidesteps the `Chat.positions` cold-start race that
        // an earlier attempt at this filter ran into.
        //
        // Empty `_mainChatIds` means we haven't completed cold-start
        // refresh yet (or the user is signed out). Don't filter then —
        // refreshLocked itself calls ingest for the harvest, and we don't
        // want the cold-start ingest to be a no-op. Once refreshLocked's
        // [_mainChatIds] population completes, subsequent live arrivals are
        // gated normally.
        val mainIds = _mainChatIds.value
        if (mainIds.isNotEmpty()) {
            val subscribed = chatId in mainIds || chatId in _archivedChatIds.value
            if (!subscribed) return
        }

        // If a real-time burst still left an album fragmented (e.g. members spread across
        // >600 ms by upstream), probe the chat for the missing siblings before mapping.
        // Cheap if there are no fragments — early-returns immediately.
        val full = coalesceAlbumFragments(chatId, messages)

        val newPosts = full
            .map { mapper.toChannelPost(it, chat) }
            .filter { it.content !is PostContent.Unsupported }
        if (newPosts.isEmpty()) return

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
     *  - **Cold-start lastMessage harvest.** [refreshLocked] routes
     *    `Chat.lastMessage` per channel through this method; if `lastMessage`
     *    is an album member, the batch is trivially `size=1`.
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
     * Concurrency: each fragment fires a parallel GetChatHistory; results
     * are merged synchronously after [awaitAll] so the seen-set has no race.
     * Bounded by distinct album ids in the input — typically 0..2 per batch.
     */
    private suspend fun coalesceAlbumFragments(
        chatId: Long,
        messages: List<TdApi.Message>,
    ): List<TdApi.Message> {
        val fragments = messages
            .filter { it.mediaAlbumId != 0L }
            .groupBy { it.mediaAlbumId }
            .values
            .filter { it.size < TELEGRAM_MAX_ALBUM_SIZE }
            // Pick one anchor per under-sized group. Surround fetch returns every
            // sibling sharing the mediaAlbumId, so one query per group is enough;
            // using the first member as anchor is arbitrary but stable (groupBy
            // preserves first-seen order).
            .map { it.first() }
        if (fragments.isEmpty()) return messages

        val seen = messages.mapTo(hashSetOf()) { it.id }
        val extras = coroutineScope {
            fragments.map { fragment ->
                async {
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
                    resp?.messages.orEmpty().filter { it.mediaAlbumId == fragment.mediaAlbumId }
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
     * Buffer the update; if a flush isn't already scheduled, schedule one. A single
     * coroutine drains the buffer after [INTERACTION_INFO_COALESCE_MS] and writes all
     * pending mutations in one `_posts.update { mutate { ... } }` call — the persistent
     * list builds the new snapshot once, regardless of how many keys we touch.
     */
    private fun handleInteractionInfo(update: TdApi.UpdateMessageInteractionInfo) {
        // null interactionInfo: original handler resolved every field to its current value
        // (effectively no-op). Drop here so the buffer stays non-null for ConcurrentHashMap.
        val info = update.interactionInfo ?: return
        pendingInteractionInfo[update.chatId to update.messageId] = info
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
        updateOnePostByAnyMemberId(update.chatId, update.messageId) {
            it.copy(editDate = update.editDate.toLong() * 1000L)
        }
    }

    private fun handleDeleted(update: TdApi.UpdateDeleteMessages) {
        if (!update.isPermanent) return
        val ids = update.messageIds.toHashSet()
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
        val target = _posts.value.firstOrNull { post ->
            post.chatId == update.chatId &&
                (post.id == update.messageId || update.messageId in post.albumMessageIds)
        } ?: return

        if (target.mediaAlbumId == 0L) {
            // Solo post — fast path, swap content in place.
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
        // Multiple workers read chatCache[chatId] concurrently (refreshLocked,
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
     * Set of chatIds the user has archived in Telegram. Populated alongside the main
     * refresh so the feed UI can surface a dedicated "Архів" tab without a separate query.
     * Stays a [StateFlow] so the tab can show / hide based on whether the user actually
     * has anything archived.
     */
    private val _archivedChatIds = MutableStateFlow<Set<Long>>(emptySet())
    val archivedChatIds: StateFlow<Set<Long>> = _archivedChatIds.asStateFlow()

    // Set of chatIds in the user's main chat list. See [refreshLocked] for why
    // we maintain this as an authoritative set (filled from GetChats response)
    // rather than reading it off `Chat.positions` (which has a cold-start
    // race). Filter source for [ingest] — live UpdateNewMessage from a chat
    // not in this set OR [archivedChatIds] is dropped.
    private val _mainChatIds = MutableStateFlow<Set<Long>>(emptySet())

    private suspend fun refreshLocked() {
        // Cold-start strategy (mirrors Telegram-Android): drive `LoadChats(ChatListMain)`
        // so TDLib emits UpdateNewChat for every chat. Each chat carries `lastMessage`
        // server-side, so the feed can be reconstructed from chatCache without a
        // per-channel GetChatHistory fan-out. Previously this method issued
        // GetChat × N (Sem=16) + GetChatHistory × N (Sem=4); on a 200-channel account
        // that was ~400 RPCs on the critical path and the dominant trigger for the
        // post-login FLOOD_WAIT class. See
        // `docs/superpowers/specs/2026-05-11-tdlib-cold-start-lastmessage-only-design.md`.
        //
        // Archive is NOT drained here — archived chats are out of scope for v1 of this
        // change. The existing UpdateChatAddedToList / UpdateChatRemovedFromList
        // listeners still keep `_archivedChatIds` live so the UI scope predicate works.
        drainChatList(TdApi.ChatListMain())

        // GetChats returns chat ids already loaded into TDLib's local memory by the
        // drainChatList pass above. Int.MAX_VALUE because the page count is the real
        // ceiling on response size.
        val chatIds = td.send(TdApi.GetChats(TdApi.ChatListMain(), Int.MAX_VALUE))
            .chatIds.toList()

        // Authoritative subscription set used by [ingest] to gate live
        // [UpdateNewMessage] arrivals. TDLib pushes UpdateNewMessage for any
        // chat it has ever resolved (deep-link previews, public-username
        // lookups, linked discussion-group parents); without this gate those
        // side-resolved channels' posts would land in the all-feed, which
        // surfaced as "I see channels in the feed I'm not subscribed to" and
        // as scroll-anchor creep when drilling into channels (their posts
        // got slotted above the user's anchor). Maintained live by the
        // UpdateChatAddedToList / UpdateChatRemovedFromList listeners above,
        // so a freshly-joined channel becomes visible without app restart.
        //
        // Why this set + not `Chat.positions.any { ChatListMain && order != 0 }`:
        // positions has a cold-start race where UpdateNewChat arrives before
        // TDLib has filled in the positions array, and harvesting via the
        // chat's `lastMessage` would then reject every chat — the resulting
        // bug was an empty feed on restart with only post-warmup live
        // arrivals visible. The GetChats response is, by contrast, the
        // direct authoritative answer to "what's in ChatListMain right now",
        // populated before harvest fires.
        _mainChatIds.value = _mainChatIds.value + chatIds.toSet()

        // LoadChats triggers UpdateNewChat per chat, but the emission may arrive on
        // the td.updates flow AFTER GetChats has already returned the id list — TDLib's
        // bridge does not guarantee that the update queue is fully drained before the
        // response. Wait up to 2 s for chatCache to catch up; on timeout we proceed with
        // whatever's cached (the UpdateChatLastMessage listener will catch the rest
        // via live ingest as those updates arrive).
        suspendUntilOrTimeout(WAIT_NEW_CHAT_TIMEOUT_MS, WAIT_NEW_CHAT_POLL_MS) {
            chatIds.all { chatCache.containsKey(it) }
        }

        // Harvest lastMessage for each known chat and route through ingest. ingest()
        // already:
        //   - filters non-channel chats (basic groups / DM / supergroup-chats)
        //   - runs coalesceAlbumFragments for album-member lastMessages
        //   - applies PostFilterStrategy (service / expired media drops)
        //   - emits on _newArrivals for the live-update consumers
        //   - dedups against the existing feed
        // Sem = REFRESH_CONCURRENCY (4) bounds the concurrent album-coalesce probes;
        // for non-album chats ingest is pure in-memory and never blocks.
        //
        // Skip caught-up chats (`unreadCount == 0`). Server-authoritative
        // signal per tdlib/td#1034: `unreadCount == 0 ⟺ lastMessage.id ≤
        // lastReadInboxMessageId`. Surfacing those into the cold-start feed
        // was the trigger for "reverse-feed lands on an ancient post" — in
        // OldestUnreadFirst (asc-by-date) a dormant channel's years-old
        // lastMessage sat at `items[0]` and, when it happened to be unread
        // per the cursor map, the boundary picker stranded the user there.
        // Admin-owned channels are a natural special-case of this filter:
        // own posts are outgoing and don't bump `unreadCount`, so harvest
        // drops the admin's last broadcast cleanly. Live ingest of brand-
        // new posts (UpdateNewMessage / UpdateChatLastMessage) is unaffected
        // — this is purely a cold-start initialisation filter.
        val semaphore = Semaphore(REFRESH_CONCURRENCY)
        coroutineScope {
            chatIds.map { chatId ->
                async {
                    semaphore.withPermit {
                        val chat = chatCache[chatId] ?: return@withPermit
                        if (chat.unreadCount == 0) return@withPermit
                        val msg = chat.lastMessage ?: return@withPermit
                        ingest(chatId, listOf(msg))
                    }
                }
            }.awaitAll()
        }

        // Partial-album rescue lives in [restoreFromSnapshotInternal] now —
        // it does targeted GetMessage on the previous session's saved member
        // ids and works deterministically against TDLib's per-message local
        // index, sidestepping the chat-history hydration race that the old
        // in-line delay-and-re-ingest pass tried (and sometimes failed) to
        // wait out.
    }

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
            deepLoadJobs.values.forEach { it.cancel() }
            deepLoadJobs.clear()
            deepLoadCooldownUntilMs.clear()
            pageEnded.clear()
            pageJobs.values.forEach { it.cancel() }
            pageJobs.clear()
            lastRefreshAtMs = 0L
            _archivedChatIds.value = emptySet()
            _mainChatIds.value = emptySet()
        }
        runCatching { snapshotStore.clear() }
            .warnUnlessCancelled(TAG, "snapshotStore.clear")
    }

    /**
     * Repeatedly call [TdApi.LoadChats] until TDLib runs out of pages for [list]. TDLib
     * signals "no more chats to load" with a `404 Not Found` error specifically — any
     * other error (network blip, auth race) is transient and would historically have
     * caused an early exit and a partially-populated chat list on cold start. Distinguish
     * the two: 404 → terminate (we're done). Other errors → swallow this iteration and
     * try the next; a transient blip should not strand the user with half a feed.
     *
     * Bounded by [MAX_LOAD_CHATS_PAGES] — 10 pages × 200 hint = up to 2000 chats per list,
     * which is well past the realistic ceiling and protects against a TDLib bug ever
     * returning success indefinitely.
     */
    private suspend fun drainChatList(list: TdApi.ChatList) {
        repeat(MAX_LOAD_CHATS_PAGES) {
            val res = runCatching { td.send(TdApi.LoadChats(list, CHAT_LIST_HINT)) }
            val err = res.exceptionOrNull()
            if (err is TdClient.TdException && err.code == 404) return
            // Any other failure: retry on the next iteration; the bounded loop guards
            // against an infinite retry storm if TDLib stays unhealthy.
        }
    }

    private companion object {
        const val TAG = "PostsRepository"
        const val CHAT_LIST_HINT = 200
        const val MAX_LOAD_CHATS_PAGES = 10
        const val REFRESH_DEFAULT_LIMIT = 30
        // Mirrors the FeedSource.refreshIfStale window: skip the round-trip
        // when last successful refresh was within the last minute. 60s tracks
        // the WebFeedSource staleness gate so both modes feel equally responsive
        // to foreground re-entry.
        const val REFRESH_STALE_MS = 60_000L

        /**
         * Hard cap on members per Telegram album, per the protocol. Drives
         * [coalesceAlbumFragments]: a batch with exactly this many members of one
         * `mediaAlbumId` is provably complete and skips the surround fetch;
         * anything smaller is potentially partial and triggers a rescue query.
         * Source: TDLib `sendMessageAlbum` spec.
         */
        const val TELEGRAM_MAX_ALBUM_SIZE = 10

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
        // Aligns with TDLib's default ~4 simultaneous downloads — same shape, same back-
        // pressure profile.
        const val REFRESH_CONCURRENCY = 4

        /**
         * How long [refreshLocked] waits for late-arriving `UpdateNewChat` emissions to
         * fill [chatCache] after `GetChats` returns. Empirically TDLib's update queue
         * drains within a few hundred ms on warm runs; on a true cold start it can take
         * 1-2 s, which is the upper bound this constant captures.
         *
         * Known limitation. Past the timeout we proceed with whatever is cached. The
         * [TdApi.UpdateChatLastMessage] listener catches chats that emit a fresh
         * last-message after the window closes — but if a chat's last-message is
         * UNCHANGED at the time its late [TdApi.UpdateNewChat] arrives (already-read
         * channel, no new posts during the boot window), neither listener fires for
         * it and the chat is absent from the feed until the next pull-to-refresh.
         * Mitigation in practice: the next user-driven refresh runs `refreshLocked`
         * again and the cache is warm by then, so the miss is recovered on the first
         * scroll-down or PTR gesture. Permanent fix would require either a longer
         * timeout (trades cold-start latency for completeness) or a second-pass
         * GetChats after the burst settles. Deferred.
         */
        const val WAIT_NEW_CHAT_TIMEOUT_MS = 2_000L

        /** Poll interval for [WAIT_NEW_CHAT_TIMEOUT_MS]. */
        const val WAIT_NEW_CHAT_POLL_MS = 50L
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
    }
}

internal fun TdApi.Chat.isChannel(): Boolean {
    val type = this.type
    return type is TdApi.ChatTypeSupergroup && type.isChannel
}

/** Result of [PostsRepository.resolvePublicHandle]. See its KDoc for semantics. */
sealed interface PublicHandleResult {
    data class Channel(val chatId: Long) : PublicHandleResult
    data class Unsupported(val kind: PublicHandleKind) : PublicHandleResult
    data object NotFound : PublicHandleResult
}

/** Kind discriminator carried by [PublicHandleResult.Unsupported]. */
enum class PublicHandleKind { User, Group, Unknown }
