package dev.lyo.hortay.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.MediaState
import kotlinx.coroutines.launch

/**
 * Plays a TDLib-managed video. Asks [dev.lyo.hortay.data.MediaCache] to download the
 * file (deduplicated and priority-aware) and once it lands on disk, hands the path
 * to a single [ExoPlayer]. While the download is in flight a [MediaIndeterminateIndicator]
 * is shown over a transparent surface; underlying composables (typically the
 * blurred poster from [TdMediaImage]) remain visible.
 *
 * Lifecycle:
 *   • Tied to the host's [Lifecycle]: pauses on STOP, releases on DESTROY.
 *   • [autoLoop] = true → silent looping (Telegram "GIF" animations).
 *
 * Render path: bare [TextureView] inside [AspectRatioFrameLayout] for every call
 * site, fullscreen and feed-preview alike. An earlier iteration used media3's
 * `PlayerView` (with `useController=true`) for the fullscreen path to get its
 * built-in scrubber widgets; that surface ships stock 2010s system styling that
 * clashed with the rest of the app's M3 Expressive vocabulary (polygon shapes,
 * wavy progress, motion-token transitions). Replaced by [VideoPlayerControls],
 * a Compose chrome painted over the same TextureView. Two render-path benefits
 * fall out for free:
 *   • The transparent `TextureView` continues to read through to the underlying
 *     poster while ExoPlayer prepares (kills the 2-3 s black-square preroll a
 *     `SurfaceView`-backed `PlayerView` produced).
 *   • Quality / mute / playback / seek state is now a pure Compose concern,
 *     so the chrome composes through the same `@Immutable` stability chain as
 *     the rest of the UI instead of hiding mutation behind an `AndroidView`.
 *
 * Quality switch: the caller changes [fileId]. [MediaCache] is asked to download
 * the new file; once it's Ready, ExoPlayer's MediaItem is swapped while preserving
 * the current playback position so the user resumes mid-frame instead of restarting.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun TdVideoPlayer(
    fileId: Int,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
    autoLoop: Boolean = false,
    showControls: Boolean = true,
    muted: Boolean = false,
    priority: DownloadPriority = DownloadPriority.VisibleMedia,
    /**
     * Remote-URL fallback used by web (anonymous) mode. When [fileId] is 0
     * (placeholder for "no TDLib file") and [remoteUrl] is set, ExoPlayer
     * streams the URL directly via its built-in HTTP DataSource — bypassing
     * the [MediaCache] download orchestration that has nothing to do here.
     * Lets PostBody's video block render guest-mode videos through the same
     * Composable that TDLib mode uses.
     */
    remoteUrl: String? = null,
    /**
     * Pre-seed for [AspectRatioFrameLayout.setAspectRatio]. 0f means "unknown —
     * fill parent until the decoder reports the real size". A non-zero value
     * (Telegram's poster width / height, propagated from [AlbumItem.media])
     * letterboxes the texture correctly on first layout, eliminating the
     * one-frame "stretch then snap" the fullscreen viewer otherwise showed
     * between mount and `onVideoSizeChanged`. Once ExoPlayer reports the real
     * aspect (typically identical to the poster) we adopt that authoritatively;
     * a mismatched seed only costs one re-layout, not a re-decode.
     */
    initialAspect: Float = 0f,
    /**
     * Fired once per decoded source when ExoPlayer renders its first frame to the
     * texture ([Player.Listener.onRenderedFirstFrame]). The fullscreen viewer keeps
     * the item's poster composited under the (transparent) TextureView and uses this
     * signal to crossfade it out — without it the poster either flashed to black
     * before decode or had to stay composited for the page's whole lifetime.
     * Null = no observer, zero overhead.
     */
    onFirstFrameRendered: (() -> Unit)? = null,
) {
    val pool = LocalExoPlayerPool.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coScope = rememberCoroutineScope()
    val isRemote = fileId == 0 && remoteUrl != null

    // Centralised observe / ensure / cancelDeferred — see [rememberMediaBinding].
    // Web-mode (isRemote) makes the binding a no-op shape: ExoPlayer streams
    // direct from [remoteUrl] via its built-in HTTP DataSource and the
    // download orchestration has nothing to do here. [fileId] of `0` is the
    // historical "no TDLib file" sentinel for this composable; pass null to
    // the binding so it stays in [MediaState.Idle] regardless of whether
    // [isRemote] is true or false.
    val binding = rememberMediaBinding(
        fileId = if (fileId == 0) null else fileId,
        priority = priority,
        isRemote = isRemote,
    )
    val mediaState = binding.state
    val showLoadingOverlay = rememberDeferredLoading(state = mediaState, key = fileId) && !isRemote
    // Tracks ExoPlayer's STATE_BUFFERING transitions for the in-playback
    // rebuffer overlay (separate from the pre-Ready download overlay).
    //
    // ExoPlayer flips into STATE_BUFFERING for many sub-second reasons that
    // are NOT user-visible "the video froze" events: source switch on quality
    // change (~50-200 ms), seek (~100-300 ms), normal chunk-boundary refills
    // on tight buffers (~50-150 ms). Painting the indicator immediately on
    // every such blip produced a visible flash of the disc-and-spinner during
    // healthy playback — same UX bug the pre-Ready download path solved with
    // [rememberDeferredLoading]. Reusing the same primitive here so a true
    // network-rebuffer (>400 ms of starved decoder) gets feedback while every
    // blip-and-recover stays invisible. 400 ms < the 600 ms used for the
    // download path because the user is mid-watch (more attentive); a longer
    // wait while the video is frozen reads worse than the same wait staring
    // at a thumbnail.
    var isBuffering by remember(fileId) { mutableStateOf(false) }
    val showRebufferOverlay = rememberDeferredLoading(
        pending = isBuffering,
        key = fileId,
        graceMs = REBUFFER_OVERLAY_GRACE_MS,
    )

    // Acquire from the shared pool. Pooled instances arrive in IDLE state with empty
    // playlist (see ExoPlayerPool.release); the apply-block here re-applies the
    // per-call attributes that the pool reset on the previous release. Mute regime
    // is fixed at acquire time — muted players are built without an audio renderer
    // entirely (no AudioTrack, no AudioMix wakelock).
    val exoPlayer = remember {
        pool.acquire(muted = muted).apply {
            playWhenReady = autoPlay
            repeatMode = if (autoLoop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = if (muted) 0f else 1f
        }
    }

    // React to autoPlay changes after acquisition — critical inside a HorizontalPager,
    // where neighbour pages stay composed past the active one (offscreenPageLimit ≥ 1).
    // Without this, a video that started playing on its active page keeps running
    // off-screen after the swipe (audio bleed-through, kept-alive MediaCodec, kept-alive
    // wakelock for non-muted players); a precomposed neighbour acquired with autoPlay=
    // false stays paused when it becomes the active page. Passing it through a
    // LaunchedEffect keyed on `autoPlay` flips playWhenReady on each transition.
    LaunchedEffect(autoPlay) { exoPlayer.playWhenReady = autoPlay }

    // Swap the source when the file becomes Ready, or when the caller picks a
    // different quality (different fileId → new readyPath). We preserve playback position
    // across the swap so a quality flip resumes mid-frame instead of restarting from zero.
    //
    // Keyed on [binding.readyPath] (the actual usable transition) rather than the whole
    // [mediaState] object — matching WebM/Lottie/InlineEmoji. MediaState.Downloading emits
    // a fresh data-class instance per throttled progress tick; keying on the whole object
    // re-launched this effect on every tick (harmless — it early-returned — but churny).
    val readyPath = binding.readyPath
    LaunchedEffect(readyPath, fileId, isRemote, remoteUrl) {
        val uri: String = if (isRemote) {
            // K2 smart-casts remoteUrl to String via the isRemote val above
            // (`fileId == 0 && remoteUrl != null`).
            remoteUrl
        } else {
            // readyPath is null for both "not Ready" and "Ready but path empty" (TDLib's
            // transient post-completion-rename snapshot) — the single null-check covers both.
            val path = readyPath ?: return@LaunchedEffect
            "file://$path"
        }
        val resumeAt = exoPlayer.currentPosition.coerceAtLeast(0L)
        val wasPlaying = exoPlayer.playWhenReady
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        if (resumeAt > 0L) exoPlayer.seekTo(resumeAt)
        exoPlayer.playWhenReady = wasPlaying
    }

    // Tracks the source video's aspect ratio (width / height). Seeded from the
    // caller's [initialAspect] (poster geometry) so the first layout pass already
    // letterboxes correctly; without the seed the TextureView would fill the
    // parent on mount and visibly snap to the right aspect only when
    // [onVideoSizeChanged] fires. Updated in onVideoSizeChanged once the decoder
    // has read the format — if the decoder disagrees with the poster (rare —
    // Telegram's poster is a downscaled real frame, identical aspect) the
    // texture re-letterboxes; cheaper than the visible stretch.
    var videoAspect by remember(fileId, remoteUrl) { mutableStateOf(initialAspect) }

    // Latest [onFirstFrameRendered] for the listener below — the DisposableEffect is
    // keyed on [exoPlayer] only, so a recomposition with a new lambda must not leave
    // the listener holding the stale one.
    val firstFrameCallback = rememberUpdatedState(onFirstFrameRendered)

    DisposableEffect(exoPlayer) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> if (autoPlay) exoPlayer.play()
                else -> Unit
            }
        }
        val playerListener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) {
                    val pixelRatio = if (videoSize.pixelWidthHeightRatio > 0) {
                        videoSize.pixelWidthHeightRatio
                    } else {
                        1f
                    }
                    videoAspect = (videoSize.width * pixelRatio) / videoSize.height
                }
            }

            // Track ExoPlayer's playback state so the M3 Expressive buffering
            // overlay below can paint when the player is mid-rebuffer (download
            // already finished, but the decoder ran out of demuxed frames). The
            // download-progress overlay handles the pre-Ready window; this
            // listener handles every stall AFTER the file is local — common on
            // long videos where ExoPlayer's buffer dries out faster than the
            // disk can refill it.
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
            }

            override fun onRenderedFirstFrame() {
                firstFrameCallback.value?.invoke()
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        exoPlayer.addListener(playerListener)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            exoPlayer.removeListener(playerListener)
            // Hand the player back to the pool instead of releasing — saves the
            // MediaCodec/decoder allocation cost on the next viewport entry.
            pool.release(exoPlayer, muted = muted)
        }
    }

    Box(modifier = modifier) {
        // Single render path: bare TextureView inside AspectRatioFrameLayout.
        // A SurfaceView (what the old `PlayerView` branch wrapped) is an opaque
        // hardware overlay that paints solid black before the first decoded
        // frame lands — produced a 1-3 s black-square preroll on top of the
        // blurred poster while ExoPlayer prepared. TextureView with
        // `isOpaque = false` blends transparently until the texture is
        // populated, so the poster underneath reads through cleanly during
        // the prepare/buffer window. AspectRatioFrameLayout letterboxes the
        // texture at the source video's aspect ratio (the same primitive the
        // old PlayerView used internally with RESIZE_MODE_FIT) — without it,
        // a poster-sized slot stretches the video when the actual resolution
        // doesn't quite match the rounded CSS aspect.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                // [exoPlayer] is `remember`'d once for this TdVideoPlayer
                // instance (see acquire block above), so the texture bind
                // is a one-time setup — repeating it from `update` on
                // every recomposition (parent emit, centred-flip, mute
                // toggle) thrashed `setVideoTextureView` even though the
                // texture identity never changed. The aspect-ratio update
                // legitimately depends on the live [videoAspect] state
                // and stays in [update].
                val texture = TextureView(ctx).apply { isOpaque = false }
                exoPlayer.setVideoTextureView(texture)
                AspectRatioFrameLayout(ctx).apply {
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    addView(texture)
                }
            },
            update = { frame ->
                if (videoAspect > 0f) frame.setAspectRatio(videoAspect)
            },
            onRelease = { frame ->
                exoPlayer.clearVideoTextureView(frame.getChildAt(0) as TextureView)
            },
        )
        when (val s = mediaState) {
            is MediaState.Downloading -> if (showLoadingOverlay) Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MediaLoadingOverlay(
                    progress = s.progress,
                    downloadedBytes = s.downloadedBytes,
                    totalBytes = s.totalBytes,
                    onCancel = { binding.cancelExplicit() },
                )
            }
            is MediaState.Failed -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MediaFailedOverlay(
                    onRetry = { coScope.launch { binding.retry(priority) } },
                )
            }
            MediaState.Idle -> if (showLoadingOverlay) Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MediaIndeterminateIndicator()
            }
            is MediaState.Ready -> Unit
        }
        // Mid-playback rebuffer overlay. Uses the same M3 Expressive
        // LoadingIndicator (polygon cycle) as the pre-download path, so a
        // stall during a watched video reads identically to a stall before
        // the file lands — single visual idiom for "busy". Only paints when
        // the file is actually local (mediaState is Ready) and the player
        // has been starved past [REBUFFER_OVERLAY_GRACE_MS]; the download
        // overlay above takes the pre-Ready stalls. Sub-grace blips
        // (seeks / source switches / brief chunk refills) never reach
        // showRebufferOverlay so the disc doesn't flash on healthy playback.
        if (mediaState is MediaState.Ready && showRebufferOverlay) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MediaIndeterminateIndicator()
            }
        }
        // Custom Compose chrome: 72 dp polygon-morph play/pause, M3 Slider
        // seek bar, mute toggle, auto-hide, double-tap ±10s seek. Replaces
        // the stock media3 PlayerView controller surface. TDLib mode mounts
        // the chrome only after the file is local (pre-Ready the user sees
        // the download affordance via MediaLoadingOverlay above and playback
        // chrome would be in the way). Web mode (isRemote) skips the
        // MediaCache pathway entirely — ExoPlayer streams from the URL and
        // owns its own ready-state — so the chrome mounts as soon as the
        // composable enters: any pre-roll buffering is handled via the
        // player's STATE_BUFFERING listener inside [VideoPlayerControls].
        if (showControls && (isRemote || mediaState is MediaState.Ready)) {
            VideoPlayerControls(
                player = exoPlayer,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// 400 ms grace window before the mid-playback rebuffer overlay paints. Catches
// real "video froze" events (>400 ms of starved decoder, typical on cellular
// hand-off or tight buffer drain on big videos) while hiding sub-second blips
// (seek, source switch, chunk-boundary refill) that mean nothing to the user.
// Tighter than the 600 ms used pre-Ready: the user is mid-watch and more
// attentive — a longer freeze with no feedback reads worse than the same
// wait while staring at a thumbnail.
private const val REBUFFER_OVERLAY_GRACE_MS = 400L
