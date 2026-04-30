package dev.lyo.hortay

import android.content.Context
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.ChatFoldersRepository
import dev.lyo.hortay.data.CommentsRepository
import dev.lyo.hortay.data.CountryRepository
import dev.lyo.hortay.data.CustomEmojiRepository
import dev.lyo.hortay.data.MediaCache
import dev.lyo.hortay.data.MessageMapper
import dev.lyo.hortay.data.PostsRepository
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.data.StatsRepository
import dev.lyo.hortay.data.TdClient
import dev.lyo.hortay.data.TdLifecycleBridge
import dev.lyo.hortay.data.TranslationsStore
import dev.lyo.hortay.ui.media.ExoPlayerPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency graph for the app. Lightweight alternative to Hilt for a single-process
 * app with a small object set; easy to grow into a multi-module setup later.
 *
 * Lifetime: created once in [HortayApp.onCreate] and held for the entire process. All
 * coroutines launched here use [appScope], which is cancelled only on process death.
 */
class AppGraph(context: Context) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Declared before [tdClient] because TdClient consumes it for OptimizeStorage
    // throttling (the cleanup needs a persisted "last run at" timestamp to skip on
    // every cold start).
    val settingsStore: SettingsStore = SettingsStore(context)

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
        MediaCache(tdClient, appScope, tdClient.connection, lifecycleBridge.foreground)

    // Shared between PostsRepository (channel feed) and CommentsRepository (discussion
    // threads) so an author resolved in one context is reused in the other — same user
    // appearing as a feed post AND as a thread reply hits the cache twice.
    private val messageMapper: MessageMapper = MessageMapper(tdClient)

    val postsRepository: PostsRepository = PostsRepository(tdClient, messageMapper, appScope)

    val commentsRepository: CommentsRepository = CommentsRepository(tdClient, messageMapper, appScope)

    val bookmarkStore: BookmarkStore = BookmarkStore(context)

    val statsRepository: StatsRepository = StatsRepository(tdClient)

    val chatFoldersRepository: ChatFoldersRepository = ChatFoldersRepository(tdClient, appScope)

    val translations: TranslationsStore = TranslationsStore(tdClient)

    val channelActions: ChannelActionsRepository = ChannelActionsRepository(tdClient)

    val countries: CountryRepository = CountryRepository(tdClient)

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
}

