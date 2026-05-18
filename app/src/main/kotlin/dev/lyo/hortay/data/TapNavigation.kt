package dev.lyo.hortay.data

import android.animation.ValueAnimator

/**
 * Anti-flicker grace for tap-driven destination skeletons.
 *
 * "Don't paint the skeleton for the first N ms after the destination
 * mounts." Common-case warm-cache reads land Ready inside a single frame;
 * a skeleton that paints for 16 ms and unmounts reads as a flicker even
 * though both states are technically correct. 120 ms is below the human
 * flicker threshold for app-level loading indicators (commonly cited
 * 100-150 ms range) and below typical Compose enter-transition durations,
 * so fast loads are *invisible*: tap → animation runs → destination
 * lands populated → no blank-frame artifact.
 *
 * Architectural contract — this is NOT a push-side timer:
 *   - The tap pushes the destination immediately. The source view never
 *     holds, never blocks, never adds latency. Animation start and data
 *     load start kick off in parallel.
 *   - This grace lives screen-side: the mounted destination decides
 *     whether to paint a skeleton based on its own data state (still
 *     Loading after [SCREEN_MOUNT_GRACE_MS]?) — not on a flag forwarded
 *     by the pusher. Data state, not a timer, drives the decision.
 *   - The transition animation is "free runway", not a deadline. If data
 *     arrives during the animation, the destination mounts populated; if
 *     not, the skeleton paints once the animation has had time to settle.
 *
 * Media downloads use a longer [dev.lyo.hortay.ui.media.LOADING_OVERLAY_GRACE_MS]
 * (600 ms) — file IO legitimately takes longer than data hydration and a
 * faster grace would strobe progress discs on healthy connections. The
 * two values are calibrated to different latency budgets, not duplicates.
 *
 * See [effectiveSkeletonGrace] for how this composes with the system
 * animator-duration-scale (developer options / accessibility "Remove
 * animations") — when there's no transition to hide behind, grace is 0
 * and the skeleton paints on the first Loading frame.
 */
const val SCREEN_MOUNT_GRACE_MS: Long = 120L

/**
 * Apply the system animator-duration-scale to a base grace window in ms.
 *
 * Reads [ValueAnimator.getDurationScale]:
 *   - 1.0 (default) → returns [baseMs] unchanged.
 *   - 0.5 / 2.0 (developer options) → grace tracks the user's animation
 *     speed knob so an x2 user sees longer-running animations *and*
 *     proportionally longer skeleton-suppression.
 *   - 0.0 (animations disabled / accessibility "Remove animations") →
 *     returns 0. The destination appears without a transition; any grace
 *     would just be a delay before painting feedback the user needs sooner.
 *
 * Returning a non-negative `Long` so callers can pass the result straight
 * to `kotlinx.coroutines.delay` without an extra check.
 */
fun effectiveSkeletonGrace(baseMs: Long): Long {
    val scale = ValueAnimator.getDurationScale().toDouble()
    return (baseMs * scale).toLong().coerceAtLeast(0L)
}
