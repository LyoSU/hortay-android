package dev.lyo.hortay

import android.content.Context
import dev.lyo.hortay.data.AutoDownloadStore
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.LinkDialogState
import dev.lyo.hortay.data.report.ReportDialogState
import dev.lyo.hortay.data.report.ReportExplainerStore
import dev.lyo.hortay.data.report.ReportLogStore
import dev.lyo.hortay.data.report.ReportRepository
import dev.lyo.hortay.data.toStringResolver
import dev.lyo.hortay.ui.report.GuestReportDelegator
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.ChatFoldersRepository
import dev.lyo.hortay.data.CommentsRepository
import dev.lyo.hortay.data.CountryRepository
import dev.lyo.hortay.data.CustomEmojiRepository
import dev.lyo.hortay.data.DeepLinkRouter
import dev.lyo.hortay.data.IgnoredChannelsStore
import dev.lyo.hortay.data.MediaAutoDownloader
import dev.lyo.hortay.data.MediaCache
import dev.lyo.hortay.data.MessageMapper
import dev.lyo.hortay.data.NavStack
import dev.lyo.hortay.data.PostsRepository
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.data.StartupCoordinator
import dev.lyo.hortay.data.StatsRepository
import dev.lyo.hortay.data.TelegramLinkResolver
import dev.lyo.hortay.data.TdClient
import dev.lyo.hortay.data.TdLifecycleBridge
import dev.lyo.hortay.data.DataStoreTimelineSnapshotStore
import dev.lyo.hortay.data.TimelineSnapshotStore
import dev.lyo.hortay.data.TranslationsStore
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.data.web.GuestModeStore
import dev.lyo.hortay.data.web.MigrationCoordinator
import dev.lyo.hortay.data.web.MigrationStore
import dev.lyo.hortay.data.web.SubscriptionsStore
import dev.lyo.hortay.data.web.WebCustomEmojiBridge
import dev.lyo.hortay.data.web.WebCustomEmojiResolver
import dev.lyo.hortay.data.web.WebFeedScheduler
import dev.lyo.hortay.data.web.WebFeedSource
import dev.lyo.hortay.data.web.WebRepository
import dev.lyo.hortay.data.web.WebTelegramClient
import dev.lyo.hortay.data.web.db.WebDatabase
import dev.lyo.hortay.data.web.db.WebDatabaseProvider
import java.io.File
import androidx.lifecycle.ProcessLifecycleOwner
import dev.lyo.hortay.ui.media.CustomEmojiAnimator
import dev.lyo.hortay.ui.media.ExoPlayerPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Manual dependency graph for the app. Lightweight alternative to Hilt for a single-process
 * app with a small object set; easy to grow into a multi-module setup later.
 *
 * Lifetime: created once in [HortayApp.onCreate] and held for the entire process. All
 * coroutines launched here use [appScope], which is cancelled only on process death.
 */
class AppGraph(context: Context) {

    private val res = context.resources.toStringResolver()

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Process-wide user-message bus. Repositories post errors here; MainScaffold
    // collects and renders Snackbars regardless of which screen is in front. See
    // [UserMessageBus] for the rationale on a singleton bus vs per-screen state.
    val userMessages: UserMessageBus = UserMessageBus()

    // Declared before [tdClient] because TdClient consumes it for OptimizeStorage
    // throttling (the cleanup needs a persisted "last run at" timestamp to skip on
    // every cold start).
    val settingsStore: SettingsStore = SettingsStore(context)

    // Tiny `(chatId, messageId)` snapshot of the top of the feed, persisted across
    // process death so cold start renders real content sub-100ms instead of a blank
    // screen for the multi-second refresh round-trip storm.
    val timelineSnapshotStore: TimelineSnapshotStore = DataStoreTimelineSnapshotStore(context)

    val tdClient: TdClient = TdClient.create(context, settingsStore).also { it.start() }

    // Bridge ProcessLifecycleOwner + ConnectivityManager into TDLib so the daemon knows
    // when we're foreground/online and what network it should plan downloads for. The
    // bridge owns the canonical app-foreground signal; MediaCache reads it to park its
    // stall watchdog while the user isn't looking. Held by the graph to keep the listener
    // alive for the process lifetime.
    private val lifecycleBridge: TdLifecycleBridge =
        TdLifecycleBridge(tdClient, context, appScope).also { it.bind() }

