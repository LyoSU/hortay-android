package dev.lyo.hortay.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.lyo.hortay.ui.media.SpoilerParticles
import dev.lyo.hortay.ui.media.SpoilerRipple
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Per-group spoiler-reveal state shared by every spoiler-bearing text renderer — the flat
 * [dev.lyo.hortay.data.FormattedText] path in [rememberRenderableText] and the rich-message
 * tree in `ui.rich`. A tap on any glyph (or inline-emoji stub) inside a spoiler reveals every
 * spoiler of the text at once — each cover's line runs behind ripples that start together —
 * then leaves them revealed.
 *
 * [revealedGroups] is the current snapshot value — hand it straight to a `remember(...)` key
 * so the backing [androidx.compose.ui.text.AnnotatedString] rebuilds (Transparent → text colour)
 * exactly when the text reveals; [LinkAwareText] then masks the glyphs outside the growing ripple
 * until it has passed. [revealStart] is read in the draw phase only: the ripple itself is drawn
 * from [SpoilerParticles.frameTimeMs], so a reveal never recomposes per frame.
 */
internal class SpoilerReveal(
    val revealedGroups: RevealedSpoilers,
    val reveal: (Int) -> Unit,
    val revealStart: (Int) -> Long,
)

/**
 * Which spoiler groups of a text are revealed. A text reveals all of its spoilers at once, so
 * this is all-or-nothing; renderers ask `groupId in revealedGroups` without knowing the ids.
 *
 * Deliberately not a `Set<Int>`: an "every id" set has no honest size, and a set that claimed
 * to be empty compared equal to `emptySet()` under the state's structural-equality policy, so
 * the reveal write was dropped and the text never uncovered. Two enum constants are distinct
 * `remember` keys by construction.
 */
internal enum class RevealedSpoilers {
    None,
    All,
    ;

    operator fun contains(groupId: Int): Boolean = this == All
}

/** [SpoilerReveal.revealStart] of a group still under its cover. */
internal const val SPOILER_COVERED = 0L

/** [SpoilerReveal.revealStart] of a group whose reveal has finished. */
internal const val SPOILER_SETTLED = -1L

/** The ripple is time-based in the draw phase; this only bounds how long its state is kept. */
private const val REVEAL_SETTLE_MS = SpoilerRipple.MAX_DURATION_MS + 100L

/**
 * Snapshot-backed reveal state of one text, free of Compose UI so it is unit-testable.
 *
 * [revealed] flips once, false → true, and never back (a new text gets a new instance).
 * [rippleStartMs] holds the reveal start while the ripple may still be running and is cleared
 * by [settle]; [revealStart] folds both into the draw-phase encoding consumers read.
 */
@Stable
internal class SpoilerRevealState {
    private var revealed by mutableStateOf(false)
    private var rippleStartMs by mutableStateOf<Long?>(null)

    /** Composition read: changes exactly once, when the text reveals. */
    val revealedGroups: RevealedSpoilers
        get() = if (revealed) RevealedSpoilers.All else RevealedSpoilers.None

    /**
     * Reveals every spoiler with a ripple starting at [nowMs] (a positive monotonic time).
     * Returns false, changing nothing, when the text was already revealed — a second tap must
     * not restart the ripple.
     */
    fun reveal(nowMs: Long): Boolean {
        if (revealed) return false
        revealed = true
        rippleStartMs = nowMs
        return true
    }

    /** The ripple has certainly finished: drop its start so the cover stops being drawn. */
    fun settle() {
        rippleStartMs = null
    }

    /** Draw-phase read: [SPOILER_COVERED], the ripple start, or [SPOILER_SETTLED]. */
    fun revealStart(): Long = rippleStartMs ?: if (revealed) SPOILER_SETTLED else SPOILER_COVERED
}

/**
 * [contentKey] must be a content-stable identity (see [spoilerContentKey] for the
 * [dev.lyo.hortay.data.FormattedText] path): reveal state resets only when the underlying text
 * genuinely changes, not on every reaction / view-count / scroll recomposition that hands the
 * renderer a fresh instance.
 */
@Composable
internal fun rememberSpoilerReveal(contentKey: Any): SpoilerReveal {
    val state = remember(contentKey) { SpoilerRevealState() }
    val scope = rememberCoroutineScope()

    // A tap on any group reveals every spoiler of the text at once, as Telegram does: all
    // groups share one reveal start, so their ripples run together.
    val reveal: (Int) -> Unit = remember(state) {
        { _ ->
            if (state.reveal(SpoilerParticles.nowMs())) {
                scope.launch {
                    delay(REVEAL_SETTLE_MS)
                    state.settle()
                }
            }
        }
    }

    val revealStart: (Int) -> Long = remember(state) { { _ -> state.revealStart() } }

    return SpoilerReveal(state.revealedGroups, reveal, revealStart)
}
