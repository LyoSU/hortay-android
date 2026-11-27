package dev.lyo.hortay

import dev.lyo.hortay.data.resolveActivePolicy
import android.content.Context
import dev.lyo.hortay.data.AuthStage
import dev.lyo.hortay.data.AutoDownloadStore
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.LinkDialogState
import dev.lyo.hortay.data.report.ReportDialogState
import dev.lyo.hortay.data.report.ReportExplainerStore
import dev.lyo.hortay.data.report.ReportLogStore
import dev.lyo.hortay.data.report.ReportRepository
import dev.lyo.hortay.data.toStringResolver
import dev.lyo.hortay.ui.report.GuestReportDelegator
import dev.lyo.hortay.data.AccountSession
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.ChannelMediaRepository
import dev.lyo.hortay.data.discover.ChannelDiscoveryRepository
import dev.lyo.hortay.data.discover.ChannelSuggestionsRepository
import dev.lyo.hortay.data.ChatFoldersRepository
import dev.lyo.hortay.data.ChannelDirectory
import dev.lyo.hortay.data.ChatRegistry
import dev.lyo.hortay.data.CommentsRepository
import dev.lyo.hortay.data.CountryRepository
import dev.lyo.hortay.data.CustomEmojiRepository
import dev.lyo.hortay.data.DeepLinkRouter
import dev.lyo.hortay.data.EmojiStyle
import dev.lyo.hortay.data.ReactionEmojiIconRepository
import dev.lyo.hortay.data.ColdStartBackfillStore
import dev.lyo.hortay.data.ColdStartBackfillStoreImpl
import dev.lyo.hortay.data.IgnoredChannelsStore
import dev.lyo.hortay.data.MediaAutoDownloader
import dev.lyo.hortay.data.MediaCache
import dev.lyo.hortay.data.ProfileAccentRegistry
import dev.lyo.hortay.data.MessageMapper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.lyo.hortay.data.nav.AppNavKey
import dev.lyo.hortay.data.suspendRunCatching
import dev.lyo.hortay.data.warnUnlessCancelled
import dev.lyo.hortay.data.nav.HomeKey
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.OnboardingStore
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.data.StartupCoordinator
import dev.lyo.hortay.data.StatsRepository
import dev.lyo.hortay.data.StorageOptimizer
import dev.lyo.hortay.data.TelegramLinkResolver
import dev.lyo.hortay.data.TdClient
import dev.lyo.hortay.data.TdLifecycleBridge
import dev.lyo.hortay.data.DataStoreTimelineSnapshotStore
import dev.lyo.hortay.data.TimelineSnapshotStore
import dev.lyo.hortay.data.TranslationsStore
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.data.subscriptions.SubscriptionTransfer
import dev.lyo.hortay.data.subscriptions.KnownChannelMembership
import dev.lyo.hortay.data.subscriptions.ThrottledChannelJoiner
import dev.lyo.hortay.data.web.GuestModeStore
import dev.lyo.hortay.data.web.MigrationCoordinator
import dev.lyo.hortay.data.web.MigrationStore
import dev.lyo.hortay.data.web.SubscriptionsStore
import dev.lyo.hortay.data.web.WebCustomEmojiBridge
import dev.lyo.hortay.data.web.WebCustomEmojiResolver
import dev.lyo.hortay.data.web.WebFeedScheduler
import dev.lyo.hortay.data.web.WebFeedSource
import dev.lyo.hortay.data.web.WebRepository
import dev.lyo.hortay.data.web.WebReadAck
import dev.lyo.hortay.data.web.WebTelegramClient
import dev.lyo.hortay.data.web.WebViewReporter
import dev.lyo.hortay.data.archive.ArchiveRepository
import dev.lyo.hortay.data.archive.ArchiveSettings
import dev.lyo.hortay.data.archive.ArchiveSettingsStore
import dev.lyo.hortay.data.archive.ArchiveSweep
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import dev.lyo.hortay.data.web.db.WebDatabase
import dev.lyo.hortay.data.web.db.WebDatabaseProvider
import java.io.File
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.lyo.hortay.ui.media.CustomEmojiAnimator
import dev.lyo.hortay.ui.media.ExoPlayerPool
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manual dependency graph for the app. Lightweight alternative to Hilt for a single-process
 * app with a small object set; easy to grow into a multi-module setup later.
 *
 * Lifetime: created once in [HortayApp.onCreate] and held for the entire process. All
 * coroutines launched here use [appScope], which is cancelled only on process death.
 */
class AppGraph(context: Context) {

    private val res = context.resources.toStringResolver()

