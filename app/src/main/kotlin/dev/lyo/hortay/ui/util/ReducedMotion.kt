package dev.lyo.hortay.ui.util

import androidx.compose.runtime.Composable
import dev.lyo.hortay.data.animatorDurationScale

/**
 * True when the user has disabled animations system-wide (Settings → Accessibility
 * "Remove animations", or Developer options "Animator duration scale = Off"), which
 * sets [animatorDurationScale] to `0f`.
 *
 * Gate for CONTENT / gesture animations (spoiler dispersal, pinch-zoom springs,
 * drag-dismiss settle) so motion-sensitive users get an instant snap to the end
 * state instead of bouncy physics or a multi-hundred-ms reveal. Infinite-loop
 * decorations (shimmer, spinners) are intentionally NOT gated here — they read as
 * "still loading" rather than "motion", and Compose's animator-scale handling
 * already leaves them running.
 *
 * Reads the scale on every recomposition; cheap (a single framework getter) and the
 * value only changes when the user toggles the OS setting, so no need to remember it.
 */
@Composable
fun rememberReducedMotion(): Boolean = animatorDurationScale() == 0f
