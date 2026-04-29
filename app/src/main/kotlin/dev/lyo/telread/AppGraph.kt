package dev.lyo.telread

import android.content.Context
import dev.lyo.telread.data.BookmarkStore
import dev.lyo.telread.data.ChannelActionsRepository
import dev.lyo.telread.data.ChatFoldersRepository
import dev.lyo.telread.data.CommentsRepository
import dev.lyo.telread.data.MediaCache
import dev.lyo.telread.data.MessageMapper
import dev.lyo.telread.data.PostsRepository
import dev.lyo.telread.data.SettingsStore
import dev.lyo.telread.data.StatsRepository
import dev.lyo.telread.data.TdClient
import dev.lyo.telread.data.TdLifecycleBridge
import dev.lyo.telread.data.TranslationsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency graph for the app. Lightweight alternative to Hilt for a single-process
 * app with a small object set; easy to grow into a multi-module setup later.
 *
 * Lifetime: created once in [TelreadApp.onCreate] and held for the entire process. All
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

    // Bridge ProcessLifecycleOwner + ConnectivityManager into TDLib so the daemon knows
    // when we're foreground/online and what network it should plan downloads for. Held
    // by the graph to keep the listener alive for the process lifetime.
    private val lifecycleBridge: TdLifecycleBridge =
        TdLifecycleBridge(tdClient, context, appScope).also { it.bind() }
}

