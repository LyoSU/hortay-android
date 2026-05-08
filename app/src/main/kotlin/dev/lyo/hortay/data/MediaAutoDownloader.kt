package dev.lyo.hortay.data

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Auto-downloads media for *real-time-arrived* posts only, mirroring how
 * Telegram-Android applies its "Auto-Download Media" policy: the policy
 * scopes to messages that land via [TdApi.UpdateNewMessage] (i.e. arrived
 * while the app was running, or first-shown right after reconnect). Posts
 * pulled in by refresh / pagination / snapshot restore / channel-history
 * back-fill are NOT auto-fetched — those become eligible for download only
 * when the user scrolls them into (or near) the viewport, where
 * [TimelineScreen]'s `prefetchAnchor` LaunchedEffect bumps the soon-visible
 * slots to [DownloadPriority.VisibleMedia].
 *
 * Why this matters in numbers. A heavy user with 200 channels and
 * `videos = on` on Wi-Fi cold-starts into a feed of ~1000 posts. The
 * earlier "subscribe to PostsRepository.posts and prefetch the whole feed"
 * shape blew the cache to multiple GB in one sitting — every video that
 * fit under the size cap got downloaded, regardless of whether the user
 * would ever scroll to it. The new shape downloads zero bytes on cold
 * start (minithumbs are inline in the GetChatHistory payload, so the user
 * still sees blurred previews instantly); the visible cards then download
 * via the per-Composable `ensure(VisibleMedia)`, and a real
 * [TdApi.UpdateNewMessage] arrival is the only thing that fires the
 * speculative path.
 *
 * Posters of video and animation posts ride the photos toggle, not the
 * videos toggle — they are separate TDLib fileIds at photo-size
 * (30-300 KB), and the videos toggle exists to spare metered bandwidth
 * from multi-MB playback files, not to leave video cards visually empty.
 * Same policy Telegram-Android uses.
 *
 * What we deliberately do NOT do:
 *   • Touch the file size for photos. Telegram thumbs are 30-300 KB and
 *     the per-size toggle in TG is just visual parity — even on the
 *     slowest tariff, the photo cap would exclude nothing real.
 *   • Add our own data-saver toggle on top of Android's. We honour the
 *     OS-level Data Saver via
 *     [ConnectivityManager.getRestrictBackgroundStatus]; layering a
 *     second toggle on top creates "I disabled it but it still doesn't
 *     work" confusion.
 *   • Pre-fetch reply-quote thumbnails or avatar pyramids. Those are
 *     wired through [DownloadPriority.Avatar]/[DownloadPriority.VisibleMedia]
 *     when they appear; a speculative pyramid for every post would 5x the
 *     request count for a feature nobody asked for.
 *   • Reissue downloads on settings change. [AutoDownloadSettings] is
 *     read at the moment a new post arrives. A user who flips
 *     "Mobile · Videos" off mid-session stops auto-downloading new
 *     videos but doesn't cancel inflight ones — Telegram does the same,
 *     and cancelling a half-finished download just wastes the bytes
 *     already on the wire.
 */