    /**
     * The [CoroutineExceptionHandler] is a last-resort net: a fire-and-forget launch whose body
     * throws (an SQLite "disk full" during archive capture, a mapper NPE on an unexpected TDLib
     * payload) would otherwise reach the thread's uncaught-exception handler and kill the
     * process. Logged and dropped instead — one bad update must not take the app down. Call
     * sites still wrap their own bodies; this only catches what slips past them.
     * [AccountSession.scope] inherits this context, so session work is covered too.
     */
    val appScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> android.util.Log.e(TAG, "uncaught in appScope", e) },
    )

    // Process-wide user-message bus. Repositories post errors here; MainScaffold
    // collects and renders Snackbars regardless of which screen is in front. See
    // [UserMessageBus] for the rationale on a singleton bus vs per-screen state.
    val userMessages: UserMessageBus = UserMessageBus()

    // Declared before [tdClient] because TdClient consumes it for OptimizeStorage
    // throttling (the cleanup needs a persisted "last run at" timestamp to skip on
    // every cold start).
    val settingsStore: SettingsStore = SettingsStore(context)

    /** Device-level one-shot feature-discovery flags. See [OnboardingStore]. */
    val onboardingStore: OnboardingStore = OnboardingStore(context)

    init {
        // Rate-prompt bookkeeping: one DataStore edit per process start, on appScope so cold
        // start never waits on it.
        appScope.launch { onboardingStore.recordLaunchDay() }
    }

    /**
     * [SettingsStore.archiveInAllFeed], pre-warmed. It is part of the feed's scene key, so
     * a `collectAsState(initialValue = false)` flipping to `true` after the snapshot has
     * latched would rebuild the feed under the reader. Started eagerly here, DataStore has
     * long emitted by the time `MainScaffold` mounts (it waits for TDLib auth).
     */
    internal val archiveInAllFeedState: StateFlow<Boolean> =
        settingsStore.archiveInAllFeed.stateIn(appScope, SharingStarted.Eagerly, initialValue = false)

    /** [SettingsStore.groupFeed], pre-warmed like [archiveInAllFeedState]. */
    internal val groupFeedState: StateFlow<Boolean> =
        settingsStore.groupFeed.stateIn(appScope, SharingStarted.Eagerly, initialValue = true)

    /**
     * [SettingsStore.dynamicColor], pre-warmed for the same reason as [archiveInAllFeedState]:
     * `MainActivity` themes the whole window from it, and a lazily started collector would paint
     * its `initialValue` for the first frames. Started eagerly at graph construction, DataStore
     * has usually emitted by the time the first composition reads it. `initialValue` matches the
     * store default (`false`, brand palette), so even a lost race never flashes wallpaper colours
     * for a user who hasn't opted in.
     */
    internal val dynamicColorState: StateFlow<Boolean> =
        settingsStore.dynamicColor.stateIn(appScope, SharingStarted.Eagerly, initialValue = false)

    // Tiny `(chatId, messageId)` snapshot of the top of the feed, persisted across
    // process death so cold start renders real content sub-100ms instead of a blank
    // screen for the multi-second refresh round-trip storm.
    val timelineSnapshotStore: TimelineSnapshotStore = DataStoreTimelineSnapshotStore(context)

    val tdClient: TdClient = TdClient.create(context, settingsStore).also { it.start() }

    /**
     * Lifetime of the signed-in account. Every session-scoped state holder registers its
     * logout wipe here ([AccountSession.onEnd]) next to its construction; bound to
     * [TdClient.loggedOut] in `init` once every hook is registered.
     */
    val session: AccountSession = AccountSession(appScope)

    /**
     * Per-user profile accent colours (the gradients Telegram users pick for
     * themselves). Bound to the update stream + logout signal here; consumed by the
     * Settings hero and the user-profile sheet via `LocalProfileAccent`.
     */
    val profileAccent: ProfileAccentRegistry =
        ProfileAccentRegistry().also { registry ->
            registry.bind(tdClient.updates, appScope)
            session.onEnd("profileAccent") { registry.clear() }
        }

    // Bridge ProcessLifecycleOwner + ConnectivityManager into TDLib so the daemon knows
    // when we're foreground/online and what network it should plan downloads for. The
    // bridge owns the canonical app-foreground signal; MediaCache reads it to park its
    // stall watchdog while the user isn't looking. Held by the graph to keep the listener
    // alive for the process lifetime.
    private val lifecycleBridge: TdLifecycleBridge =
        TdLifecycleBridge(tdClient, context, appScope, settingsStore).also { it.bind() }

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

    /**
     * Update-fed users / supergroups mirror — see [ChatRegistry]. Its logout wipe is NOT
     * a construction-time [AccountSession.onEnd] hook: it runs in [runLogoutCleanup]
     * right AFTER `postsRepository.clear()`, because an in-flight history load stores
     * the chat it fetched (`rememberChat`) — wiping the registry first let that load
     * re-insert account A's `Chat` after the wipe.
     */
    private val chatRegistry: ChatRegistry =
        ChatRegistry(tdClient).also { registry -> registry.bind(appScope) }

    // Shared between PostsRepository (channel feed) and CommentsRepository (discussion
    // threads) so an author resolved in one context is reused in the other.
    private val messageMapper: MessageMapper = MessageMapper(tdClient, res, chatRegistry)

    /** Read-only channel lookups (header metadata, handle resolution, share links). */
    val channelDirectory: ChannelDirectory = ChannelDirectory(tdClient, chatRegistry)

    /**
     * Channels the user has chosen to hide from the merged feed. See
     * [IgnoredChannelsStore] KDoc for the cross-mode contract. Wired before
     * [postsRepository] / [webFeedSource] because both consume it for the
     * read-side filter applied to their respective merged-feed flows.
     */
    val ignoredChannels: IgnoredChannelsStore = IgnoredChannelsStore(context)

    /** Words whose posts stay out of the merged feed; see [dev.lyo.hortay.data.MutedWordsStore]. */
    val mutedWords: dev.lyo.hortay.data.MutedWordsStore = dev.lyo.hortay.data.MutedWordsStore(context)

    /**
     * One-shot persisted flag that gates [PostsRepository.runFirstSignInBackfill].
     * Reset on TDLib `loggedOut` via [runLogoutCleanup] so a fresh sign-in
     * re-runs the backfill — the cold-start UX it protects only ever bites
     * when there is no cached history (different account, or first launch).
     */
    val coldStartBackfillStore: ColdStartBackfillStore = ColdStartBackfillStoreImpl(context)

    // Archive database and repository — declared before postsRepository so the capture
    // sink is available when PostsRepository is wired.
    // archive.db opens (and migrates) on first use; a failure degrades to an empty in-memory
    // archive for the run instead of crashing whichever flow touched it first.
    val archiveDriver: dev.lyo.hortay.data.archive.GuardedSqlDriver =
        dev.lyo.hortay.data.archive.openArchiveDriver(context)
    val archiveDb: ArchiveDatabase = ArchiveDatabase(archiveDriver)

    val archiveSettingsStore: ArchiveSettingsStore = ArchiveSettingsStore(context)

    internal val archiveSettingsState: StateFlow<ArchiveSettings> =
        archiveSettingsStore.flow.stateIn(
            scope = appScope,
            started = SharingStarted.Eagerly,
            initialValue = ArchiveSettings.DEFAULT,
        )

    val archivedMediaStore: dev.lyo.hortay.data.archive.ArchivedMediaStore =
        dev.lyo.hortay.data.archive.ArchivedMediaStore(context, archiveDb)
    /**
     * Capture decision journal: two rotating JSONL files (~2000 records) under
     * `filesDir/archive_journal/`, ids / hashes / decision codes only. Mirrored to logcat
     * (tag `Archive`, debug level) in debuggable and beta builds; wiped with the archive.
     */
    val archiveJournal: dev.lyo.hortay.data.archive.ArchiveJournal =
        dev.lyo.hortay.data.archive.FileArchiveJournal(
            dir = File(context.filesDir, "archive_journal"),
            logcat = if (BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "beta") {
                { line -> android.util.Log.d("Archive", line) }
            } else {
                null
            },
        )
    val archiveRepository: ArchiveRepository =
        ArchiveRepository(
            db = archiveDb,
            settings = archiveSettingsState,
            mediaStore = archivedMediaStore,
            journal = archiveJournal,
            available = { archiveDriver.isAvailable },
            wipeStorage = { dev.lyo.hortay.data.archive.wipeArchiveStorage(context) },
        )

    /**
     * The single ordered writer for TDLib-mode archive captures. Its consumer runs in the
     * session scope; the logout hook stops it and drops queued events before
     * [runLogoutCleanup] wipes the archive (hooks run in registration order).
     */
    val archiveRecorder: dev.lyo.hortay.data.archive.ArchiveRecorder =
        dev.lyo.hortay.data.archive.ArchiveRecorder(
            repo = archiveRepository,
            journal = archiveJournal,
            scope = { session.scope },
            readLocal = { chatId, messageId ->
                suspendRunCatching { tdClient.send(org.drinkless.tdlib.TdApi.GetMessageLocally(chatId, messageId)) }
                    .getOrNull()
            },
            copyMedia = { content ->
                dev.lyo.hortay.data.archive.MediaFileFromContent.extract(content)
                    ?.let { archivedMediaStore.copyIfAvailable(it) }
            },
        ).also { recorder -> session.onEnd("archiveRecorder") { recorder.reset() } }
    val archiveSweep: ArchiveSweep = ArchiveSweep(
        db = archiveDb,
        settings = archiveSettingsState,
        mediaStore = archivedMediaStore,
        writeLock = archiveRepository.writeMutex,
        available = { archiveDriver.isAvailable },
    )

    /** Sharp-media lookup for the edit-history sheet (archive copy, else the same TDLib photo). */
    val archivedMediaResolver: dev.lyo.hortay.data.archive.ArchivedMediaResolver =
        dev.lyo.hortay.data.archive.ArchivedMediaResolver(tdClient, archivedMediaStore)

    val bookmarkStore: BookmarkStore = BookmarkStore(context)

    val postsRepository: PostsRepository = PostsRepository(
        td = tdClient,
        mapper = messageMapper,
        registry = chatRegistry,
        scope = appScope,
        userMessages = userMessages,
        connection = tdClient.connection,
        snapshotStore = timelineSnapshotStore,
        foreground = lifecycleBridge.foreground,
        res = res,
        ignoredChannels = ignoredChannels,
        mutedWords = mutedWords,
        savedKeys = bookmarkStore.bookmarks,
        coldStartBackfill = coldStartBackfillStore,
        archiveRepository = archiveRepository,
        archiveRecorder = archiveRecorder,
    )

    val commentsRepository: CommentsRepository = CommentsRepository(
        td = tdClient,
        mapper = messageMapper,
        scope = appScope,
        res = res,
        archiveRecorder = archiveRecorder,
    )

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
     * Storage-sweep observer. Runs [TdClient.maybeOptimizeStorage] only after
     * [StartupCoordinator.Phase.Active] AND foreground — keeps the daily sweep
     * off the cold-start critical path where it used to race [LoadChats]
     * traffic.
     */
    @Suppress("unused")
    private val storageOptimizer: StorageOptimizer = StorageOptimizer(
        td = tdClient,
        startupPhase = startupCoordinator.phase,
        foreground = lifecycleBridge.foreground,
        scope = appScope,
        archiveSweep = archiveSweep,
    )

    init {
        // One-shot session-start sync. Fires the first time TDLib reaches
        // `Ready` (cold start with existing creds OR fresh login completes),
        // and once more on every re-Ready edge (re-login after logout). Updates
        // are the source of truth, LoadChats is the trigger. See
        // [PostsRepository.triggerInitialSync] for what happens during the drain.
        // collectLatest: leaving Ready (logout) cancels an in-flight sync/backfill
        // instead of letting it finish against the next account's state.
        appScope.launch {
            tdClient.authStage
                .collectLatest { stage ->
                    if (stage !is AuthStage.Ready) return@collectLatest
                    postsRepository.triggerInitialSync()
                    // Backfill runs AFTER the chat-list drain returns so
                    // [PostsRepository._mainChatIds] / the chat registry have a
                    // reasonable snapshot to pick top-K from. Sequential in the
                    // same coroutine — a non-Ready stage cancels it mid-loop.
                    // The persisted flag stays unset on any failure path, so
                    // the next Ready edge retries.
                    postsRepository.runFirstSignInBackfill()
                }
        }
    }

    /**
     * User-configurable per-network auto-download policy (Wi-Fi / mobile / roaming),
     * mirroring Telegram's "Data and Storage" → "Auto-Download Media" UX. Writes
     * route through TDLib's `SetAutoDownloadSettings` so the policy travels with
     * the account; DataStore persists the same values locally as a cold-start
     * cache for the window before [AuthStage.Ready] (TDLib has no per-network
     * GET endpoint). Held by the graph because both the settings UI (read/write)
     * and [autoDownloader] (read-only) need the same instance.
     */
    val autoDownloadStore: AutoDownloadStore = AutoDownloadStore(
        context = context,
        td = tdClient,
        authStage = tdClient.authStage,
        scope = appScope,
    ).also { store -> session.onEnd("autoDownloadStore") { store.clear() } }

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

    init {
        // Scroll-ahead prefetch follows the same per-network policy as live arrivals.
        mediaCache.prefetchPolicy = {
            resolveActivePolicy(lifecycleBridge.networkType.value, autoDownloadStore.settings.value)
        }
    }

    val statsRepository: StatsRepository = StatsRepository(tdClient, userMessages, res)

    /** Channel shared-media grid pages; stateless, fetched only while the grid is open. */
    val channelMedia: ChannelMediaRepository = ChannelMediaRepository(tdClient)

    val chatFoldersRepository: ChatFoldersRepository = ChatFoldersRepository(tdClient, appScope)

    val translations: TranslationsStore =
        TranslationsStore(tdClient, appScope, userMessages, tdClient.connection, res)

    val channelActions: ChannelActionsRepository =
        ChannelActionsRepository(tdClient, userMessages, tdClient.connection, res) { chatId, isMember ->
            // Read lazily at call time — [channelDiscovery] is declared further down.
            channelDiscovery.updateMembership(chatId, isMember)
            recommendedChannels.onMembershipChanged(chatId, isMember)
        }

    /** Callback buttons of posts' inline keyboards; stateless, one query per press. */
    val inlineKeyboards: dev.lyo.hortay.data.posts.InlineKeyboardRepository =
        dev.lyo.hortay.data.posts.InlineKeyboardRepository(tdClient, userMessages, tdClient.connection, res, session)

    /** Static map pictures for location posts; file ids cached per session. */
    val mapThumbnails: dev.lyo.hortay.data.MapThumbnailRepository =
        dev.lyo.hortay.data.MapThumbnailRepository(tdClient, session)

    /**
     * Proxy support (SOCKS5 / HTTP / MTProto). A thin driver over TDLib's native proxy API plus
     * a failover watchdog; see [dev.lyo.hortay.data.proxy.ProxyRepository] KDoc. The pool lives in
     * TDLib's own database — NOT cleared on logout (you may need the proxy to reach the sign-in
     * screen), so it is deliberately absent from [runLogoutCleanup].
     */
    val proxyRepository: dev.lyo.hortay.data.proxy.ProxyRepository =
        dev.lyo.hortay.data.proxy.ProxyRepository(
            sender = tdClient,
            connection = tdClient.connection,
            userMessages = userMessages,
            scope = appScope,
            res = res,
        )

    val countries: CountryRepository = CountryRepository(tdClient, res, appScope)

    // Custom-emoji resolver for inline emojis in formatted text and for custom-emoji
    // reaction buckets. Uses GetCustomEmojiStickers in batches of up to 200 ids; the
    // request stream is debounced 50ms so a screen-full of posts coalesces into a
    // single TDLib call.
    val customEmoji: CustomEmojiRepository = CustomEmojiRepository(tdClient, appScope)

    /**
     * [SettingsStore.emojiStyleState] (pre-warmed by the store): `MainActivity` reads it to
     * decide whether reaction glyphs draw Telegram's artwork, and the resolver below gates
     * its TDLib calls on it. DataStore has long emitted by the time the authenticated UI
     * mounts (it waits for TDLib auth).
     */
    internal val emojiStyleState: StateFlow<EmojiStyle> get() = settingsStore.emojiStyleState

    /**
     * Telegram's static artwork for standard (unicode) reaction emoji, resolved lazily per
     * emoji via `GetEmojiReaction` — see [ReactionEmojiIconRepository]. Session-scoped: its
     * file ids belong to the signed-in account's TDLib database, so it is wiped on logout.
     */
    val reactionEmojiIcons: ReactionEmojiIconRepository =
        ReactionEmojiIconRepository(tdClient, appScope, emojiStyleState).also { repo ->
            session.onEnd("reactionEmojiIcons") { repo.clear() }
        }

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

    init {
        // Parsed TGS compositions are keyed by TDLib local file paths, which belong to the
        // outgoing account's database — drop them (and in-flight parses) on logout.
        session.onEnd("lottieCompositions") { dev.lyo.hortay.ui.media.LottieCompositionStore.clear() }
    }

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
    // Idle pooled players are drained when the app leaves the foreground (and on
    // onTrimMemory, see HortayApp) so their decoders don't outlive the visible session.
    val exoPlayerPool: ExoPlayerPool = ExoPlayerPool(context, httpCallFactory = { webMediaHttpClient }).also { pool ->
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = pool.drainIdle()
        })
    }

    // App-wide single-active-source session for inline audio / voice-note playback, shared
    // by the regular feed's Audio/VoiceNote posts and the rich audio/voice blocks. Owns one
    // pooled audio ExoPlayer that outlives the player rows, so playback survives scrolling;
    // cleared on logout (session-scoped state hard rule).
    val audioPlaybackSession: dev.lyo.hortay.ui.media.AudioPlaybackSession =
        dev.lyo.hortay.ui.media.AudioPlaybackSession(exoPlayerPool, mediaCache).also { audio ->
            // ExoPlayer must be touched on its application (main) thread.
            session.onEnd("audioPlayback") { withContext(Dispatchers.Main) { audio.clear() } }
        }

    // Decoded-frame LRU cache for VP9+alpha WebM animations (stickers, custom emoji).
    // Shares appScope so decode coroutines are cancelled with the process; see the class
    // KDoc for the maxBytes budget. Keyed by TDLib local file ids, which restart per TDLib
    // database, so it is wiped on logout — otherwise account B could be served account A's
    // decoded sticker and the previous session's frames would stay pinned in the budget.
    val webmFrameCache: dev.lyo.hortay.data.media.WebmFrameCache =
        dev.lyo.hortay.data.media.WebmFrameCache(appScope)
            .also { cache -> session.onEnd("webmFrameCache") { cache.clear() } }

    /**
     * Process-wide delivery channel for resolved Telegram deep links. The parser
     * ([linkResolver]) submits typed events here; `MainScaffold` / `WebModeScaffold`
     * collect them and dispatch navigation. Lives on the graph so a single Channel
     * survives configuration changes — re-creating the router per Activity would lose
     * buffered links arriving during the recreation.
     */
    val deepLinkRouter: DeepLinkRouter = DeepLinkRouter()
        .also { router -> session.onEnd("deepLinkRouter") { router.clear() } }

    /**
     * Canonical Telegram-URL parser. Delegates to TDLib's `GetInternalLinkType` (knows
     * ~50 link variants, offline, pre-auth-safe) when the daemon is reachable, falls
     * back to a local regex for the three shapes our UI dispatches. Called from
     * `MainActivity` (cold-launch intents) and the in-app `HortayUriHandler` (link taps
     * inside post bodies, web previews, settings author rows, etc.) — single source of
     * truth for "is this a Telegram URL, and what does it mean".
     */
    val linkResolver: TelegramLinkResolver = TelegramLinkResolver(tdClient)
        .also { resolver -> session.onEnd("linkResolver") { resolver.clear() } }

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
        .also { dialogs -> session.onEnd("linkDialogs") { dialogs.clear() } }

    /**
     * Navigation 3 back stack shared by both scaffolds (authenticated
     * [dev.lyo.hortay.ui.main.MainScaffold] and guest [dev.lyo.hortay.ui.web.WebModeScaffold] —
     * they never compose simultaneously, [dev.lyo.hortay.MainActivity] routes between them).
     * Lives on the graph so deep-link routing (`DeepLinkRouter`) can push keys before the UI
     * scaffold composes, and is intentionally NOT restored across process death — a killed
     * process means the user abandoned the drill path, so cold launch lands on [HomeKey] (Feed
     * top). Root is always [HomeKey]; detail keys push on top. Reset on logout via
     * [runLogoutCleanup]; each scaffold's `entryProvider` carries self-healing no-op entries for
     * the other mode's keys so a foreign key surviving a mode flip can't crash `NavDisplay`.
     */
    val backStack: SnapshotStateList<AppNavKey> = mutableStateListOf(HomeKey)

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

    val webRepository: WebRepository = WebRepository(webDatabase, res)

    /**
     * Sends the app's own HTTPS traffic through the proxy TDLib has enabled — the same pool in
     * guest and signed-in mode. Installed on [webHttpClient], and so on everything derived from
     * it ([webMediaHttpClient]). See [dev.lyo.hortay.data.proxy.WebProxyRouting] KDoc.
     */
    val webProxyRouting: dev.lyo.hortay.data.proxy.WebProxyRouting =
        dev.lyo.hortay.data.proxy.WebProxyRouting(proxyRepository.state, appScope)

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
     * opened to t.me. Carries the user's proxy via [webProxyRouting]; a proxy change
     * evicts the connection pool so no pooled connection keeps the old route.
     */
    val webHttpClient: okhttp3.OkHttpClient =
        WebTelegramClient.defaultHttpClient(File(context.cacheDir, "web-http"), webProxyRouting::install)
            .also { client -> webProxyRouting.onRouteChanged { client.connectionPool.evictAll() } }

    /**
     * [webHttpClient] for URL media: Coil image loads (HortayApp's image loader) and ExoPlayer
     * URL streams ([exoPlayerPool]). `newBuilder()` keeps the connection pool, dispatcher and the
     * proxy routing; it follows redirects like a media client should, skips the OkHttp cache
     * (Coil has its own disk cache, streams aren't cached) and drops the call timeout, which
     * would otherwise cut a video stream after 20 s.
     */
    val webMediaHttpClient: okhttp3.OkHttpClient = webHttpClient.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .cache(null)
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    val webClient: WebTelegramClient = WebTelegramClient(webHttpClient)

    /**
     * Resolves Telegram custom-emoji ids to renderable TGS / WebM / WebP assets via
     * the public `/i/emoji/<id>.json` endpoint. Used by the anonymous-mode UI to
     * surface real custom-emoji reactions and inline-emoji animations — without it
     * web mode would be limited to neutral chips for any emoji-id that has no
     * unicode fallback baked into the static HTML.
     */
    val webCustomEmoji: WebCustomEmojiResolver =
        WebCustomEmojiResolver(webHttpClient, emojiCache = webRepository)

    /**
     * Curated channel-suggestions catalog (sections + handles + blurbs), fetched
     * read-only from GitHub and cached on disk. Powers the "Add channel" surfaces in
     * BOTH modes; the catalog source is shared, only avatar/subscriber hydration
     * differs per mode (t.me/s vs [channelDiscovery]). Reuses [webHttpClient] — same
     * connection pool / disk cache as the rest of the read-only HTTP pipeline.
     */
    val channelSuggestions: ChannelSuggestionsRepository = ChannelSuggestionsRepository(
        http = webHttpClient,
        cacheFile = File(context.cacheDir, "suggestions.json"),
        appVersionCode = runCatching {
            @Suppress("DEPRECATION") // longVersionCode is API 28+; minSdk is 26
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }.getOrDefault(Int.MAX_VALUE),
    )

    /**
     * TDLib-side channel discovery (resolve a curated handle to a live card, search
     * public channels by name) for authenticated mode. Never touches t.me/s — auth
     * mode talks to Telegram only. Resolve cache is session-scoped (`isMember`), so
     * its wipe is registered with [session].
     */
    val channelDiscovery: ChannelDiscoveryRepository =
        ChannelDiscoveryRepository(tdClient) { session.scope }
            .also { discovery -> session.onEnd("channelDiscovery") { discovery.clear() } }

    /**
     * "Recommended by your channels" in the signed-in add-channel sheet: similar-chat
     * votes aggregated over the user's own channels. Scans only on demand, after
     * [startupCoordinator] is Active, in [session]'s scope; the persisted per-source
     * cache is account-bound and wiped with the session.
     */
    val recommendedChannels: dev.lyo.hortay.data.discover.RecommendedChannelsRepository =
        dev.lyo.hortay.data.discover.RecommendedChannelsRepository(
            td = tdClient,
            discovery = channelDiscovery,
            store = dev.lyo.hortay.data.discover.SimilarChatsStoreImpl(context),
            subscribedChannels = { postsRepository.subscribedChannelsByRank() },
            hiddenChannels = { ignoredChannels.ignored.first() },
            startupPhase = startupCoordinator.phase,
            chatListLoaded = postsRepository.initialSyncDone,
            sessionScope = { session.scope },
        ).also { repo -> session.onEnd("recommendedChannels") { repo.clear() } }

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
    /**
     * Counts guest reads as channel views via `t.me/v/`, as the t.me/s/ page does — see
     * [WebViewReporter]. Active only while the "Count my views" setting is on AND the app
     * is in guest mode without a signed-in account; signing in, leaving guest mode or
     * turning the setting off drops everything it holds, unsent. Not account state, so
     * no [session] hook: a TDLib logout can't leave guest tokens behind (there are none
     * while signed in).
     */
    val webViewReporter: WebViewReporter = WebViewReporter(
        scope = appScope,
        transport = webClient::postViews,
        enabled = combine(
            settingsStore.countWebViews,
            guestMode.isGuest,
            tdClient.authStage,
        ) { on, guest, auth -> on && guest && auth != AuthStage.Ready }
            .stateIn(appScope, SharingStarted.Eagerly, initialValue = false),
        isGated = { webClient.isGateActive },
    ).also { it.bind(lifecycleBridge.foreground) }

    val webFeedSource: WebFeedSource = WebFeedSource(
        client = webClient,
        repository = webRepository,
        subscriptions = webSubscriptions,
        scope = appScope,
        ignoredChannels = ignoredChannels,
        mutedWords = mutedWords,
        archiveRepository = archiveRepository,
        viewReporter = webViewReporter,
    )

    /** The guest read-ack: local read cursor + view report. See [WebReadAck]. */
    val webReadAck: WebReadAck = WebReadAck(webRepository, webViewReporter)

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
     * The one throttled SearchPublicChat + JoinChat loop (1 channel/s), shared by the
     * guest → account migration and the channel-list import. See [ThrottledChannelJoiner].
     */
    private val channelJoiner: ThrottledChannelJoiner = ThrottledChannelJoiner(
        channels = channelDirectory,
        channelActions = channelActions,
        floodWaitUntilMs = tdClient.floodWaitUntilMs,
        isAlreadyMember = KnownChannelMembership(
            channelIdOf = webRepository::channelIdFor,
            supergroup = chatRegistry::supergroup,
        )::isMember,
    )

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
        joiner = channelJoiner,
        authStage = tdClient.authStage,
        scope = appScope,
    ).also { it.bind() }

    /**
     * Channel-list import / export for both modes plus the share-to-Hortay target. The
     * signed-in import joins through [channelJoiner] in [session]'s scope; the logout hook
     * closes its sheet so account A's run can't linger over account B.
     */
    val subscriptionTransfer: SubscriptionTransfer = SubscriptionTransfer(
        webSubscriptions = webSubscriptions,
        webFeed = webFeedSource,
        posts = postsRepository,
        channels = channelDirectory,
        joiner = channelJoiner,
        accountScope = { session.scope },
        appScope = appScope,
        userMessages = userMessages,
        res = res,
    ).also { transfer -> session.onEnd("subscriptionTransfer") { transfer.clear() } }

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
        // Registered last, so these run after the hooks registered at construction.
        session.onEnd("graph") { runLogoutCleanup() }
        session.onEnd("minithumbTints") { dev.lyo.hortay.ui.media.MinithumbTints.clear() }
        session.bind(tdClient.loggedOut)
    }

    /**
     * Drop every piece of per-account state held by the graph. Each repo
     * is responsible for its own cleanup; this method is just the
     * orchestrator. Real errors from individual clears are logged (via
     * [warnUnlessCancelled]) but never propagated — a partial wipe is still
     * better than aborting the cleanup midway and leaving half the caches
     * stale. A [kotlinx.coroutines.CancellationException], by contrast, IS
     * propagated (the collecting scope is going away — honour the coroutine
     * contract rather than pinning ourselves alive to finish clears nobody
     * will observe).
     */
    private suspend fun runLogoutCleanup() {
        // PostsRepository.clear() already wipes the on-disk snapshot
        // internally (it owns the snapshotStore reference and the
        // semantic ownership), so we don't double-clear it here.
        // ChatPresence is a process-singleton object — wipe its
        // refcount map so account A's leftover OpenChat counts can't
        // poison account B's open/close arithmetic.
        runCatching { postsRepository.clear() }.warnUnlessCancelled(TAG, "logout cleanup: postsRepository")
        // After the posts repository (which bumps its session epoch and drops queued
        // ingests), so a history load racing the logout can't re-populate the registry
        // with account A's chats behind the wipe. See the [chatRegistry] KDoc.
        runCatching { chatRegistry.clear() }.warnUnlessCancelled(TAG, "logout cleanup: chatRegistry")
        runCatching { dev.lyo.hortay.data.ChatPresence.clear() }.warnUnlessCancelled(TAG, "logout cleanup: chatPresence")
        runCatching { messageMapper.clear() }.warnUnlessCancelled(TAG, "logout cleanup: messageMapper")
        runCatching { commentsRepository.clear() }.warnUnlessCancelled(TAG, "logout cleanup: commentsRepository")
        runCatching { mediaCache.clear() }.warnUnlessCancelled(TAG, "logout cleanup: mediaCache")
        runCatching { customEmoji.clear() }.warnUnlessCancelled(TAG, "logout cleanup: customEmoji")
        // customEmoji.clear() also wiped the stickers the guest-mode bridge had populated, but
        // the bridge's dedup set still marks those ids as requested — without this reset they
        // stay blank in guest mode until restart (guest → sign in → sign out).
        runCatching { webCustomEmojiBridge.reset() }.warnUnlessCancelled(TAG, "logout cleanup: webCustomEmojiBridge")
        // Main thread: clear() recycles bitmaps the UI thread blits and mutates the
        // animator's unsynchronized entry map, which the UI thread reads every frame.
        runCatching {
            withContext(Dispatchers.Main) { customEmojiAnimator.clear() }
        }.warnUnlessCancelled(TAG, "logout cleanup: customEmojiAnimator")
        runCatching { stickerOutline.clear() }.warnUnlessCancelled(TAG, "logout cleanup: stickerOutline")
        runCatching { chatFoldersRepository.clear() }.warnUnlessCancelled(TAG, "logout cleanup: chatFoldersRepository")
        runCatching { translations.clear() }.warnUnlessCancelled(TAG, "logout cleanup: translations")
        runCatching { migrationStore.reset() }.warnUnlessCancelled(TAG, "logout cleanup: migrationStore")
        // Drop the in-memory migration proposal / progress too — reset() only
        // clears the persisted proposal-shown flag; the coordinator's StateFlows
        // still hold account A's guest-mode handles until cleared here.
        runCatching { migrationCoordinator.clearProposal() }.warnUnlessCancelled(TAG, "logout cleanup: migrationCoordinator")
        // Cold-start backfill flag is per-auth-session. Resetting on logout
        // ensures the next sign-in (different Telegram account, or same
        // account after explicit sign-out) re-runs the backfill — the
        // cold-start UX it protects only ever bites without a warm TDLib
        // message DB.
        runCatching { coldStartBackfillStore.reset() }.warnUnlessCancelled(TAG, "logout cleanup: coldStartBackfillStore")
        // Drop any in-flight report sheet — its target's chatId/messageId
        // belongs to account A's TDLib database and will be invalid the moment
        // the new client spawns.
        runCatching { reportDialogs.close() }.warnUnlessCancelled(TAG, "logout cleanup: reportDialogs")
        // Drop any in-flight drill stack — chatId / messageIds belong to
        // account A's TDLib database and will be invalid the moment the new
        // client spawns. Both writes must land inside ONE snapshot: clear()
        // then add() are two separate snapshot mutations, and between them the
        // back stack is momentarily empty — NavDisplay requires a non-empty
        // stack and can crash if it recomposes against the empty frame.
        // On Main, like every other back-stack writer (scaffold pushes/pops, deep-link
        // dispatch): a mutable snapshot applied from this background thread can conflict
        // with a concurrent UI-thread push and fail to apply.
        runCatching {
            withContext(Dispatchers.Main) {
                Snapshot.withMutableSnapshot { backStack.apply { clear(); add(HomeKey) } }
            }
        }.warnUnlessCancelled(TAG, "logout cleanup: backStack")
        // Archive snapshots belong to the previous account's message ids and
        // chat ids. Wipe on logout so account B doesn't see account A's history
        // in the archive screen.
        // The early "archiveRecorder" hook already reset the recorder, but posts/comments
        // repositories are cleared above, after it — an ingest landing in between can
        // re-arm it with account A's events. Reset again right before the wipe.
        runCatching { archiveRecorder.reset() }.warnUnlessCancelled(TAG, "logout cleanup: archiveRecorder")
        runCatching { archiveRepository.clear() }.warnUnlessCancelled(TAG, "logout cleanup: archiveRepository")
        // Per-account preferences in ArchiveSettingsStore (currently: the
        // excluded-chat set, which is keyed on TDLib chatIds). The master
        // `enabled` toggle is a global user preference and survives logout.
        runCatching { archiveSettingsStore.resetForLogout() }.warnUnlessCancelled(TAG, "logout cleanup: archiveSettingsStore")
    }

    private companion object {
        const val TAG = "AppGraph"
    }
}
