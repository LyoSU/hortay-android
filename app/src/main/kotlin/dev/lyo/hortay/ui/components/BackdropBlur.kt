package dev.lyo.hortay.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Blurs the window this is composed in while a long-press menu is up over it, by [progress]
 * (0 = sharp, 1 = fully blurred; values past 1 from a spring's overshoot are clamped).
 *
 * Must be composed OUTSIDE the menu's own window: inside a `Dialog` or a `ModalBottomSheet`
 * [LocalView] is that window's view, and blurring it would blur the menu instead of what is
 * behind it. The blur is a [RenderEffect] on the host view (API 31+), so the whole screen
 * behind the menu blurs — live video and Android views included — while nothing in the
 * host recomposes or re-records: the radius is pushed from a [snapshotFlow], quantised to
 * whole pixels so a settled menu sets no new effect per frame. The effect is cleared at
 * radius 0 and always on dispose, so a menu removed without its exit animation (a dismiss
 * from inside, a configuration change) can never leave the screen blurred.
 *
 * Nothing else in the app sets a [RenderEffect] on the host view (media blurs go through
 * Compose `graphicsLayer`s), so this owns it outright while composed.
 *
 * Below API 31 there is no window blur; callers use [backdropScrimAlpha], which is stronger
 * there, so the menu still separates from what is behind it. Blur is not motion: under
 * reduced motion the caller snaps [progress] and the blur snaps with it.
 */
@Composable
internal fun BackdropBlur(progress: () -> Float) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) HostBlur(progress)
}

@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun HostBlur(progress: () -> Float) {
    val view = LocalView.current
    val maxRadius = with(LocalDensity.current) { BACKDROP_BLUR_RADIUS.toPx() }
    val currentProgress by rememberUpdatedState(progress)
    DisposableEffect(view) {
        onDispose { view.setRenderEffect(null) }
    }
    LaunchedEffect(view, maxRadius) {
        snapshotFlow { (currentProgress().coerceIn(0f, 1f) * maxRadius).roundToInt() }
            .collect { radius -> view.applyBlur(radius) }
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun View.applyBlur(radius: Int) {
    setRenderEffect(
        if (radius <= 0) null
        else RenderEffect.createBlurEffect(radius.toFloat(), radius.toFloat(), Shader.TileMode.CLAMP),
    )
}

/** Whether [BackdropBlur] actually blurs on this device. */
internal val backdropBlurAvailable: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Scrim strength over the backdrop of a long-press menu. Over a blur the scrim is only a
 * tint for depth — the blur already separates the menu, and a full-strength scrim on top
 * of it turned the screen muddy grey. Without blur the scrim does all the separating, so
 * it is stronger than the plain media scrim.
 */
internal fun backdropScrimAlpha(): Float = if (backdropBlurAvailable) BLURRED_SCRIM_ALPHA else UNBLURRED_SCRIM_ALPHA

private val BACKDROP_BLUR_RADIUS = 24.dp
private const val BLURRED_SCRIM_ALPHA = 0.24f
private const val UNBLURRED_SCRIM_ALPHA = 0.55f