    // MediaCache reads tdClient.connection so the stall watchdog skips reissue ticks
    // while we're WaitingForNetwork — TDLib resumes downloads itself when the link
    // returns, and reissuing in the meantime would just rack up retry counts and prematurely
    // mark slots Failed. lifecycleBridge.foreground does the same job for app-background:
    // suspend the watchdog entirely (zero CPU/battery) until the app comes back to focus.
    val mediaCache: MediaCache =
        MediaCache(
            td = tdClient,
            scope = appScope,
            connection = tdClient.connection,
            foreground = lifecycleBridge.foreground,
            networkType = lifecycleBridge.networkType,
            res = res,
        )

    // Shared between PostsRepository (channel feed) and CommentsRepository (discussion
    // threads) so an author resolved in one context is reused in the other — same user
    // appearing as a feed post AND as a thread reply hits the cache twice.
    private val messageMapper: MessageMapper = MessageMapper(tdClient, res)

    /**
     * Channels the user has chosen to hide from the merged feed. See
     * [IgnoredChannelsStore] KDoc for the cross-mode contract. Wired before
     * [postsRepository] / [webFeedSource] because both consume it for the
     * read-side filter applied to their respective merged-feed flows.
     */
    val ignoredChannels: IgnoredChannelsStore = IgnoredChannelsStore(context)

    val postsRepository: PostsRepository = PostsRepository(
        td = tdClient,
        mapper = messageMapper,
        scope = appScope,
        userMessages = userMessages,
        connection = tdClient.connection,
        snapshotStore = timelineSnapshotStore,
        foreground = lifecycleBridge.foreground,
        res = res,
        ignoredChannels = ignoredChannels,
    )

    val commentsRepository: CommentsRepository = CommentsRepository(tdClient, messageMapper, appScope, res)

    /**
     * Process-wide cold-start gate. Holds the [StartupCoordinator.Phase.Booting] flag
     * for the first ~3 seconds after [AuthorizationStateReady] (or until the feed
     * reaches ~20 posts, whichever fires first) plus a 1.5 s settle buffer. Consumed
     * by [autoDownloader] and the [ui.timeline.TimelineScreen] comments-thread
     * prefetch collector to skip speculative work during the post-auth RPC storm —
     * see its KDoc for the full rationale.
     */
    val startupCoordinator: StartupCoordinator = StartupCoordinator(
        authStage = tdClient.authStage,
        posts = postsRepository.posts,
        scope = appScope,
    )

    /**
     * User-configurable per-network auto-download policy (Wi-Fi / mobile / roaming),
     * mirroring Telegram's "Data and Storage" → "Auto-Download Media" UX. Persisted
     * as a single JSON blob in DataStore. Held by the graph because both the
     * settings UI (read/write) and [autoDownloader] (read-only) need the same
     * instance — splitting them would invalidate the shared cache and force the
     * downloader to re-decode the JSON on every settings emit.
     */
    val autoDownloadStore: AutoDownloadStore = AutoDownloadStore(context)

    /**
     * Background service that prefetches photos / videos / animations for posts that
     * arrive via [TdApi.UpdateNewMessage] (real-time) per [autoDownloadStore]. Posts
     * pulled by refresh / pagination / snapshot restore / channel-history back-fill are
     * NOT auto-downloaded — those go through the viewport-driven prefetch path in
     * [TimelineScreen] when (and only when) the user scrolls them into view.
     *
     * Bound at process start; lives for the entire app lifetime via [appScope]. Reads
     * the network classification from [TdLifecycleBridge.networkType] (own coarse enum
     * that distinguishes roaming, which TDLib's [TdApi.NetworkType] does not).
     */
    val autoDownloader: MediaAutoDownloader = MediaAutoDownloader(
        store = autoDownloadStore,
        cache = mediaCache,
        newArrivalsFlow = postsRepository.newArrivals,
        networkType = lifecycleBridge.networkType,
        connection = tdClient.connection,
        foreground = lifecycleBridge.foreground,
        startupPhase = startupCoordinator.phase,
        scope = appScope,
    ).also { it.bind() }

    val bookmarkStore: BookmarkStore = BookmarkStore(context)

    val statsRepository: StatsRepository = StatsRepository(tdClient)

    val chatFoldersRepository: ChatFoldersRepository = ChatFoldersRepository(tdClient, appScope)

