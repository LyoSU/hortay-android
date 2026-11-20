package dev.lyo.hortay.ui.timeline

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.compose.LottieAnimatable
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.rememberLottieAnimatable
import dev.lyo.hortay.data.DownloadPriority
import dev.lyo.hortay.data.MediaCache
import dev.lyo.hortay.data.MediaState
import dev.lyo.hortay.data.ReactionEmojiIcon
import dev.lyo.hortay.data.ReactionKind
import dev.lyo.hortay.data.stableKey
import dev.lyo.hortay.data.TdMedia
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.media.LocalReactionEmojiIcons
import dev.lyo.hortay.ui.media.LottieCompositionStore
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.media.rememberMediaBinding
import dev.lyo.hortay.ui.media.rememberRevealAlpha
import dev.lyo.hortay.ui.util.rememberReducedMotion
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * A standard (unicode) reaction emoji, drawn with Telegram's own static reaction artwork
 * when [LocalReactionEmojiIcons] provides a resolver, else with the device's emoji font.
 *
 * No layout jump, ever: the box is always measured by the text emoji, which stays
 * composed in every state (just faded out once the artwork is on disk), and the artwork
 * draws inside that same box with [Modifier.matchParentSize]. So the glyph's size is
 * identical while loading, after the swap, on a failed lookup, in guest mode and under
 * either emoji-style setting. The text also stays the screen-reader label.
 *
 * Fallback to the text emoji while the artwork resolves or downloads, when TDLib has no
 * artwork for it (the resolver caches the miss), and whenever the local is null (guest
 * mode, [dev.lyo.hortay.data.EmojiStyle.System]) — in that case no TDLib call is made.
 *
 * Telegram's one-shot animations (TGS, played through the app's Lottie pipeline):
 *   - **Choose effect.** Each increment of [chooseEffectTrigger] above 0 plays the
 *     reaction's `centerAnimation` once in place of the static icon, and its
 *     `aroundAnimation` (the burst) once, centred on the glyph at [AROUND_SCALE]× its size.
 *     The burst is laid out at the glyph's own size and only DRAWS larger, so nothing is
 *     re-measured — the chip, its row and the post keep their size. The trigger starts at
 *     0 in every fresh composition (the caller owns it in `remember`), so a recycled
 *     LazyColumn item or a re-entering chip never replays an old effect.
 *   - **Appear.** With [playAppear], the `appearAnimation` plays once as the glyph enters
 *     composition (the long-press picker strip opening), then rests on the static icon.
 *
 * Neither plays under reduced motion, without the Telegram artwork (guest mode, System
 * style, failed lookup), or before the static icon is on screen — the centre animation's
 * last frame hands back to the static icon, so it needs the icon to hand back to. The
 * choose-effect files (two small TGS per emoji, kept in TDLib's file cache afterwards) are
 * fetched at [DownloadPriority.Avatar] as soon as a glyph shows its static icon, so they
 * are on disk before the first tap; the appear file only when the picker opens. A play
 * waits for its files and for the static icon up to a budget ([CHOOSE_START_BUDGET_MS] /
 * [APPEAR_START_BUDGET_MS]) and is skipped past it, while the download finishes for the
 * next one. The wait matters for a chip that is NEW with this very pick — the first
 * reaction on a post — whose icon binds a frame or two after the effect is asked for.
 * Nothing here gates the tap or the optimistic reaction flip, which run before any of it.
 */
@Composable
internal fun ReactionEmojiGlyph(
    emoji: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    chooseEffectTrigger: Int = 0,
    playAppear: Boolean = false,
) {
    val resolver = LocalReactionEmojiIcons.current
    val icon: ReactionEmojiIcon? = if (resolver != null) {
        // Lazy, per composed glyph — the resolver dedupes, so N chips of one emoji cost
        // one TDLib call per session at most.
        LaunchedEffect(resolver, emoji) { resolver.request(emoji) }
        val store = resolver.icons.collectAsStateWithLifecycle()
        val resolved = remember(resolver, emoji) { derivedStateOf { store.value[emoji] } }
        resolved.value
    } else {
        null
    }
    val fileReady = if (icon != null) {
        rememberMediaBinding(fileId = icon.media.fileId, priority = DownloadPriority.Avatar).isReady
    } else {
        false
    }
    // Crossfade text → artwork; snaps under reduced motion, and starts at 1 when the
    // artwork is already on disk at first composition (no fade on scroll-back).
    val artworkAlpha = rememberRevealAlpha(revealed = fileReady)

    val reducedMotion = rememberReducedMotion()
    val canAnimate = icon != null && fileReady && !reducedMotion
    // Re-checked right before a play starts: the files may land after the icon state
    // changed (style switched to System, reduced motion turned on).
    val canAnimateNow by rememberUpdatedState(canAnimate)
    val iconNow by rememberUpdatedState(icon)
    val cache = LocalMediaCache.current
    // Warm the choose effect while the reaction is merely on screen: fetched on the tap
    // instead, the files missed the start budget and the first pick played nothing.
    LaunchedEffect(icon, canAnimate) {
        if (!canAnimate) return@LaunchedEffect
        cache.prefetchReactionAnimation(icon?.centerAnimation)
        cache.prefetchReactionAnimation(icon?.aroundAnimation)
    }
    // Compositions currently playing; null = at rest on the static icon. Set only while
    // a one-shot runs, so the card never recomposes per frame — Lottie reads the
    // animatable's progress in its draw phase.
    var inner by remember(emoji) { mutableStateOf<LottieComposition?>(null) }
    var around by remember(emoji) { mutableStateOf<LottieComposition?>(null) }
    // The centre animation keeps its glyph in the middle ~half of its canvas (room for the
    // motion), the appear animation fills it — so each is drawn at its own scale to land
    // on the static icon's size.
    var innerScale by remember(emoji) { mutableFloatStateOf(1f) }
    val innerProgress = rememberLottieAnimatable()
    val aroundProgress = rememberLottieAnimatable()

    LaunchedEffect(chooseEffectTrigger) {
        if (chooseEffectTrigger == 0 || reducedMotion) return@LaunchedEffect
        val startedAt = SystemClock.uptimeMillis()
        // A chip created by this pick has no resolved icon on its first frames.
        withTimeoutOrNull(CHOOSE_START_BUDGET_MS) { snapshotFlow { canAnimateNow }.first { it } }
            ?: return@LaunchedEffect
        val target = iconNow ?: return@LaunchedEffect
        val centerMedia = target.centerAnimation
        val aroundMedia = target.aroundAnimation
        if (centerMedia == null && aroundMedia == null) return@LaunchedEffect
        // Kick both downloads first and outside the budget, so a skipped effect still
        // leaves the files on disk for the next tap.
        cache.prefetchReactionAnimation(centerMedia)
        cache.prefetchReactionAnimation(aroundMedia)
        val budget = CHOOSE_START_BUDGET_MS - (SystemClock.uptimeMillis() - startedAt)
        val (centerComp, aroundComp) = coroutineScope {
            val c = async { centerMedia?.let { cache.loadReactionAnimation(it, budget) } }
            val a = async { aroundMedia?.let { cache.loadReactionAnimation(it, budget) } }
            c.await() to a.await()
        }
        if (!canAnimateNow) return@LaunchedEffect
        coroutineScope {
            if (centerComp != null) {
                launch {
                    innerScale = CENTER_SCALE
                    playOnce(centerComp, innerProgress) { inner = it }
                }
            }
            if (aroundComp != null) launch { playOnce(aroundComp, aroundProgress) { around = it } }
        }
    }

    val appearMedia = icon?.appearAnimation
    val composedAt = remember(emoji) { SystemClock.uptimeMillis() }
    // Keyed on the media too: the strip may open before this emoji's artwork resolves,
    // and it still plays if the file shows up inside the budget counted from opening.
    LaunchedEffect(playAppear, appearMedia) {
        if (!playAppear || appearMedia == null || reducedMotion) return@LaunchedEffect
        cache.prefetchReactionAnimation(appearMedia)
        val budget = APPEAR_START_BUDGET_MS - (SystemClock.uptimeMillis() - composedAt)
        val comp = cache.loadReactionAnimation(appearMedia, budget) ?: return@LaunchedEffect
        if (!canAnimateNow) return@LaunchedEffect
        innerScale = 1f
        playOnce(comp, innerProgress) { inner = it }
    }

    val innerPlaying = inner != null
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = emoji.withEmojiPresentation(),
            style = style,
            modifier = Modifier.graphicsLayer { alpha = if (innerPlaying) 0f else 1f - artworkAlpha },
        )
        if (icon != null) {
            TdMediaImage(
                media = icon.media,
                contentDescription = null,
                modifier = Modifier
                    .matchParentSize()
                    // The playing animation stands in for the static icon; its last frame
                    // is Telegram's resting pose, so the swap back is seamless.
                    .graphicsLayer { alpha = if (innerPlaying) 0f else artworkAlpha },
                contentScale = ContentScale.Fit,
                placeholderColor = null,
                showProgress = false,
                priority = DownloadPriority.Avatar,
            )
        }
        inner?.let { comp ->
            LottieAnimation(
                composition = comp,
                progress = { innerProgress.progress },
                modifier = Modifier.matchParentSize().drawOversized(innerScale),
            )
        }
        around?.let { comp ->
            LottieAnimation(
                composition = comp,
                progress = { aroundProgress.progress },
                modifier = Modifier
                    .matchParentSize()
                    .drawOversized(AROUND_SCALE),
            )
        }
    }
}

