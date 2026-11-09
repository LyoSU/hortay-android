package dev.lyo.hortay.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing scale on a 4 dp grid. New layout code picks a step from here instead of a fresh
 * literal, so gutters and gaps stay consistent between screens. Component-internal metrics
 * (an avatar size, a chip's own padding) remain local constants next to the component.
 */
object HortaySpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/**
 * Alpha steps for de-emphasised content drawn over a theme colour. One step per role, so
 * "quiet" text reads the same grey on every screen instead of drifting between call sites.
 */
object HortayAlpha {
    /** Secondary text and icons that must stay comfortably readable. */
    const val QUIET = 0.8f

    /** Tertiary metadata: timestamps, counters, helper captions. */
    const val FAINT = 0.55f

    /** Media and control scrims. */
    const val SCRIM = 0.45f

    /** Hairline dividers and frames. */
    const val HAIRLINE = 0.35f
}