    val translations: TranslationsStore =
        TranslationsStore(tdClient, appScope, userMessages, tdClient.connection, res)

    val channelActions: ChannelActionsRepository =
        ChannelActionsRepository(tdClient, userMessages, tdClient.connection, res)

    val countries: CountryRepository = CountryRepository(tdClient, res)

    // Custom-emoji resolver for inline emojis in formatted text and for custom-emoji
    // reaction buckets. Uses GetCustomEmojiStickers in batches of up to 200 ids; the
    // request stream is debounced 50ms so a screen-full of posts coalesces into a
    // single TDLib call.
    val customEmoji: CustomEmojiRepository = CustomEmojiRepository(tdClient, appScope)

    /**
     * Process-wide frame driver for inline custom-emoji TGS playback. One
     * [com.airbnb.lottie.LottieDrawable] + one shared progress state per `customEmojiId`,
     * driven by a single [android.view.Choreographer.FrameCallback] master clock. Inline
     * emoji repeats (the same `customEmojiId` mounted N times in a post) share one
     * playback session — see [CustomEmojiAnimator] KDoc for the full rationale.
     *
     * Wired with [ProcessLifecycleOwner] so background pauses are honoured globally —
     * when the app moves below STARTED the master clock stops posting frame callbacks.
     */
    val customEmojiAnimator: CustomEmojiAnimator = CustomEmojiAnimator(
        processLifecycle = ProcessLifecycleOwner.get().lifecycle,
    )

    // Vector-silhouette cache for the outline → thumb → sticker visual ladder. TDLib's
    // [TdApi.GetStickerOutlineSvgPath] is offline (microseconds over JNI) but we still
    // memoise the parsed Compose Path so repeated viewport entries don't re-parse the
    // same SVG. See [StickerOutlineStore] for the LRU + negative-cache contract.
    val stickerOutline: dev.lyo.hortay.ui.media.StickerOutlineStore =
        dev.lyo.hortay.ui.media.StickerOutlineStore(tdClient)

    // Shared ExoPlayer pool. Each ExoPlayer instance is heavy (MediaCodec decoders +
    // surface threads + WakeLockManager + AudioMix wakelock), and the per-Composable
    // `Builder().build()` pattern in TdVideoPlayer / WebmStickerPlayer churned 38 of
    // them per minute of fast scroll on profiling — most living 0 ms. Pooling makes
    // scroll past video cards essentially free at the player layer.
    val exoPlayerPool: ExoPlayerPool = ExoPlayerPool(context)

    /**
     * Process-wide delivery channel for resolved Telegram deep links. The parser
     * ([linkResolver]) submits typed events here; `MainScaffold` / `WebModeScaffold`
     * collect them and dispatch navigation. Lives on the graph so a single Channel
     * survives configuration changes — re-creating the router per Activity would lose
     * buffered links arriving during the recreation.
     */
    val deepLinkRouter: DeepLinkRouter = DeepLinkRouter()

    /**
     * Canonical Telegram-URL parser. Delegates to TDLib's `GetInternalLinkType` (knows
     * ~50 link variants, offline, pre-auth-safe) when the daemon is reachable, falls
     * back to a local regex for the three shapes our UI dispatches. Called from
     * `MainActivity` (cold-launch intents) and the in-app `HortayUriHandler` (link taps
     * inside post bodies, web previews, settings author rows, etc.) — single source of
     * truth for "is this a Telegram URL, and what does it mean".
     */
    val linkResolver: TelegramLinkResolver = TelegramLinkResolver(tdClient)
        .also { it.bindLogoutClear(tdClient.loggedOut, appScope) }

    /**
     * Process-wide dialog state for link-confirmation flows. Hoisted off the scaffolds
     * because they live on the AppGraph: each is set by an in-flight suspending call
     * (`HortayUriHandler.openUri` for masked links, the deep-link collector for invite
     * previews) that can outlive a rotation. Storing the pending value in a saveable
     * inside the scaffold was a real bug — the writeback happened AFTER the saveable
     * bag was packed, so the rotated composition started fresh and the dialog never
     * appeared. A MutableStateFlow on the graph survives configuration changes
     * naturally (graph is process-lifetime) without needing Parcelize on
     * [ChatInvitePreview].
     *
     * Trade-off: state is NOT persisted across process death. For a "user just tapped
     * a link" dialog that's correct — a killed process represents user abandonment,
     * not pending intent. Telegram-Android has the same boundary.
     */
    val linkDialogs: LinkDialogState = LinkDialogState()

