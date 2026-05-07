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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Drives auto-download of post media in TDLib mode using the user's
 * [AutoDownloadSettings]. Mirrors Telegram's "Data and Storage" → "Auto-Download Media"
 * UX: photos always small (no per-size gate), videos honour [AutoDownloadPolicy.videoMaxBytes],
 * animations honour [AutoDownloadPolicy.animationMaxBytes]. **Posters of video and
 * animation posts ride the photos toggle, not the videos toggle** — they are
 * separate TDLib fileIds at photo-size (30-300 KB), and the videos toggle exists
 * to spare metered bandwidth from multi-MB playback files, not to leave video
 * cards visually empty. Same policy Telegram-Android uses.
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
    private val connection: StateFlow<ConnectionStatus>,
    context: Context,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)!!

    // LRU set of (chatId, messageId) pairs we've already considered for prefetch.
    //
    // Bounded for two independent reasons:
    //   1. **Memory.** Posts age out of the feed but we never re-walk old entries
    //      from this set, so an unbounded HashSet grows monotonically — at typical
    //      ~1k new posts/day per heavy subscriber that's tens of MB of permanent
    //      heap after a week. A capped LinkedHashMap evicts the oldest key when
    //      we cross [PREFETCH_LRU_CAPACITY], which is sized at 4× the feed cap so
    //      a re-emit of the current feed never thrashes the cache.
    //   2. **Concurrency.** Both [bind]'s post-flow collector and the settings
    //      collector run on [Dispatchers.Default]'s shared workers. A plain
    //      [HashSet] is not thread-safe under structural rehash, and `add` is the
    //      mutation point. We synchronise through [Collections.synchronizedMap]
    //      on the LinkedHashMap; the `add` site below holds the lock for the
    //      lookup + insert + evict trio so two simultaneous `processFeed` runs
    //      can't tear each other's state.
    private val prefetched: MutableMap<Pair<Long, Long>, Boolean> = java.util.Collections
        .synchronizedMap(object : LinkedHashMap<Pair<Long, Long>, Boolean>(
            PREFETCH_LRU_CAPACITY,
            0.75f,
            /* accessOrder */ false,
        ) {
            override fun removeEldestEntry(eldest: Map.Entry<Pair<Long, Long>, Boolean>): Boolean {
                return size > PREFETCH_LRU_CAPACITY
            }
        })

    // Caps the number of `dispatchPost` jobs in flight at once. On cold start
    // [PostsRepository] hands us hundreds of posts in a single emit; without this
    // gate we'd `scope.launch` one coroutine per post and send 400+ concurrent
    // [TdApi.GetFile] / [TdApi.DownloadFile] requests through TDLib's per-DC
    // queue. TDLib's own scheduler handles the burst, but the shape of "400
    // launches in one tick" makes the IO dispatcher heap-thrash and burns wakeup
    // budget for nothing — we land at the same priority-8 queue position either
    // way. 8 permits matches TDLib's typical per-DC slot count and is small
    // enough to keep the launch-rate sane.
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
        // Hold off entirely while TDLib reports the link as down — issuing
        // DownloadFile here would queue a request that goes nowhere AND inflate
        // [MediaCache.tracks] retry counters when the watchdog ticks. TDLib
        // resumes downloads itself on reconnect, and the *next* posts emit
        // (which fires when the snapshot changes anyway) re-runs this loop with
        // a fresh policy.
        if (connection.value == ConnectionStatus.WaitingForNetwork) return
        val policy = activePolicy() ?: return  // network=None → nothing to do

        for (post in posts) {
            val key = post.chatId to post.id
            // synchronizedMap.put returns the previous value; null = first
            // occurrence of this key, anything else = already considered. Single
            // call covers lookup + insert + LRU eviction atomically.
            if (prefetched.put(key, true) != null) continue
            scope.launch(ioDispatcher) {
                // Bounded concurrency — see [dispatchGate] KDoc.
                dispatchGate.withPermit { dispatchPost(post, policy) }
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
            is PostContent.Video -> {
                // Poster (the photo-thumb the feed paints behind the play badge) and
                // playback file are SEPARATE TDLib fileIds. Gating the poster behind
                // [policy.videos] left video posts as a bare minithumb on metered
                // networks — the user saw a tiny blurred placeholder until they
                // scrolled close enough to trigger viewport-driven prefetch in
                // [TimelineScreen.kt]'s `posterFileIds()` walk. Telegram-Android
                // prefetches the poster regardless of the video toggle, since
                // posters are photo-sized (30-300 KB) and the toggle's intent is
                // "don't burn bytes on multi-MB playback files", not "leave video
                // cards visually empty". Decouple the two: poster rides the
                // [policy.photos] toggle (where it semantically belongs), playback
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
                // Mirrors [PostContent.Video] handling: poster on the photos
                // toggle, playback on the videos toggle. Without the split, an
                // album of 5 videos with `policy.videos=false` painted 5
                // minithumbs in a row — significantly worse than the photo
                // album it visually emulates.
                prefetchPoster(item.media, policy)
                dispatchVideo(
                    fileId = item.playbackFileId,
                    sizeBytes = item.qualities.defaultPick.sizeBytes,
                    policy = policy,
                )
            }
            is AlbumItem.Animation -> {
                // Mirrors [PostContent.Animation] — see rationale above.
                prefetchPoster(item.media, policy)
                dispatchAnimation(item.playbackFileId, policy)
            }
        }
    }

    /**
     * Prefetch a media item's photo-poster fileId at [DownloadPriority.Prefetch],
     * gated on [AutoDownloadPolicy.photos]. Used by Video / Animation / album
     * Video / album Animation paths so the poster doesn't ride the playback
     * toggle. No-op if [TdMedia.fileId] is null — TDLib reports null when no
     * server-side photo accompanied the media (rare; e.g. a forwarded GIF
     * that lost its thumb).
     */
    private suspend fun prefetchPoster(media: TdMedia, policy: AutoDownloadPolicy) {
        if (!policy.photos) return
        media.fileId?.let { cache.ensure(it, DownloadPriority.Prefetch) }
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
        // Animations don't carry a size estimate at the [PostContent] level and we
        // don't surface a size cap in the UI for them either — Telegram doesn't,
        // and GIFs are typically <5 MB so the cap would be theatre. The toggle is
        // the only knob.
        cache.ensure(fileId, DownloadPriority.Prefetch)
    }

    private companion object {
        // 4× PostsRepository's typical feed cap so a re-emit of the visible feed
        // never thrashes the LRU; hitting eviction means the user has seen this
        // many distinct posts during the session, at which point dropping the
        // oldest is acceptable (the worst that happens is a repeat ensure() call
        // for a file already on disk — TDLib's DownloadFile is idempotent).
        const val PREFETCH_LRU_CAPACITY = 4096
        const val DISPATCH_CONCURRENCY = 8
    }
}
