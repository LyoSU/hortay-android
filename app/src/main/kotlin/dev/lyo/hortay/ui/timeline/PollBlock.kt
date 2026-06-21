@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.PollKind
import dev.lyo.hortay.data.PollOption
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.TdMedia
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdMediaImage
import dev.lyo.hortay.ui.text.rememberRenderableText
import dev.lyo.hortay.ui.theme.mediaFrame
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Voting contract handed to [PollBlock]. [PostCard] / [TimelineScreen] capture the host
 * post once and produce a [PollVoting] whose lambdas already know which (chatId, messageId)
 * to act on, so the block stays a pure projection of [PostContent.Poll].
 *
 * [onCommit] is the single entry point for all voting actions:
 *   * `[]` → retract any existing vote — only invoked when the poll is regular AND
 *     [PollKind.Regular.allowsRevoting] is true. The block hides the affordance otherwise.
 *   * `[i]` → single-answer commit (regular single-vote OR quiz).
 *   * `[i, j, …]` → multi-answer commit. Only invoked when [PollKind.Regular.allowsMultipleAnswers]
 *     is true; the block then renders a "Голосувати" CTA below the option list and stages the
 *     selection locally until the user confirms.
 *
 * Held [@Immutable] so the parent PostCard's recomposition does not invalidate the block's
 * skippability when neither lambda changes identity.
 */
@Immutable
class PollVoting(
    val onCommit: (chosenIndices: IntArray) -> Unit,
)

/**
 * Material 3 Expressive poll card.
 *
 * Visual stages (one card morphs between them as state lands from TDLib):
 *
 *   1. **Pre-vote, regular single-answer** — radio dots, tap a row → instant commit.
 *   2. **Pre-vote, regular multi-answer** — checkbox squares, tap to stage, "Голосувати" CTA.
 *   3. **Pre-vote, quiz** — radio dots (one shot), tap → commits the choice.
 *   4. **Post-vote results** — animated horizontal progress bars per row, percentages, chosen
 *      rows tinted primary; for quizzes correct/wrong icons replace the dot.
 *   5. **Closed** — read-only results view with a "Опитування завершено" header chip.
 *
 * Header chips:
 *   * Type chip — "Опитування" or "Вікторина" (quiz badge)
 *   * Anonymous chip — visibility_off icon + "Анонімне"
 *   * Countdown chip — only when `closeDate > 0`; ticks every second, switches to
 *     a static "Завершено" once `closeDate` passes (TDLib will deliver `isClosed=true`
 *     a moment later via `UpdateMessageContent`).
 *
 * The block never reaches into TDLib directly — all voting goes through [voting], which is
 * null in read-only contexts (guest mode, deep-link preview surfaces, tests) so the block
 * degrades to a pure render of the current poll state.
 */
@Composable
internal fun PollBlock(
    content: PostContent.Poll,
    voting: PollVoting?,
    onOpenInSource: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cs.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        // Header media (photo banner attached to the poll itself — TDLib Polls 2.0).
        content.headerMedia?.let { thumb ->
            PollHeaderMedia(thumb, onClick = onOpenInSource)
            Spacer(Modifier.height(12.dp))
        }
        PollHeaderChips(content)
        Spacer(Modifier.height(10.dp))
        PollQuestion(content)
        if (content.description.text.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            PollDescription(content)
        }
        Spacer(Modifier.height(14.dp))
        PollOptionsList(content, voting)
        Spacer(Modifier.height(12.dp))
        PollFooter(content, voting, onOpenInSource)
    }
}

