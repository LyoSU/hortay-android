package dev.lyo.hortay

import android.app.Application
import android.content.Context
import android.os.StrictMode
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.lyo.hortay.data.LocaleStore

class HortayApp : Application(), SingletonImageLoader.Factory {

    lateinit var graph: AppGraph
        private set

    // Wrap application context with the user-picked locale on API 26-32. Strings reached
    // through `applicationContext.resources` (TDLib error toasts, Coil error messages,
    // some Compose stringResource calls that resolve via the application config) need
    // this wrap; without it, only the activity-scoped lookups would localise. No-op on
    // API 33+ — see [LocaleStore].
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleStore.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        graph = AppGraph(this)
        // No media service / notification / wake lock backs inline audio, so it must not
        // keep playing once the app leaves the foreground (incl. screen off). Pause only —
        // returning to the app never auto-resumes.
        val audio = graph.audioPlaybackSession
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = audio.onAppBackgrounded()
        })
    }

    // Drop idle pooled ExoPlayers under memory pressure — each pins MediaCodec decoders.
    // Players in use are untouched; the pool refills on the next acquire.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::graph.isInitialized) graph.exoPlayerPool.drainIdle()
    }

    // Catch accidental main-thread disk/network I/O and leaked Closeables/registrations
    // early, while the codebase is clean (no runBlocking; AppGraph init is async). DEBUG
    // only. penaltyLog — NOT penaltyDeath: TDLib JNI and profileinstaller produce a few
    // benign first-launch reads, and killing the process on those would be pure noise.
    // If a specific known-benign violation starts spamming the log, wrap its call site in
    // StrictMode.allowThreadDiskReads() rather than relaxing the policy globally.
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                .build(),
        )
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .crossfade(true)
        // URL images (guest-mode CDN photos, avatars) go through the shared web client so they
        // follow the user's proxy (see WebProxyRouting) instead of Coil's own direct client.
        // A component added here takes precedence over the service-loaded default fetcher.
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.webMediaHttpClient })) }
        .memoryCache {
            // 10% of Java heap leaves headroom for our MutableStateFlow snapshots, the
            // PersistentList feed, TDLib's working set and Compose buffers. 20% defaults
            // were too generous on low-end devices (256 MB heap → 50 MB cache alone).
            MemoryCache.Builder()
                .maxSizePercent(context, percent = 0.10)
                .build()
        }
        .diskCache {
            // 2% of free disk on a typical mid-range phone (32-128 GB) lands in the
            // 50-250 MB range, which fits well alongside TDLib's 500 MB media cap.
            // Clamp to [32 MB, 256 MB] so a 1 TB device doesn't dedicate gigabytes
            // to web-mode thumbs and a 16 GB device doesn't shrink below a usable
            // working set. Hortay's primary image pipeline is TDLib (drawn directly
            // from minithumb byte arrays + on-disk file paths, with diskCachePolicy
            // explicitly DISABLED in TdMediaImage), so Coil's disk cache mainly
            // serves web-mode (`t.me/s/<u>` thumbs) and channel avatars rendered via
            // AsyncImage outside the TDLib path.
            DiskCache.Builder()
                .directory(cacheDir.resolve("coil"))
                .minimumMaxSizeBytes(32L * 1024 * 1024)
                .maxSizePercent(0.02)
                .maximumMaxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .build()
}