    /**
     * Process-wide polymorphic back-stack for channel-drill and comments
     * overlays. Replaces the three disjoint stacks that previously lived on
     * each scaffold (`channelStack`, `commentsForPost`, `pendingScrollTarget`).
     * See [NavStack] KDoc for the full lifetime contract.
     *
     * Lives on the graph because deep-link routing (`DeepLinkRouter`) may
     * push entries before the UI scaffold composes, and because per-entry
     * ViewModel keying is keyed on a stable [NavEntry.entryId] that survives
     * scaffold recompose / tab swap.
     */
    val nav: NavStack = NavStack()

    /**
     * Anonymous-mode SQLDelight database. Stores channel metadata, post payloads,
     * resolved custom-emoji assets and curated/discovery suggestions. Construction
     * triggers schema creation on first launch and migration validation on every
     * launch (the app's `sqldelight { verifyMigrations.set(true) }` runs at build
     * time, but the runtime PRAGMA setup happens here in [WebDatabaseProvider]).
     * Held by the graph so the [AndroidSqliteDriver]'s connection pool survives
     * for the entire process lifetime — re-opening the DB per call would cost
     * 50-100 ms of WAL-mode setup on every read.
     */
    val webDatabase: WebDatabase = WebDatabaseProvider.create(context)

    val webRepository: WebRepository = WebRepository(webDatabase)

    /**
     * Anonymous web-mode HTTP client. Reads public channel previews via
     * t.me/s/<username> with no Telegram authentication required. The shared
     * 10 MB OkHttp [Cache] under `cacheDir/web-http/` persists ETag /
     * Last-Modified across cold starts so a 200-channel sweep that's already
     * been done in this session avoids re-downloading every body — typically a
     * 80-90% bandwidth saving on the second-of-the-day refresh.
     */
    /**
     * Single shared OkHttpClient for the anonymous-mode pipeline. The same instance
     * powers [webClient] (channel HTML), [webCustomEmoji] (emoji JSON), and
     * [dev.lyo.hortay.ui.media.LottieUrlStore] (TGS payload bytes). Sharing matters:
     * connection-pool reuse + the ETag-aware [okhttp3.Cache] under
     * `cacheDir/web-http/` cuts a 200-channel sweep ~80-90% on a warm cache, and a
     * cold TGS resolve hits the same H2 connection that the JSON resolve already
     * opened to t.me.
     */
    val webHttpClient: okhttp3.OkHttpClient =
        WebTelegramClient.defaultHttpClient(File(context.cacheDir, "web-http"))

    val webClient: WebTelegramClient = WebTelegramClient(webHttpClient)

    /**
     * Resolves Telegram custom-emoji ids to renderable TGS / WebM / WebP assets via
     * the public `/i/emoji/<id>.json` endpoint. Used by the anonymous-mode UI to
     * surface real custom-emoji reactions and inline-emoji animations — without it
     * web mode would be limited to neutral chips for any emoji-id that has no
     * unicode fallback baked into the static HTML.
     */
    val webCustomEmoji: WebCustomEmojiResolver = WebCustomEmojiResolver(webHttpClient)

    /**
     * Persistent list of channel usernames the user has subscribed to in anonymous
     * mode. Survives across cold starts independently of TDLib's chat list. When a
     * user signs in, Phase 2's migration step uses this set to drive auto-subscribe
     * via TdApi.SearchPublicChat + TdApi.JoinChat (throttled).
     */
    val webSubscriptions: SubscriptionsStore = SubscriptionsStore(context)

    /** Persists "use the app without signing in" choice. See [GuestModeStore]. */
    val guestMode: GuestModeStore = GuestModeStore(context)

    /**
     * Multi-channel orchestrator. Mirrors [webSubscriptions] into the channel
     * table, fans out parallel fetches into [webRepository.ingestPage], and
     * exposes the merged feed as a [kotlinx.coroutines.flow.StateFlow]. UI
     * surfaces (timeline, channels list) bind directly to its `posts` /
     * `channels` flows; the source itself is process-singleton because the
     * DataStore→DB sync needs exactly one observer to avoid double-writes.
     */
    val webFeedSource: WebFeedSource = WebFeedSource(
        client = webClient,
        repository = webRepository,
        subscriptions = webSubscriptions,
        scope = appScope,
        ignoredChannels = ignoredChannels,
    )

