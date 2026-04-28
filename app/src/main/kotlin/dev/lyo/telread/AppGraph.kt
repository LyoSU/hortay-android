package dev.lyo.telread

import android.content.Context
import dev.lyo.telread.data.MediaCache
import dev.lyo.telread.data.PostsRepository
import dev.lyo.telread.data.TdClient
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

    val postsRepository: PostsRepository = PostsRepository(tdClient, appScope)
}
