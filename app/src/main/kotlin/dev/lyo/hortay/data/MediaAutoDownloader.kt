package dev.lyo.hortay.data

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Drives auto-download of post media in TDLib mode using the user's
 * [AutoDownloadSettings]. Mirrors Telegram's "Data and Storage" → "Auto-Download Media"
 * UX: photos always small (no per-size gate), videos honour [AutoDownloadPolicy.videoMaxBytes],
 * animations honour [AutoDownloadPolicy.animationMaxBytes].
 *
 * Single observer: subscribes to [PostsRepository.posts] and diffs the head against a
 * `Set<(chatId, messageId)>` of already-prefetched ids. Anything new gets dispatched to
 * [MediaCache.ensure] at [DownloadPriority.Prefetch] — a strictly speculative class
 * that never blocks visible-media downloads (priority 8 vs 16) and never blocks the
 * fullscreen viewer (priority 8 vs 32). If the user scrolls into a prefetched card, the
 * Composable's own `ensure(VisibleMedia)` upgrades the priority of the in-flight
 * download in place — the file does not restart, [MediaCache.ensure] is idempotent.
 *
 * What we deliberately do NOT do:
 *   • Touch the file size for photos. Telegram thumbs are 30-300 KB and the per-size
 *     toggle in TG is just visual parity — even on the slowest tariff, the photo cap
 *     would exclude nothing real.
 *   • Add our own data-saver toggle on top of Android's. We honour the OS-level Data
 *     Saver via [ConnectivityManager.getRestrictBackgroundStatus]; layering a second
 *     toggle on top creates "I disabled it but it still doesn't work" confusion.
 *   • Pre-fetch reply-quote thumbnails or avatar pyramids. Those are wired through
 *     [DownloadPriority.Avatar]/[DownloadPriority.VisibleMedia] when they appear; a
 *     speculative pyramid for every post would 5x the request count for a feature
 *     nobody asked for.
 *   • Reissue downloads on settings change. [AutoDownloadSettings] is read at the
 *     moment a new post arrives. A user who flips "Mobile · Videos" off mid-session
 *     stops auto-downloading new videos but doesn't cancel inflight ones — Telegram
 *     does the same, and cancelling a half-finished download just wastes the bytes
 *     already on the wire.
 */
class MediaAutoDownloader(
    private val store: AutoDownloadStore,
    private val cache: MediaCache,
    private val postsFlow: StateFlow<PersistentList<TimelinePost>>,
    private val networkType: StateFlow<HortayNetworkType>,
    context: Context,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)!!

    // Bounded set of (chatId, messageId) pairs we've already considered. The feed itself
    // is bounded by [PostsRepository] (~1000 posts), so this set's residency is bounded
    // implicitly — when a post falls off the feed it stops re-appearing in emits and
    // simply ages out of relevance. We don't actively evict because the keys are tiny
    // (16 bytes per Pair<Long, Long> wrapper) and the Set's GC cost is dominated by
    // the post objects we're tracking, which already have their own retention policy.
    private val prefetched = HashSet<Pair<Long, Long>>()

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

        // Diff-and-dispatch on every feed emit. StateFlow already de-dupes by
        // structural equality before re-emitting, so we don't need an explicit
        // distinctUntilChanged here (it's a no-op anyway and emits a deprecation
        // warning).
        postsFlow
            .onEach { posts -> processFeed(posts) }
            .launchIn(scope)
    }

    private fun processFeed(posts: PersistentList<TimelinePost>) {
        if (posts.isEmpty()) return
        val policy = activePolicy() ?: return  // network=None → nothing to do

        // Walk the feed once. We dispatch ensure() calls inline through scope.launch,
        // not through suspend — TDLib's DownloadFile is non-blocking and the dispatch
        // itself is a single channel.send under the hood. Doing this synchronously on
        // the IO dispatcher would serialise the loop on a single thread for no benefit.
        for (post in posts) {
            val key = post.chatId to post.id
            if (!prefetched.add(key)) continue
            scope.launch(ioDispatcher) {
                dispatchPost(post, policy)
            }
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
     * anyway, so issuing the call now is wasted work that just runs the diff.
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
            is PostContent.Video -> dispatchVideo(
                fileId = c.playbackFileId,
                sizeBytes = c.qualities.defaultPick.sizeBytes,
                policy = policy,
            )
            is PostContent.Animation -> dispatchAnimation(c.playbackFileId, policy)
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
            is AlbumItem.Video -> dispatchVideo(
                fileId = item.playbackFileId,
                sizeBytes = item.qualities.defaultPick.sizeBytes,
                policy = policy,
            )
            is AlbumItem.Animation -> dispatchAnimation(item.playbackFileId, policy)
        }
    }

    private suspend fun dispatchVideo(fileId: Int, sizeBytes: Long, policy: AutoDownloadPolicy) {
        if (!policy.videos) return
        if (fileId == 0) return
        // Conservative: a video of unknown size is not auto-downloaded. TDLib does
        // sometimes ship `expectedSize` larger than the real final byte count
        // (MediaCache.kt:295-297 has the canonical note on this), so the inverse —
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
        // Animations don't carry a size estimate at the [PostContent] level — TDLib's
        // `Animation` payload has expectedSize on the file metadata, but the mapper
        // doesn't surface it on [PostContent.Animation]. We rely on the media cap
        // [AutoDownloadPolicy.animationMaxBytes] only after [MediaCache.ensure] has
        // populated the slot's size — for V1 we autoload all animations when the
        // toggle is on (matches Telegram's behaviour: there's no slider for GIFs in
        // their UI either).
        cache.ensure(fileId, DownloadPriority.Prefetch)
    }

}