class MediaAutoDownloader(
    private val store: AutoDownloadStore,
    private val cache: MediaCache,
    private val newArrivalsFlow: SharedFlow<TimelinePost>,
    private val networkType: StateFlow<HortayNetworkType>,
    private val connection: StateFlow<ConnectionStatus>,
    private val foreground: StateFlow<Boolean>,
    context: Context,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)!!

    // Caps the number of `dispatchPost` jobs in flight at once. An album burst
    // can flush ~10 posts into [newArrivalsFlow] in one debounce window, and a
    // multi-channel news minute can land a similar count. 8 permits matches
    // TDLib's typical per-DC slot count; without the gate we'd `scope.launch`
    // a coroutine per arrival and over-saturate the IO dispatcher with
    // launches (TDLib's queue would serialise them anyway, so the launches
    // themselves are pure overhead).
    private val dispatchGate = Semaphore(permits = DISPATCH_CONCURRENCY)

    // Latest settings snapshot, read on every prefetch decision. @Volatile because the
    // collector coroutine writes from one dispatcher and the post observer reads from
    // another — without it the read could see a torn 5-boolean record on architectures
    // where the JIT splits a 64-bit reference across two 32-bit moves (academic on ART
    // for object refs, but the discipline is cheap and removes the doubt).
    @Volatile
    private var current: AutoDownloadSettings = AutoDownloadSettings.DEFAULT

    private var bound = false

    fun bind() {
        if (bound) return
        bound = true

        // Latest user choice — overwrites on every change.
        store.settings
            .onEach { current = it }
            .launchIn(scope)

        // One emission per genuinely-new post (UpdateNewMessage path).
        // [PostsRepository.ingest] already deduplicates against existing
        // feed ids before emitting, so we don't need a local LRU here —
        // a duplicate UpdateNewMessage from a TDLib reconnect echo lands
        // as a no-op upstream.
        newArrivalsFlow
            .onEach { post -> dispatchAsync(post) }
            .launchIn(scope)
    }

    private fun dispatchAsync(post: TimelinePost) {
        // Hold off entirely while TDLib reports the link as down — issuing
        // DownloadFile here would queue a request that goes nowhere AND inflate
        // [MediaCache.tracks] retry counters when the watchdog ticks. TDLib
        // resumes downloads itself on reconnect.
        if (connection.value == ConnectionStatus.WaitingForNetwork) return
        // Battery: pause speculative prefetch while the user isn't looking.
        // Telegram-Android does the same — auto-download runs only when the app
        // is in the foreground. With Hortay positioning itself as a lightweight,
        // battery-conscious reader, kicking off DownloadFile against a phone
        // that's been in the user's pocket for an hour is exactly the
        // anti-pattern. The user's foreground return naturally re-emits the
        // active feed; anything we missed will instead surface via the
        // viewport-driven prefetch in [TimelineScreen] the moment they scroll
        // it into view, which is also what the user expects ("I see what's on
        // screen, that's the budget"). The cost of dropping these arrivals is
        // negligible — they'd have downloaded ~free at the next foreground
        // entry anyway, since the OS holds the cellular radio in low-power
        // state when the screen is off and Doze gates background traffic.
        if (!foreground.value) return
        val policy = activePolicy() ?: return  // network=None → nothing to do
        scope.launch(ioDispatcher) {
            // Bounded concurrency — see [dispatchGate] KDoc.
            dispatchGate.withPermit { dispatchPost(post, policy) }
        }
    }

    /**
     * Resolves the active [AutoDownloadPolicy] from settings + the device's current
     * network. Honours the OS-level Data Saver: when enabled (whitelist mode is
     * "ENABLED" — meaning background data is restricted for non-whitelisted apps,
     * and we are non-whitelisted by default), we treat the connection as if videos
     * were off, regardless of the user's per-network preference. This matches what
     * Telegram and other Android apps do; the OS toggle is a system-wide promise.
     *
     * Returns null when the device has no active network — there's no meaningful
     * policy in that state, and TDLib will queue any DownloadFile until it reconnects
     * anyway, so issuing the call now is wasted work.
     */
    private fun activePolicy(): AutoDownloadPolicy? {
        val raw = when (networkType.value) {
            HortayNetworkType.Wifi -> current.onWifi
            HortayNetworkType.Mobile -> current.onMobile
            HortayNetworkType.Roaming -> current.onRoaming
            HortayNetworkType.None -> return null
        }
        // Data Saver applies only to metered connections (mobile/roaming).
        if (networkType.value != HortayNetworkType.Wifi && isDataSaverActive()) {
            return raw.copy(videos = false, animations = false)
        }
        return raw
    }

    private fun isDataSaverActive(): Boolean = try {
        cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    } catch (_: SecurityException) {
        // Some manufacturers have been known to throw here on edge OS builds. Defaulting
        // to "off" matches the behaviour the user sees on a fresh install — they didn't
        // ask for restriction, and the setting is opt-in.
        false
    }

    private suspend fun dispatchPost(post: TimelinePost, policy: AutoDownloadPolicy) {
        when (val c = post.content) {
            is PostContent.Text -> {
                // Web previews carry a single small image (`og:image`). Treat as a photo
                // for autoload purposes.
                if (policy.photos) c.webPreview?.image?.fileId?.let { cache.ensure(it, DownloadPriority.Prefetch) }
            }
            is PostContent.PhotoAlbum -> dispatchAlbum(c, policy)
            is PostContent.Video -> {
                // Poster (the photo-thumb the feed paints behind the play badge) and
                // playback file are SEPARATE TDLib fileIds. Gating the poster behind
                // [policy.videos] left video posts as a bare minithumb on metered
                // networks. Telegram-Android prefetches the poster regardless of the
                // video toggle, since posters are photo-sized (30-300 KB) and the
                // toggle's intent is "don't burn bytes on multi-MB playback files",
                // not "leave video cards visually empty". Decouple the two: poster
                // rides [policy.photos] (where it semantically belongs), playback
                // rides [policy.videos] with the same size cap as before.
                prefetchPoster(c.media, policy)
                dispatchVideo(
                    fileId = c.playbackFileId,
                    sizeBytes = c.qualities.defaultPick.sizeBytes,
                    policy = policy,
                )
            }
            is PostContent.Animation -> {
                // Same split as Video: poster is photo-sized (the still frame
                // shown before the GIF auto-loops), playback is the actual MP4.
                prefetchPoster(c.media, policy)
                dispatchAnimation(c.playbackFileId, policy)
            }
            is PostContent.Sticker,
            is PostContent.AnimatedEmoji -> {
                // Stickers/animated emoji ride the [CustomEmojiRepository] batched path
                // and aren't user-configurable; skipping here is correct.
            }
            is PostContent.Document,
            is PostContent.Audio,
            is PostContent.VoiceNote,
            is PostContent.VideoNote,
            is PostContent.Poll,
            is PostContent.Location,
            is PostContent.Contact,
            is PostContent.Dice,
            is PostContent.Checklist,
            is PostContent.ExpiredMedia,
            is PostContent.Service,
            is PostContent.Unsupported -> Unit
        }
    }

    private suspend fun dispatchAlbum(album: PostContent.PhotoAlbum, policy: AutoDownloadPolicy) {
        for (item in album.items) when (item) {
            is AlbumItem.Photo -> if (policy.photos) {
                item.media.fileId?.let { cache.ensure(it, DownloadPriority.Prefetch) }
            }
            is AlbumItem.Video -> {
                prefetchPoster(item.media, policy)
                dispatchVideo(
                    fileId = item.playbackFileId,
                    sizeBytes = item.qualities.defaultPick.sizeBytes,
                    policy = policy,
                )
            }
            is AlbumItem.Animation -> {
                prefetchPoster(item.media, policy)
                dispatchAnimation(item.playbackFileId, policy)
            }
        }
    }

    private suspend fun prefetchPoster(media: TdMedia, policy: AutoDownloadPolicy) {
        if (!policy.photos) return
        media.fileId?.let { cache.ensure(it, DownloadPriority.Prefetch) }
    }

    private suspend fun dispatchVideo(fileId: Int, sizeBytes: Long, policy: AutoDownloadPolicy) {
        if (!policy.videos) return
        if (fileId == 0) return
        // Conservative: a video of unknown size is not auto-downloaded. TDLib does
        // sometimes ship `expectedSize` larger than the real final byte count
        // (MediaCache.kt has the canonical note on this), so the inverse —
        // accepting unknown-size — could let a 100 MB clip through on mobile. The
        // miss case (size = 0 because the message arrived without a quality probe)
        // is rare and recoverable: when the user scrolls to the card, the visible
        // ensure() will start the download on demand.
        if (sizeBytes <= 0L) return
        if (sizeBytes > policy.videoMaxBytes) return
        cache.ensure(fileId, DownloadPriority.Prefetch)
    }

    private suspend fun dispatchAnimation(fileId: Int, policy: AutoDownloadPolicy) {
        if (!policy.animations) return
        if (fileId == 0) return
        // Animations don't carry a size estimate at the [PostContent] level and we
        // don't surface a size cap in the UI for them either — Telegram doesn't,
        // and GIFs are typically <5 MB so the cap would be theatre. The toggle is
        // the only knob.
        cache.ensure(fileId, DownloadPriority.Prefetch)
    }

    private companion object {
        const val DISPATCH_CONCURRENCY = 8
    }
}
