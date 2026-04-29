package dev.lyo.telread

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade

class TelreadApp : Application(), SingletonImageLoader.Factory {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .crossfade(true)
        .memoryCache {
            // 10% of Java heap leaves headroom for our MutableStateFlow snapshots, the
            // PersistentList feed, TDLib's working set and Compose buffers. 20% defaults
            // were too generous on low-end devices (256 MB heap → 50 MB cache alone).
            MemoryCache.Builder()
                .maxSizePercent(context, percent = 0.10)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("coil"))
                .maxSizePercent(0.02)
                .build()
        }
        .build()
}