    /**
     * Tier-2 foreground polling for the web pipeline. Bound here so the
     * scheduler runs for the entire process lifetime — its foreground
     * StateFlow source already auto-pauses sweeps when the app backgrounds,
     * so there's no battery cost while not in use. Background sweeps
     * (tier-3 WorkManager) and viewport-driven sweeps (tier-1) layer in
     * later without changing this wiring.
     */
    val webFeedScheduler: WebFeedScheduler = WebFeedScheduler(
        feedSource = webFeedSource,
        foreground = lifecycleBridge.foreground,
        // Mode-aware pause: poll only when the user is BOTH unauthenticated to
        // TDLib AND has explicitly opted into guest mode. [MainActivity] mounts
        // [WebModeScaffold] under exactly that condition; matching it here
        // means tier-2 never polls for a surface that isn't on screen. The
        // earlier auth-only gate kept polling alive on the AuthScreen and
        // burned traffic + battery for a UI that doesn't render the feed,
        // with a real FLOOD_WAIT risk. Scheduler resumes automatically when
        // the user re-enters guest mode and the app is foreground.
        authStage = tdClient.authStage,
        isGuest = guestMode.isGuest,
        scope = appScope,
    ).also { it.bind() }

    /**
     * Bridges resolved t.me/i/emoji/<id>.json results into the same
     * [CustomEmojiRepository] that TDLib mode uses. Lets the shared
     * [dev.lyo.hortay.ui.media.CustomEmojiInlineView] render web custom
     * emojis (in formatted-text spans and reaction chips) without forking the
     * inline-view code. TGS animates via [dev.lyo.hortay.ui.media.LottieUrlStore]
     * (real Lottie-Compose playback, parity with TDLib mode); WebM falls back
     * to a static WEBP thumb because the t.me/i/emoji endpoint bakes alpha
     * into a sidecar VP9 stream that Android's MediaCodec doesn't decode —
     * see [WebCustomEmojiBridge] KDoc for the full rationale.
     */
    val webCustomEmojiBridge: WebCustomEmojiBridge = WebCustomEmojiBridge(
        resolver = webCustomEmoji,
        customEmojiRepo = customEmoji,
        feed = webFeedSource.posts,
        scope = appScope,
    ).also { it.bind() }

    /**
     * Tracks whether the one-time "migrate guest subscriptions to your TDLib
     * account" proposal has been shown, plus which usernames the user already
     * approved. Held separately from [webSubscriptions] so signing out doesn't
     * erase migration history (the user might re-add a few of the same channels
     * in guest mode before re-authenticating).
     */
    val migrationStore: MigrationStore = MigrationStore(context)

    /**
     * Drives the post-sign-in migration proposal. Listens for [authStage] →
     * Ready transitions; when one fires AND the proposal hasn't been shown
     * AND the guest-subscription set is non-empty, exposes a non-null
     * [MigrationCoordinator.pendingProposal] that [MainActivity] renders as a
     * [dev.lyo.hortay.ui.web.MigrationProposalSheet]. Confirmation throttles
     * SearchPublicChat + JoinChat at 1/sec to keep TDLib's flood-control happy.
     */
    val migrationCoordinator: MigrationCoordinator = MigrationCoordinator(
        migrationStore = migrationStore,
        subscriptions = webSubscriptions,
        postsRepository = postsRepository,
        channelActions = channelActions,
        authStage = tdClient.authStage,
        scope = appScope,
    ).also { it.bind() }

    // ---- CSAE-compliance: child safety reporting --------------------------------

    /**
     * Append-only JSONL audit log for all report attempts (both auth and guest mode).
     * Max 200 entries; rotated on every log() call after the ceiling is reached.
     * File lives in [Context.filesDir]/report_log.jsonl — not in cacheDir so it
     * survives cache clears and can be inspected by compliance auditors.
     */
    val reportLogStore: ReportLogStore = ReportLogStore(context)

    /**
     * DataStore flag that persists whether the one-time "reports go to Telegram
     * moderators" explainer has been shown. Resets on logout so a new account
     * always sees it on first report.
     */
    val reportExplainerStore: ReportExplainerStore = ReportExplainerStore(context)