@Composable
private fun PollHeaderMedia(thumb: TdMedia, onClick: () -> Unit) {
    val bannerShape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(bannerShape)
            // D1 — poll banner is rectangular photographic content nested in the poll
            // card; hairline frame on the same shape it clips to.
            .mediaFrame(bannerShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
    ) {
        TdMediaImage(
            media = thumb,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PollHeaderChips(content: PostContent.Poll) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Type chip — quiz polls get a distinct tertiary palette so the lamp icon
        // reads as "different mode" at a glance; regular polls use surfaceVariant.
        val isQuiz = content.kind is PollKind.Quiz
        val typeLabel = stringResource(
            when {
                content.isClosed -> R.string.poll_state_closed
                isQuiz -> R.string.poll_type_quiz
                else -> R.string.poll_type_regular
            },
        )
        PollChip(
            icon = if (isQuiz) "lightbulb" else "poll",
            label = typeLabel,
            container = if (isQuiz) cs.tertiaryContainer else cs.secondaryContainer,
            content = if (isQuiz) cs.onTertiaryContainer else cs.onSecondaryContainer,
        )
        if (content.isAnonymous) {
            PollChip(
                icon = "visibility_off",
                label = stringResource(R.string.poll_anonymous_short),
                container = cs.surfaceContainerHighest,
                content = cs.onSurfaceVariant,
            )
        }
        PollCountdownChip(content)
    }
}

/**
 * Live-tick countdown chip. Subscribes to a 1 Hz tick only while the chip is composed and
 * the poll actually has a closeDate in the future. Once `closeDate` passes we render a
 * static "Завершено" label until TDLib delivers the authoritative `isClosed=true` event a
 * moment later (no need to wait — visual state already converged).
 */
@Composable
private fun PollCountdownChip(content: PostContent.Poll) {
    if (content.closeDate <= 0 || content.isClosed) return
    var nowSec by remember { mutableStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(content.closeDate) {
        while (true) {
            nowSec = System.currentTimeMillis() / 1000L
            if (nowSec >= content.closeDate) break
            delay(1000L)
        }
    }
    val remaining = content.closeDate - nowSec
    val label = if (remaining <= 0L) {
        stringResource(R.string.poll_countdown_finished)
    } else {
        formatCountdown(remaining)
    }
    val cs = MaterialTheme.colorScheme
    val critical = remaining in 1L..10L
    PollChip(
        icon = "timer",
        label = label,
        container = if (critical) cs.errorContainer else cs.surfaceContainerHighest,
        content = if (critical) cs.onErrorContainer else cs.onSurfaceVariant,
    )
}

private fun formatCountdown(seconds: Long): String {
    val total = max(seconds, 0L)
    return if (total >= 3600L) {
        val h = TimeUnit.SECONDS.toHours(total)
        val m = TimeUnit.SECONDS.toMinutes(total) % 60
        "%d:%02d".format(h, m)
    } else {
        val m = TimeUnit.SECONDS.toMinutes(total)
        val s = total % 60
        "%d:%02d".format(m, s)
    }
}

@Composable
private fun PollChip(
    icon: String,
    label: String,
    container: Color,
    content: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Symbol(name = icon, tint = content, size = 14.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PollQuestion(content: PostContent.Poll) {
    val rt = rememberRenderableText(content.question)
    Text(
        text = rt.text,
        inlineContent = rt.inlineContent,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun PollDescription(content: PostContent.Poll) {
    val rt = rememberRenderableText(content.description)
    Text(
        text = rt.text,
        inlineContent = rt.inlineContent,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PollOptionsList(content: PostContent.Poll, voting: PollVoting?) {
    val haptics = LocalHapticFeedback.current
    val multi = (content.kind as? PollKind.Regular)?.allowsMultipleAnswers == true
    // Local val so Kotlin's smart-cast carries through the lambda capture below — using
    // `voting` directly inside the lambda would force a `voting?.` even though `canVote`
    // already implies non-null, which Kotlin would warn as "always true / safe-call".
    val v = voting
    val canVote = v != null && !content.isClosed && !content.hasVoted
    // Staged selection for multi-answer pre-vote. Reset whenever options identity changes
    // (e.g. after a successful commit lands new `isChosen` state via UpdateMessageContent)
    // so the local state never lags behind TDLib's authoritative view.
    val staged = remember(content.options) { mutableStateOf<Set<Int>>(emptySet()) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        content.options.forEach { option ->
            PollOptionRow(
                option = option,
                kind = content.kind,
                showResults = content.hasVoted || content.isClosed || !canVote,
                isStaged = option.index in staged.value,
                multiSelect = multi,
                onTap = if (canVote && v != null) {
                    {
                        if (multi) {
                            staged.value = if (option.index in staged.value) {
                                staged.value - option.index
                            } else {
                                staged.value + option.index
                            }
                        } else {
                            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                            v.onCommit(intArrayOf(option.index))
                        }
                    }
                } else null,
            )
        }
        // Multi-answer commit CTA. Renders inline below the option list (Telegram-Android
        // idiom) only when the user has staged ≥1 row AND has not yet voted.
        AnimatedVisibility(
            visible = multi && canVote && staged.value.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        v?.onCommit(staged.value.sorted().toIntArray())
                        staged.value = emptySet()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Symbol(
                        name = "how_to_vote",
                        size = 18.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.poll_action_vote))
                }
            }
        }
    }
}

@Composable
private fun PollOptionRow(
    option: PollOption,
    kind: PollKind,
    showResults: Boolean,
    isStaged: Boolean,
    multiSelect: Boolean,
    onTap: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val quiz = kind as? PollKind.Quiz
    val isCorrect = quiz != null && option.index in quiz.correctOptionIds
    // Row outcome state for post-vote / quiz colouring. Fill discipline (WS-O): lavender
    // (`primary`) is reserved for the user's OWN choice on a regular poll — it must never
    // tint a result bar the user didn't pick. Non-chosen result bars use the neutral
    // `secondary` so they read as "other results" rather than "your choice", and stay
    // visible against the card (the previous `onSurfaceVariant` read as a near-invisible
    // grey on the result bar).
    //   - regular post-vote: chosen → primary (lavender, yours), others → secondary
    //   - quiz post-vote, user picked this: correct → tertiary (success), wrong → error
    //   - quiz post-vote, user did NOT pick this: correct → tertiary highlighted (always show
    //     the right answer), others → secondary
    val accent = when {
        quiz != null && showResults && option.isChosen && isCorrect -> cs.tertiary
        quiz != null && showResults && option.isChosen && !isCorrect -> cs.error
        quiz != null && showResults && isCorrect -> cs.tertiary
        option.isChosen && showResults -> cs.primary
        else -> cs.secondary
    }
    val container by animateColorAsState(
        targetValue = when {
            isStaged && !showResults -> cs.primaryContainer.copy(alpha = 0.40f)
            option.isChosen && showResults -> cs.primaryContainer.copy(alpha = 0.20f)
            else -> Color.Transparent
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "pollRowContainer",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(container)
            .then(if (onTap != null) Modifier.clickable(role = Role.Button, onClick = onTap) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PollOptionMarker(
                option = option,
                kind = kind,
                showResults = showResults,
                isStaged = isStaged,
                multiSelect = multiSelect,
                accent = accent,
            )
            Spacer(Modifier.width(12.dp))
            option.thumb?.let { t ->
                val thumbShape = RoundedCornerShape(8.dp)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(thumbShape)
                        // D1 — small option thumbnail (rectangular photographic). Kept at
                        // 8 dp rather than shapes.small (12 dp): a 12 dp radius reads heavy
                        // on a 36 dp tile this dense.
                        .mediaFrame(thumbShape)
                        .background(cs.surfaceContainerHighest),
                ) {
                    TdMediaImage(media = t, contentDescription = null)
                }
                Spacer(Modifier.width(10.dp))
            }
            val labelRt = rememberRenderableText(option.text)
            Text(
                text = labelRt.text,
                inlineContent = labelRt.inlineContent,
                style = MaterialTheme.typography.bodyMedium,
                color = if (showResults) cs.onSurface else cs.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (showResults) {
                Spacer(Modifier.width(8.dp))
                AnimatedContent(
                    targetState = option.percent,
                    label = "pollRowPercent",
                ) { percent ->
                    Text(
                        text = "$percent%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = accent,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = showResults,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                Spacer(Modifier.height(6.dp))
                PollResultBar(
                    percent = option.percent,
                    accent = accent,
                    isBeingChosen = option.isBeingChosen,
                )
            }
        }
    }
}

/**
 * Result bar — Material 3 Expressive `LinearWavyProgressIndicator` for the currently-being-
 * chosen row (signals "live, pending RPC") and a flat [LinearProgressIndicator] for settled
 * results. Animates the fill on percent changes so the bar visibly slides when TDLib pushes
 * updated counts via `UpdateMessageContent`.
 */
@Composable
private fun PollResultBar(percent: Int, accent: Color, isBeingChosen: Boolean) {
    val target = (percent.coerceIn(0, 100) / 100f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = MaterialTheme.motionScheme.slowEffectsSpec(),
        label = "pollResultBar",
    )
    if (isBeingChosen) {
        LinearWavyProgressIndicator(
            progress = { animated },
            color = accent,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
        )
    } else {
        LinearProgressIndicator(
            progress = { animated },
            color = accent,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
    }
}

/**
 * Leading marker for a poll option:
 *   * pre-vote single-answer / quiz → radio-style empty/filled circle
 *   * pre-vote multi-answer → square checkbox-style mark
 *   * post-vote regular → check inside primary disc on chosen row, dim ring elsewhere
 *   * post-vote quiz → green ✓ (correct) / red ✗ (wrong) / ring (untouched)
 */
@Composable
private fun PollOptionMarker(
    option: PollOption,
    kind: PollKind,
    showResults: Boolean,
    isStaged: Boolean,
    multiSelect: Boolean,
    accent: Color,
) {
    val cs = MaterialTheme.colorScheme
    val quiz = kind as? PollKind.Quiz
    val isCorrect = quiz != null && option.index in quiz.correctOptionIds

    val markerSize = 22.dp
    Box(
        modifier = Modifier.size(markerSize),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // ---------------------- post-vote ----------------------
            showResults && option.isChosen && quiz != null && !isCorrect -> {
                // Wrong quiz pick — filled error disc with ✗ glyph (use existing "close").
                Box(
                    modifier = Modifier
                        .size(markerSize)
                        .clip(CircleShape)
                        .background(cs.error),
                ) {
                    Symbol(
                        name = "close",
                        tint = cs.onError,
                        size = 16.dp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            showResults && (option.isChosen || (quiz != null && isCorrect)) -> {
                // Chosen (any mode) OR un-picked-but-correct quiz answer — filled accent disc
                // with a hand-drawn check (no dedicated drawable in the pack).
                Box(
                    modifier = Modifier
                        .size(markerSize)
                        .clip(CircleShape)
                        .background(accent),
                ) {
                    DrawCheck(color = cs.onPrimary, size = 14.dp, modifier = Modifier.align(Alignment.Center))
                }
            }
            showResults -> {
                // Result view, unchosen row — small dim ring so the leading column stays aligned.
                Box(
                    modifier = Modifier
                        .size(markerSize)
                        .clip(CircleShape)
                        .border(2.dp, cs.outlineVariant, CircleShape),
                )
            }
            // ---------------------- pre-vote -----------------------
            multiSelect && isStaged -> {
                Box(
                    modifier = Modifier
                        .size(markerSize)
                        .clip(RoundedCornerShape(6.dp))
                        .background(cs.primary),
                ) {
                    DrawCheck(color = cs.onPrimary, size = 14.dp, modifier = Modifier.align(Alignment.Center))
                }
            }
            multiSelect -> {
                Box(
                    modifier = Modifier
                        .size(markerSize)
                        .clip(RoundedCornerShape(6.dp))
                        .border(2.dp, cs.outline, RoundedCornerShape(6.dp)),
                )
            }
            else -> {
                // Single-answer radio dot. Staged renders the inner filled circle so the user
                // sees the immediate optimistic flip before the RPC even fires.
                Box(
                    modifier = Modifier
                        .size(markerSize)
                        .clip(CircleShape)
                        .border(2.dp, if (isStaged) cs.primary else cs.outline, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isStaged) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(cs.primary),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Hand-drawn checkmark on a coloured disc. Used instead of a `sym_check.xml` drawable to keep
 * the icon pack lean — the path is two strokes, drawn once per recomposition (cheap), and
 * the colour comes from the host's [MaterialTheme] so dark / light themes adapt for free.
 */
@Composable
private fun DrawCheck(color: Color, size: Dp, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = with(density) { 2.dp.toPx() }
        val p1 = Offset(w * 0.18f, h * 0.54f)
        val p2 = Offset(w * 0.42f, h * 0.78f)
        val p3 = Offset(w * 0.82f, h * 0.30f)
        drawLine(color = color, start = p1, end = p2, strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color = color, start = p2, end = p3, strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun PollFooter(
    content: PostContent.Poll,
    voting: PollVoting?,
    onOpenInSource: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val totalLabel = if (content.totalVotes == 0) {
            stringResource(R.string.poll_total_votes_empty)
        } else {
            pluralStringResource(R.plurals.poll_total_votes_plural, content.totalVotes, content.totalVotes)
        }
        Text(
            text = totalLabel,
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )

        // Retraction affordance for regular polls only (quiz polls cannot be re-voted).
        val regular = content.kind as? PollKind.Regular
        if (
            voting != null &&
            content.hasVoted &&
            !content.isClosed &&
            regular?.allowsRevoting == true
        ) {
            TextButton(
                onClick = { voting.onCommit(IntArray(0)) },
            ) {
                Text(stringResource(R.string.poll_action_retract))
            }
        }

        // Quiz explanation lamp — only when the explanation text exists AND the user has
        // already answered (TDLib reveals the explanation only post-answer).
        val quiz = content.kind as? PollKind.Quiz
        if (quiz != null && content.hasVoted && quiz.explanation.text.isNotBlank()) {
            FilledTonalButton(
                onClick = onOpenInSource,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = cs.tertiaryContainer,
                    contentColor = cs.onTertiaryContainer,
                ),
                shape = MaterialTheme.shapes.large,
            ) {
                Symbol(name = "lightbulb", tint = cs.onTertiaryContainer, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.poll_action_view_explanation))
            }
        }

        // "Propose an option" affordance — Polls 2.0 lets viewers add new options. Hortay
        // is a reader app, so this taps through to Telegram.
        if (content.canAddOption && !content.isClosed) {
            TextButton(onClick = onOpenInSource) {
                Text(stringResource(R.string.poll_action_add_option))
            }
        }
    }
}