/**
 * Plays [composition] once on [animatable], publishing it through [show] while it runs
 * and clearing it afterwards — also when the run is cancelled (a newer trigger, the
 * glyph leaving composition).
 */
private suspend fun playOnce(
    composition: LottieComposition,
    animatable: LottieAnimatable,
    show: (LottieComposition?) -> Unit,
) {
    // Rewind before the composition becomes visible, so the first drawn frame is frame 0
    // and not the previous run's last frame.
    animatable.snapTo(composition = composition, progress = 0f, iteration = 1)
    show(composition)
    try {
        animatable.animate(composition = composition, iteration = 1, iterations = 1)
    } finally {
        show(null)
    }
}

/** Starts (or promotes) the download of a reaction animation file; returns at once. */
private suspend fun MediaCache.prefetchReactionAnimation(media: TdMedia?) {
    media?.fileId?.let { ensure(it, DownloadPriority.Avatar) }
}

/**
 * The parsed animation once its file is on disk, or null when that takes longer than
 * [budgetMs] or the file doesn't parse (the store memoises parse failures).
 */
private suspend fun MediaCache.loadReactionAnimation(media: TdMedia, budgetMs: Long): LottieComposition? {
    val fileId = media.fileId ?: return null
    if (budgetMs <= 0) return null
    val path = withTimeoutOrNull(budgetMs) {
        observe(fileId).filterIsInstance<MediaState.Ready>().first { it.path.isNotEmpty() }.path
    } ?: return null
    return LottieCompositionStore.load(path)
}

