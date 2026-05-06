package dev.lyo.hortay

import android.content.Context
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.toStringResolver
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.ChatFoldersRepository
import dev.lyo.hortay.data.CommentsRepository
import dev.lyo.hortay.data.CountryRepository
import dev.lyo.hortay.data.CustomEmojiRepository
import dev.lyo.hortay.data.DeepLinkRouter
import dev.lyo.hortay.data.MediaCache
import dev.lyo.hortay.data.MessageMapper
import dev.lyo.hortay.data.PostsRepository
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.data.StatsRepository
import dev.lyo.hortay.data.TdClient
import dev.lyo.hortay.data.TdLifecycleBridge
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
import dev.lyo.hortay.ui.media.ExoPlayerPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val timelineSnapshotStore: TimelineSnapshotStore = TimelineSnapshotStore(context)

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
        MediaCache(tdClient, appScope, tdClient.connection, lifecycleBridge.foreground, res)

    // Shared between PostsRepository (channel feed) and CommentsRepository (discussion
    // threads) so an author resolved in one context is reused in the other — same user
    // appearing as a feed post AND as a thread reply hits the cache twice.
    private val messageMapper: MessageMapper = MessageMapper(tdClient, res)

    val postsRepository: PostsRepository = PostsRepository(
        td = tdClient,
        mapper = messageMapper,
        scope = appScope,
        userMessages = userMessages,
        connection = tdClient.connection,
        snapshotStore = timelineSnapshotStore,
        foreground = lifecycleBridge.foreground,
        res = res,
    )

    val commentsRepository: CommentsRepository = CommentsRepository(tdClient, messageMapper, appScope, res)

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

    // Shared ExoPlayer pool. Each ExoPlayer instance is heavy (MediaCodec decoders +
    // surface threads + WakeLockManager + AudioMix wakelock), and the per-Composable
    // `Builder().build()` pattern in TdVideoPlayer / WebmStickerPlayer churned 38 of
    // them per minute of fast scroll on profiling — most living 0 ms. Pooling makes
    // scroll past video cards essentially free at the player layer.
    val exoPlayerPool: ExoPlayerPool = ExoPlayerPool(context)

    /**
     * Process-wide router for `tg://` and `https://t.me/...` deep links. MainActivity
     * submits incoming intents; MainScaffold collects events and dispatches navigation.
     * Lives on the graph so a single SharedFlow survives configuration changes — re-creating
     * the router per Activity would lose buffered links arriving during the recreation.
     */
    val deepLinkRouter: DeepLinkRouter = DeepLinkRouter()

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

    init {
        // Pre-warm the web DB on a background thread. SQLDelight's AndroidSqliteDriver
        // lazy-opens the underlying SupportSQLiteOpenHelper on first query — without
        // this touch the schema creation, WAL switch and PRAGMA setup would all run
        // synchronously inside the first observeFeed() collector on the main thread.
        // A no-op SELECT here moves that one-time ~50-100 ms cost off the critical
        // path so the first feed render isn't paying for it.
        appScope.launch { webRepository.subscribedUsernames() }
    }
}

