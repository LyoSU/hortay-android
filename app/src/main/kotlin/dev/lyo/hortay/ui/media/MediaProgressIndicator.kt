package dev.lyo.hortay.ui.media

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import dev.lyo.hortay.data.MediaState
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.theme.HortayExpressive
import dev.lyo.hortay.ui.theme.asComposeShape

/**
 * Telegram-style determinate media progress indicator. A semi-transparent black disk
 * sits over the (blurred) minithumb; an arc traces the download progress; a central
 * action icon hints at the action ("arrow_downward" while downloading, "close" if
 * tappable to cancel, "refresh" if showing a retry on a failed download). The arc
 * itself slowly rotates while indeterminate — even when bytes are flowing the user
 * sees motion, which reads as "alive" rather than "frozen".
 *
 * Tuned to mirror the official Telegram Android client visually: same disk alpha (~0.4),
 * same stroke ratio (~0.07 of the diameter), same arrow chevron style.
 *
 * If [onClick] is non-null the disk becomes tappable — used to wire "tap to cancel"
 * mid-download and "tap to retry" on a failure.
 */
@Composable
fun MediaProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconName: String = "arrow_downward",
    onClick: (() -> Unit)? = null,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = PROGRESS_TWEEN_MS, easing = LinearEasing),
        label = "media-progress",
    )
    val transition = rememberInfiniteTransition(label = "media-progress-spin")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SPIN_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "media-progress-spin-angle",
    )
    // Determinate-progress disc keeps the Telegram-canonical look: clean black
    // translucent circle, circular arc that travels around it as bytes flow,
    // central action icon. M3 Expressive's polygon-morph cue lives in the
    // *indeterminate* sibling below; mixing the two on the determinate disc
    // (Cookie ridges around a circular arc) read as visual noise — the arc
    // and the polygon ridges fought for the eye instead of compounding.
    val diskModifier = Modifier
        .size(size)
        .clip(CircleShape)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Box(modifier = modifier.then(diskModifier), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val diameter = this.size.minDimension
            val stroke = diameter * STROKE_FRACTION
            val inset = stroke / 2f
            val arcSize = Size(diameter - stroke, diameter - stroke)
            val arcOffset = Offset(inset, inset)
            drawCircle(
                color = Color.Black.copy(alpha = DISK_ALPHA),
                radius = diameter / 2f,
            )
            // Faint full ring as the "track" — gives the eye a stable reference
            // for how much is left.
            drawArc(
                color = Color.White.copy(alpha = TRACK_ALPHA),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = arcOffset,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // Active arc. We sweep from animatedProgress, but rotate the start
            // angle by `spin` so the arc visibly travels — Telegram does this so
            // a stalled 0% isn't indistinguishable from a hung indicator.
            val sweep = (animatedProgress * 360f).coerceAtLeast(MIN_SWEEP_DEGREES)
            drawArc(
                color = Color.White,
                startAngle = -90f + spin,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = arcOffset,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Symbol(
            name = iconName,
            contentDescription = null,
            tint = Color.White,
            size = size * ICON_FRACTION,
        )
    }
}

/**
 * Indeterminate variant for the brief window between "we asked TDLib for the file"
 * and "first progress update arrived". Same look as [MediaProgressIndicator], but
 * the arc is a fixed-length sweep that just spins.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaIndeterminateIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    onClick: (() -> Unit)? = null,
) {
    // Indeterminate state — the brief window before progress data lands —
    // delegates to M3 Expressive's `LoadingIndicator`: it morphs through the
    // canonical polygon cycle (Circle → SoftBurst → Cookie9 → Pill → Sunny),
    // the same vocabulary used by pull-to-refresh, auth-submit and comments
    // thread load. Black translucent disc backdrop keeps the indicator
    // legible on any photo (white snow, black headlines, mid-grey portraits).
    // Cancel-X overlays the polygon when [onClick] is wired (tap-to-cancel
    // matches Telegram's affordance during download).
    val diskModifier = Modifier
        .size(size)
        .clip(CircleShape)
        .background(Color.Black.copy(alpha = DISK_ALPHA))
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Box(modifier = modifier.then(diskModifier), contentAlignment = Alignment.Center) {
        androidx.compose.material3.LoadingIndicator(
            modifier = Modifier.size(size * 0.7f),
            color = Color.White,
        )
        if (onClick != null) {
            Symbol(
                name = "close",
                contentDescription = null,
                tint = Color.White,
                size = size * ICON_FRACTION,
            )
        }
    }
}

/**
 * Combined "downloading" overlay: progress (or indeterminate) circle plus a Telegram-style
 * "5.2 / 12.4 MB" pill underneath. The label is hidden when [totalBytes] is zero (TDLib
 * sometimes hasn't resolved the file size yet — better no label than a "5.2 / 0 MB").
 *
 * Tap on the indicator → [onCancel] (matches Telegram's tap-to-cancel during download).
 *
 * Visually unified: both pre-progress (no bytes yet) and mid-download states render the
 * same M3 Expressive polygon-cycle [MediaIndeterminateIndicator]. An earlier iteration
 * split the two — polygon for indeterminate, circle+arc for determinate — which made a
 * single download visibly switch styles mid-flow as the first progress bytes landed.
 * The numeric "X.X / Y.Y MB" pill underneath is the canonical surface for the actual
 * progress percentage; the disc is just "system is downloading", same vocabulary as
 * pull-to-refresh / auth submit / comments load.
 */
@Composable
fun MediaLoadingOverlay(
    progress: Float,
    downloadedBytes: Long,
    totalBytes: Long,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    // `progress` parameter unused now — kept in the signature for API stability
    // (callers still pass it). The numeric byte-progress pill below is the
    // canonical surface for "how many bytes landed"; the visual disc is just
    // "system is downloading".
    @Suppress("UNUSED_PARAMETER", "unused")
    val keepParam = progress
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MediaIndeterminateIndicator(onClick = onCancel)
        if (totalBytes > 0) {
            val units = stringArrayResource(R.array.size_units)
            Spacer(Modifier.height(BYTE_LABEL_GAP))
            BytePill(text = formatProgressBytes(downloadedBytes, totalBytes, units))
        }
    }
}