/**
 * Draws the content [scale]× the size it is laid out at, centred on the same point. The
 * node still reports its laid-out size to its parent, so nothing around it re-measures;
 * the extra area simply draws past the bounds (it is clipped only by an ancestor that
 * clips — the comments' horizontally scrolling reaction row does).
 */
private fun Modifier.drawOversized(scale: Float): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val side = (minOf(width, height) * scale).roundToInt()
    val placeable = measurable.measure(Constraints.fixed(side, side))
    layout(width, height) {
        placeable.place((width - side) / 2, (height - side) / 2)
    }
}

/**
 * One-shot hand-off from a long-press reaction picker to the reaction chips of the same
 * message: the picker dismisses as it toggles the reaction, so the choose effect has to
 * play on the chip in the row underneath, which only exists (or turns chosen) a frame
 * later. The picker [request]s; the chip whose key matches plays once it reads as chosen
 * and [consume]s. A request nobody consumed — the toggle failed, the chip never turned
 * chosen — expires after [EFFECT_REQUEST_TTL_MS] so it can't fire on a later, unrelated
 * change (e.g. a reaction cast from another device).
 *
 * Owned per message in `remember` (PostCard, the comment row), so a recycled LazyColumn
 * item starts without a request.
 */
@Stable
internal class ReactionEffectRequest {
    var key: String? by mutableStateOf(null)
        private set

    fun request(kind: ReactionKind) {
        // Paid and custom-emoji reactions have no Telegram reaction animation here.
        if (kind is ReactionKind.Emoji) key = kind.stableKey
    }

    fun consume() {
        key = null
    }
}

@Composable
internal fun rememberReactionEffectRequest(): ReactionEffectRequest {
    val request = remember { ReactionEffectRequest() }
    // Read through snapshotFlow, not in composition: a pick must not recompose the card.
    LaunchedEffect(request) {
        snapshotFlow { request.key }.collectLatest { key ->
            if (key != null) {
                delay(EFFECT_REQUEST_TTL_MS)
                request.consume()
            }
        }
    }
    return request
}

/**
 * How many emoji of the picker strip play their appear animation as it opens — the ones
 * visible without scrolling on a phone. The rest rest on the static icon: each appear
 * animation is one more file to fetch and one more Lottie drawable running at once.
 */
internal const val PICKER_APPEAR_LIMIT = 7

/** The centre animation's drawn size relative to the glyph box (its glyph fills ~half the canvas). */
private const val CENTER_SCALE = 2f

/** The burst's drawn size relative to the glyph box. */
private const val AROUND_SCALE = 3f

/** From the tap: how long the centre/around files may take to be ready and parsed. */
private const val CHOOSE_START_BUDGET_MS = 1_500L

/** From the picker opening: how long an appear file may take to be ready and parsed. */
private const val APPEAR_START_BUDGET_MS = 1_200L

/** Lifetime of an unconsumed [ReactionEffectRequest]. */
private const val EFFECT_REQUEST_TTL_MS = 2_000L
