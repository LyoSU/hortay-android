package dev.lyo.hortay

import android.content.Context
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.ChatFoldersRepository
import dev.lyo.hortay.data.CommentsRepository
import dev.lyo.hortay.data.CountryRepository
import dev.lyo.hortay.data.MediaCache
import dev.lyo.hortay.data.MessageMapper
import dev.lyo.hortay.data.PostsRepository
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.data.StatsRepository
import dev.lyo.hortay.data.TdClient
import dev.lyo.hortay.data.TdLifecycleBridge
import dev.lyo.hortay.data.TranslationsStore
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

    val tdClient: TdClient = TdClient.create(context).also { it.start() }

    val mediaCache: MediaCache = MediaCache(tdClient, appScope)

    // Shared between PostsRepository (channel feed) and CommentsRepository (discussion
    // threads) so an author resolved in one context is reused in the other — same user
    // appearing as a feed post AND as a thread reply hits the cache twice.
    private val messageMapper: MessageMapper = MessageMapper(tdClient)

    val postsRepository: PostsRepository = PostsRepository(tdClient, messageMapper, appScope)

    val commentsRepository: CommentsRepository = CommentsRepository(tdClient, messageMapper, appScope)

    val bookmarkStore: BookmarkStore = BookmarkStore(context)

    val settingsStore: SettingsStore = SettingsStore(context)

    val statsRepository: StatsRepository = StatsRepository(tdClient)

    val chatFoldersRepository: ChatFoldersRepository = ChatFoldersRepository(tdClient, appScope)

    val translations: TranslationsStore = TranslationsStore(tdClient)

    val channelActions: ChannelActionsRepository = ChannelActionsRepository(tdClient)

    val countries: CountryRepository = CountryRepository(tdClient)

    // Bridge ProcessLifecycleOwner + ConnectivityManager into TDLib so the daemon knows
    // when we're foreground/online and what network it should plan downloads for. Held
    // by the graph to keep the listener alive for the process lifetime.
    private val lifecycleBridge: TdLifecycleBridge =
        TdLifecycleBridge(tdClient, context, appScope).also { it.bind() }
}