/**
 * Failed-download placeholder. Shows a refresh icon on the same translucent disk — a
 * tap fires [onRetry] to force a re-download. Used in place of the loading overlay
 * when [dev.lyo.hortay.data.MediaState.Failed] is the slot's terminal state, including
 * the "stalled past N retries" surface from MediaCache's watchdog.
 */
@Composable
fun MediaFailedOverlay(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    onRetry: (() -> Unit)? = null,
) {
    val diskShape = HortayExpressive.ReactionSelected.asComposeShape()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = DISK_ALPHA), diskShape)
            .let { if (onRetry != null) it.clickable(onClick = onRetry) else it },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(
            name = "refresh",
            contentDescription = stringResource(R.string.media_retry),
            tint = Color.White,
            size = size * ICON_FRACTION,
        )
    }
}

@Composable
private fun BytePill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = DISK_ALPHA))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun formatProgressBytes(downloaded: Long, total: Long, units: Array<String>): String {
    // For the same-unit case ("5.2 / 12.4 MB") only the right side carries the unit —
    // matches Telegram's compact label. When the units differ ("780 KB / 1.2 MB") we
    // print both sides with their own unit.
    val (downValue, downUnit) = humanizeBytes(downloaded, units)
    val (totalValue, totalUnit) = humanizeBytes(total, units)
    return if (downUnit == totalUnit) {
        "$downValue / $totalValue $totalUnit"
    } else {
        "$downValue $downUnit / $totalValue $totalUnit"
    }
}

private fun humanizeBytes(bytes: Long, units: Array<String>): Pair<String, String> {
    if (bytes <= 0L) return "0" to units[0]
    var size = bytes.toDouble()
    var idx = 0
    while (size >= 1024 && idx < units.lastIndex) {
        size /= 1024
        idx++
    }
    val value = if (size >= 100 || idx == 0 || size == size.toLong().toDouble()) {
        size.toLong().toString()
    } else {
        "%.1f".format(size)
    }
    return value to units[idx]
}

/**
 * Returns `true` only after [graceMs] elapsed continuously in a "loading" state. Resets
 * to `false` whenever [pending] flips to false (file finished / failed) or [key] changes.
 *
 * UX motivation: most TDLib downloads on a healthy network land in 100-400ms — within the
 * grace window, the user never sees a spinner flicker on top of the minithumb, so loading
 * for a quick file is *invisible*. Telegram-Android does the same: their RecyclerView item
 * binds the placeholder thumb instantly and only paints the determinate progress disc after
 * a short delay so a fast load doesn't strobe a tiny circle on the screen.
 *
 * Anything still loading past [graceMs] gets the full overlay — at that point the user is
 * looking at a slow file and *needs* feedback (and a tap-to-cancel affordance).
 */
@Composable
fun rememberDeferredLoading(
    pending: Boolean,
    key: Any?,
    graceMs: Long = LOADING_OVERLAY_GRACE_MS,
): Boolean {
    val visible by produceState(initialValue = false, pending, key) {
        value = if (pending) {
            delay(graceMs)
            true
        } else {
            false
        }
    }
    return visible
}

/** Convenience: treat [MediaState.Idle] and [MediaState.Downloading] alike as "loading". */
@Composable
fun rememberDeferredLoading(
    state: MediaState,
    key: Any?,
    graceMs: Long = LOADING_OVERLAY_GRACE_MS,
): Boolean = rememberDeferredLoading(
    pending = state is MediaState.Downloading || state is MediaState.Idle,
    key = key,
    graceMs = graceMs,
)

// 600 ms grace window before the loading overlay paints. Tuned so that:
//   • Anything served from TDLib's local cache (Ready on first emit) → instant, no overlay.
//   • Most home-DC photo loads (~150-300 KB, ~200-500 ms RTT) → finish before grace expires.
//   • Slow files (DC migration, throttling, big videos) → user gets feedback at 600 ms,
//     well below human "is this stuck?" attention threshold (~1 s).
private const val LOADING_OVERLAY_GRACE_MS = 600L
private const val PROGRESS_TWEEN_MS = 200
private const val SPIN_PERIOD_MS = 2_400
private const val DISK_ALPHA = 0.45f
private const val TRACK_ALPHA = 0.18f
private const val STROKE_FRACTION = 0.07f
// Even at 0% we draw a tiny arc so the indicator never looks empty / broken on screen.
private const val MIN_SWEEP_DEGREES = 8f
private const val INDETERMINATE_SWEEP_DEGREES = 90f
private const val ICON_FRACTION = 0.42f
private val BYTE_LABEL_GAP = 8.dp
