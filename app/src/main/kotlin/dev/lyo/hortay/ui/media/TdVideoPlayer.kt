package dev.lyo.hortay.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.MediaState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Plays a TDLib-managed video. Asks [dev.lyo.hortay.data.MediaCache] to download the
 * file (deduplicated and priority-aware) and once it lands on disk, hands the path
 * to a single [ExoPlayer]. While the download is in flight a [MediaIndeterminateIndicator]
 * is shown over a transparent [PlayerView]; underlying composables (typically the
 * blurred poster from [TdMediaImage]) remain visible.
 *
 * Lifecycle:
 *   • Tied to the host's [Lifecycle]: pauses on STOP, releases on DESTROY.
 *   • [autoLoop] = true → silent looping (Telegram "GIF" animations).
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
) {
    val cache = LocalMediaCache.current
    val pool = LocalExoPlayerPool.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coScope = rememberCoroutineScope()
    val isRemote = fileId == 0 && remoteUrl != null

    // Skip starting the download while the host list is mid-scroll — see [LocalScrollGate].
    // Web-mode (isRemote) skips the entire MediaCache pathway: ExoPlayer handles
    // its own buffering against the URL, and there's no fileId to ensure() anyway.
    val gate = LocalScrollGate.current
    val gateOpen = gate.value
    LaunchedEffect(fileId, priority, gateOpen, isRemote) {
        if (gateOpen && !isRemote) cache.ensure(fileId, priority)
    }
    // Mirror the dispose-cancels-download contract from TdMediaImage. Web-mode
    // has no MediaCache slot to release.
    DisposableEffect(fileId, isRemote) {
        onDispose { if (!isRemote) cache.cancelDeferred(fileId) }
    }
    val mediaState by remember(fileId, isRemote) {
        if (isRemote) MutableStateFlow(MediaState.Idle)
        else cache.observe(fileId)
    }.collectAsStateWithLifecycle()
    val showLoadingOverlay = rememberDeferredLoading(state = mediaState, key = fileId) && !isRemote
    // Tracks ExoPlayer's STATE_BUFFERING transitions for the in-playback
    // rebuffer overlay (separate from the pre-Ready download overlay).
    var isBuffering by remember(fileId) { mutableStateOf(false) }

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
    // different quality (different fileId → new MediaState.Ready with a new path).
    // We preserve playback position across the swap so a quality flip resumes
    // mid-frame instead of restarting from zero.
    LaunchedEffect(mediaState, fileId, isRemote, remoteUrl) {
        val uri: String = if (isRemote) {
            remoteUrl ?: return@LaunchedEffect
        } else {
            val ready = mediaState as? MediaState.Ready ?: return@LaunchedEffect
            if (ready.path.isEmpty()) return@LaunchedEffect
            "file://${ready.path}"
        }
        val resumeAt = exoPlayer.currentPosition.coerceAtLeast(0L)
        val wasPlaying = exoPlayer.playWhenReady
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        if (resumeAt > 0L) exoPlayer.seekTo(resumeAt)
        exoPlayer.playWhenReady = wasPlaying
    }

    // Tracks the source video's aspect ratio (width / height); 0f means "not yet
    // known" → AspectRatioFrameLayout falls back to filling the parent. Updated
    // in onVideoSizeChanged once the decoder has read the format.
    var videoAspect by remember(fileId, remoteUrl) { mutableStateOf(0f) }

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
        // Two render paths:
        //   • showControls = true  → PlayerView (we keep its built-in scrubber /
        //     play-pause widgets for the fullscreen viewer). PlayerView wraps a
        //     SurfaceView, which is fine in the fullscreen case because the
        //     player goes opaque-over-poster anyway.
        //   • showControls = false → bare TextureView. The feed-preview path
        //     uses this: a SurfaceView is an opaque hardware overlay that
        //     paints solid black before the first decoded frame arrives, so
        //     the user saw a 1-3 s black square sitting on top of the poster
        //     during ExoPlayer's prepare/buffer window. Variable per video
        //     because preroll latency depends on container init + key-frame
        //     distance, not file size — exactly what the user observed.
        //     TextureView with `isOpaque = false` blends transparently while
        //     the texture is still empty, so the poster (drawn underneath)
        //     reads through cleanly until the first frame paints over it.
        if (showControls) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                update = { view ->
                    if (view.player !== exoPlayer) view.player = exoPlayer
                    view.useController = true
                },
            )
        } else {
            // Wrap the TextureView in AspectRatioFrameLayout so the texture is
            // letterboxed inside the slot at the source video's aspect ratio
            // instead of being stretched to fill. Bare TextureView fills its
            // parent exactly — when the slot was sized from the poster (CSS
            // padding-aspect from t.me) and the actual video resolution
            // doesn't quite match (rounded poster aspect, transcoded source),
            // the video squashes to the slot's box. AspectRatioFrameLayout
            // is the same primitive PlayerView uses internally with its
            // RESIZE_MODE_FIT, lifted out so we keep the transparent
            // TextureView path for the alpha-correct first-frame transition.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    AspectRatioFrameLayout(ctx).apply {
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        addView(TextureView(ctx).apply { isOpaque = false })
                    }
                },
                update = { frame ->
                    val texture = frame.getChildAt(0) as TextureView
                    exoPlayer.setVideoTextureView(texture)
                    if (videoAspect > 0f) frame.setAspectRatio(videoAspect)
                },
                onRelease = { frame ->
                    exoPlayer.clearVideoTextureView(frame.getChildAt(0) as TextureView)
                },
            )
        }
        when (val s = mediaState) {
            is MediaState.Downloading -> if (showLoadingOverlay) Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MediaLoadingOverlay(
                    progress = s.progress,
                    downloadedBytes = s.downloadedBytes,
                    totalBytes = s.totalBytes,
                    onCancel = { cache.cancelExplicit(fileId) },
                )
            }
            is MediaState.Failed -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MediaFailedOverlay(
                    onRetry = { coScope.launch { cache.retry(fileId, priority) } },
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
        // says it ran out of demuxed frames; the download overlay above
        // takes the pre-Ready stalls.
        if (mediaState is MediaState.Ready && isBuffering) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                MediaIndeterminateIndicator()
            }
        }
    }
}