    /**
     * TDLib-based dynamic ReportChat flow. Called from [MainScaffold]'s
     * [dev.lyo.hortay.ui.report.ReportFlowSheet] when the user reports in
     * authenticated mode. Each call maps [TdApi.ReportChatResult] variants to
     * [dev.lyo.hortay.data.report.ReportState] and logs the terminal outcome.
     */
    val reportRepository: ReportRepository = ReportRepository(
        td = tdClient,
        resolver = res,
        log = reportLogStore,
    )

    /**
     * Process-wide holder for the active report-flow target. Hoisted off
     * `MainScaffold`'s local state for the same reason [linkDialogs] is hoisted:
     * `ReportFlowSheet`'s ViewModel keeps partial answers across TDLib
     * roundtrips, and a rotation mid-flow would otherwise null the target and
     * drop the sheet on the floor before the user gets to submit. Cleared on
     * logout via [runLogoutCleanup]. See [ReportDialogState] KDoc for the
     * lifetime contract.
     */
    val reportDialogs: ReportDialogState = ReportDialogState()

    /**
     * Guest-mode report delegation chain: tg:// → CustomTabsIntent → mailto:.
     * Called from [dev.lyo.hortay.ui.web.WebModeScaffold] when the user reports
     * a post while not signed in to TDLib. Each delegation step logs its outcome
     * to [reportLogStore].
     */
    val guestReportDelegator: GuestReportDelegator = GuestReportDelegator(
        context = context,
        log = reportLogStore,
        logScope = appScope,
    )

    init {
        // Pre-warm the web DB on a background thread. SQLDelight's AndroidSqliteDriver
        // lazy-opens the underlying SupportSQLiteOpenHelper on first query — without
        // this touch the schema creation, WAL switch and PRAGMA setup would all run
        // synchronously inside the first observeFeed() collector on the main thread.
        // A no-op SELECT here moves that one-time ~50-100 ms cost off the critical
        // path so the first feed render isn't paying for it.
        appScope.launch { webRepository.subscribedUsernames() }

        // Logout cleanup fan-out. TDLib's database is being torn down
        // (LogOut → AuthorizationStateClosing → Closed) and account A's
        // file ids / chat ids / message ids are about to become invalid.
        // Wipe every per-account in-memory cache + the on-disk timeline
        // snapshot before TDLib's spawnClient() rebuilds a fresh native
        // session, so account B (or the empty AuthScreen if the user
        // doesn't sign in again) doesn't briefly render account A's last
        // feed.
        //
        // MigrationStore is reset specifically: a per-app proposal-shown
        // flag would otherwise suppress the migration offer when the user
        // signs in to a *different* Telegram account on the same device,
        // even though that account has never seen the offer.
        //
        // collect on appScope so the handler outlives any UI scope.
        appScope.launch {
            tdClient.loggedOut.collect { runLogoutCleanup() }
        }
    }

    /**
     * Drop every piece of per-account state held by the graph. Each repo
     * is responsible for its own cleanup; this method is just the
     * orchestrator. Errors from individual clears are logged but never
     * propagated — a partial wipe is still better than aborting the
     * cleanup midway and leaving half the caches stale.
     */
    private suspend fun runLogoutCleanup() {
        // PostsRepository.clear() already wipes the on-disk snapshot
        // internally (it owns the snapshotStore reference and the
        // semantic ownership), so we don't double-clear it here.
        // ChatPresence is a process-singleton object — wipe its
        // refcount map so account A's leftover OpenChat counts can't
        // poison account B's open/close arithmetic.
        runCatching { postsRepository.clear() }
        runCatching { dev.lyo.hortay.data.ChatPresence.clear() }
        runCatching { messageMapper.clear() }
        runCatching { commentsRepository.clear() }
        runCatching { mediaCache.clear() }
        runCatching { customEmoji.clear() }
        runCatching { customEmojiAnimator.clear() }
        runCatching { stickerOutline.clear() }
        runCatching { chatFoldersRepository.clear() }
        runCatching { translations.clear() }
        runCatching { migrationStore.reset() }
        // Drop any in-flight report sheet — its target's chatId/messageId
        // belongs to account A's TDLib database and will be invalid the moment
        // the new client spawns.
        runCatching { reportDialogs.close() }
        // Drop any in-flight drill stack — chatId / messageIds belong to
        // account A's TDLib database and will be invalid the moment the new
        // client spawns.
        runCatching { nav.clear() }
    }
}

