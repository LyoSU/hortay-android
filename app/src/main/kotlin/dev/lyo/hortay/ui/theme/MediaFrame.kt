package dev.lyo.hortay.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Hairline width for [mediaFrame] — one calibrated value so it can't drift per surface. */
val MediaFrameWidth: Dp = 0.5.dp

/**
 * Single source of truth for the WS-D1 media hairline. Rectangular photographic / video
 * content (feed media, album tiles, comment media, reply-preview thumbnails, web-preview
 * images) gets a faint `outlineVariant` border so a white-on-white image — a screenshot,
 * a light meme, a pale poster — does not dissolve into the clean near-white canvas.
 *
 * **Apply to the SAME shape the surface already clips to** (the caller keeps its existing
 * `Modifier.clip(shape)`); the border is layered on top of that clip so the rounded corner
 * stays seam-free (WS-L7). One extension instead of a per-call-site `border(...)` literal
 * so the weight and alpha can't drift.
 *
 * Dark scheme bumps the alpha (WS-J3): `outlineVariant` is the same low-contrast grey on
 * `#131318` as on `#FCFAFF`, so the hairline needs a touch more presence to stay visible.
 *
 * **Do NOT apply to transparent stickers (TGS/WebM/round video) or the fullscreen viewer**
 * — gate by content type at the call site, not by renderer. A frame around a transparent
 * sticker would box its alpha cut-out; the immersive viewer is intentionally chrome-free.
 */
@Composable
fun Modifier.mediaFrame(shape: Shape, width: Dp = MediaFrameWidth): Modifier {
    val alpha = if (isSystemInDarkTheme()) 0.6f else 0.5f
    return this.border(width, MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha), shape)
}
