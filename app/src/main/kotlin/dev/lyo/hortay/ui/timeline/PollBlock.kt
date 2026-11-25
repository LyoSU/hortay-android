@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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
import dev.lyo.hortay.ui.theme.tabularFigures
import dev.lyo.hortay.ui.util.rememberReducedMotion
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
 *   * `[i]` → single-answer commit (single-answer regular poll or quiz).
 *   * `[i, j, …]` → multi-answer commit. Only invoked when the poll or quiz allows multiple
 *     answers ([allowsMultipleAnswers]); the block then stages the selection locally and
 *     commits it from a "Vote" button in the footer slot (see [PollFooter]).
 *
 * Held [@Immutable] so the parent PostCard's recomposition does not invalidate the block's
 * skippability when neither lambda changes identity.
 */
@Immutable
class PollVoting(
    val onCommit: (chosenIndices: IntArray) -> Unit,
)

/** Corner of an option row — the same 12 dp as every other in-body content container. */
private val OptionShape = ContentBlockShape

/** Leading control (radio / checkbox) and trailing outcome mark size. */
private val ControlSize = 22.dp

/**
 * Poll, laid out on the post's own text edge.
 *
 * Tried: one heavy `surfaceContainerHigh` slab around the whole poll with the options
 * indented a further 12 dp inside it. On the device the slab weighed more than the
 * question, and chips / question / options / footer sat on three different left edges.
 * Now there is no outer container: the meta line, question, option rows and footer share
 * the post's text edge, and each option row is itself the (only) container — full width,
 * ≥ 48 dp, the whole row tappable.
 *
 * Stages:
 *   1. **Pre-vote, single answer** (regular or quiz) — radio ring per row, tap commits.
 *   2. **Pre-vote, multiple answers** — checkbox per row, tap stages, "Vote" (in the footer
 *      slot, laid out from the first frame) commits.
 *   3. **Results** (voted, closed, or read-only) — no controls: each row fills from the start
 *      edge to its share, percentage in tabular figures at the end. The user's pick and, for
 *      quizzes, correct / wrong answers carry an icon AND a colour role (never colour alone)
 *      plus a TalkBack state. The fill animates on the change (fast spatial spec; snaps under
 *      reduced motion) and is read in the draw phase. A poll with no votes at all shows no
 *      percentages and no fill — a closed empty poll reads as plain, non-interactive rows.
 *
 * Tried: M3 `LinearProgressIndicator` / `LinearWavyProgressIndicator` result bars under each
 * option. Their stop-indicator dot at the track end read as a stray bullet on every row
 * (worst on a closed 0-vote poll: "0%" plus a dot per row), and the separate bar doubled
 * each row's height. The row fill replaces both.
 *
 * The meta line ("Poll · Anonymous · ⏱ 9:20") is plain muted text with one small glyph, so
 * the type and anonymity no longer compete with the question as two filled chips did.
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
    // Staged selection for multi-answer pre-vote, shared by the option rows (which toggle it)
    // and the footer's Vote button (which commits it). Reset whenever options identity changes
    // (e.g. after a successful commit lands new `isChosen` state via UpdateMessageContent)
    // so the local state never lags behind TDLib's authoritative view.
    val staged = remember(content.options) { mutableStateOf<Set<Int>>(emptySet()) }
    Column(modifier = Modifier.fillMaxWidth()) {
        // Header media (photo banner attached to the poll itself — TDLib Polls 2.0).
        content.headerMedia?.let { thumb ->
            PollHeaderMedia(thumb, onClick = onOpenInSource)
            Spacer(Modifier.height(12.dp))
        }
        PollMetaLine(content)
        Spacer(Modifier.height(4.dp))
        PollQuestion(content)
        if (content.description.text.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            PollDescription(content)
        }
        Spacer(Modifier.height(12.dp))
        PollOptionsList(content, voting, staged)
        PollFooter(content, voting, staged, onOpenInSource)
    }
}

@Composable
private fun PollHeaderMedia(thumb: TdMedia, onClick: () -> Unit) {
    // Standard in-feed media corner + hairline, same as a photo post.
    val bannerShape = MaterialTheme.shapes.medium
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(bannerShape)
            .mediaFrame(bannerShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        TdMediaImage(
            media = thumb,
            contentDescription = stringResource(R.string.media_desc_photo),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Quiet one-line header: a small type glyph (ballot for a poll, lamp in tertiary for a quiz)
 * and "Poll · Anonymous", followed by the live countdown when the poll has a close date.
 */
@Composable
private fun PollMetaLine(content: PostContent.Poll) {
    val cs = MaterialTheme.colorScheme
    val isQuiz = content.kind is PollKind.Quiz
    val typeLabel = stringResource(
        when {
            content.isClosed -> R.string.poll_state_closed
            isQuiz -> R.string.poll_type_quiz
            else -> R.string.poll_type_regular
        },
    )
    val label = if (content.isAnonymous) {
        "$typeLabel · ${stringResource(R.string.poll_anonymous_short)}"
    } else {
        typeLabel
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Symbol(
            name = if (isQuiz) "lightbulb" else "poll",
            tint = if (isQuiz) cs.tertiary else cs.primary,
            size = 16.dp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        PollCountdown(content)
    }
}

/**
 * Live-tick countdown. Subscribes to a 1 Hz tick only while composed and only when the
 * poll has a future closeDate; the tick recomposes this composable alone. Once `closeDate`
 * passes it shows a static "Closing…" until TDLib delivers the authoritative
 * `isClosed=true` a moment later. The last ten seconds switch to the error role.
 */
@Composable
private fun PollCountdown(content: PostContent.Poll) {
    if (content.closeDate <= 0 || content.isClosed) return
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
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
    val color = if (remaining in 1L..10L) cs.error else cs.onSurfaceVariant
    val style = MaterialTheme.typography.labelMedium
    Text(text = " · ", style = style, color = cs.onSurfaceVariant, maxLines = 1)
    Symbol(name = "timer", tint = color, size = 14.dp)
    Spacer(Modifier.width(2.dp))
    Text(text = label, style = style.tabularFigures(), color = color, maxLines = 1, softWrap = false)
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

/**
 * Whether the poll takes several answers — regular polls and quizzes alike (TDLib's
 * `poll.allows_multiple_answers` applies to both): checkboxes, staged picks and a "Vote"
 * commit instead of radios that commit on tap.
 */
internal fun allowsMultipleAnswers(content: PostContent.Poll): Boolean = when (val kind = content.kind) {
    is PollKind.Regular -> kind.allowsMultipleAnswers
    is PollKind.Quiz -> kind.allowsMultipleAnswers
}

@Composable
private fun PollOptionsList(content: PostContent.Poll, voting: PollVoting?, staged: MutableState<Set<Int>>) {
    val haptics = LocalHapticFeedback.current
    val multi = allowsMultipleAnswers(content)
    // Local val so Kotlin's smart-cast carries through the lambda capture below.
    val v = voting
    val canVote = v != null && !content.isClosed && !content.hasVoted
    val showResults = !canVote
    // A poll nobody voted in has nothing to chart: no percentages, no fill.
    val showNumbers = showResults && content.totalVotes > 0
    val reduced = rememberReducedMotion()
    val fillSpec: AnimationSpec<Float> =
        if (reduced) snap() else MaterialTheme.motionScheme.fastSpatialSpec()
    val quiz = content.kind as? PollKind.Quiz

    // The control is centred on the option's FIRST text line, so a three-line option keeps
    // its radio / checkbox (and its percentage) level with where the label starts.
    val bodyStyle = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    val firstLine = remember(bodyStyle, density) {
        val lh = bodyStyle.lineHeight
        val dp = if (lh.isSp) with(density) { lh.toDp() } else 24.dp
        if (dp < ControlSize) ControlSize else dp
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = if (canVote && !multi) Modifier.selectableGroup() else Modifier,
    ) {
        content.options.forEach { option ->
            val isStaged = option.index in staged.value
            val onTap: (() -> Unit)? = if (canVote && v != null) {
                {
                    if (multi) {
                        staged.value = if (isStaged) staged.value - option.index else staged.value + option.index
                    } else {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        v.onCommit(intArrayOf(option.index))
                    }
                }
            } else {
                null
            }
            PollOptionRow(
                option = option,
                quiz = quiz,
                showResults = showResults,
                showNumbers = showNumbers,
                isStaged = isStaged,
                multiSelect = multi,
                firstLine = firstLine,
                fillSpec = fillSpec,
                onTap = onTap,
            )
        }
    }
}

/** Result outcome of one row, driving its fill colour, trailing mark and TalkBack state. */
private enum class OptionOutcome { None, Chosen, Correct, Wrong }

@Composable
private fun PollOptionRow(
    option: PollOption,
    quiz: PollKind.Quiz?,
    showResults: Boolean,
    showNumbers: Boolean,
    isStaged: Boolean,
    multiSelect: Boolean,
    firstLine: Dp,
    fillSpec: AnimationSpec<Float>,
    onTap: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    val isCorrect = quiz != null && option.index in quiz.correctOptionIds
    val outcome = when {
        !showResults -> OptionOutcome.None
        quiz != null && option.isChosen && !isCorrect -> OptionOutcome.Wrong
        quiz != null && isCorrect -> OptionOutcome.Correct
        option.isChosen -> OptionOutcome.Chosen
        else -> OptionOutcome.None
    }
    // Fill discipline: the accent (primaryContainer) is reserved for the user's own pick;
    // quiz answers use tertiary (right) / error (wrong); everybody else's share is a
    // neutral onSurface veil so it never reads as "your choice".
    val fillColor = when (outcome) {
        OptionOutcome.Chosen -> cs.primaryContainer
        OptionOutcome.Correct -> cs.tertiaryContainer
        OptionOutcome.Wrong -> cs.errorContainer
        OptionOutcome.None -> cs.onSurface.copy(alpha = 0.08f)
    }
    val base by animateColorAsState(
        targetValue = if (isStaged && !showResults) cs.primaryContainer else contentBlockColor,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "pollRowBase",
    )
    val fill = animateFloatAsState(
        targetValue = if (showNumbers) option.percent.coerceIn(0, 100) / 100f else 0f,
        animationSpec = fillSpec,
        label = "pollRowFill",
    )

    val outcomeLabel = when (outcome) {
        OptionOutcome.Chosen -> stringResource(R.string.poll_option_your_vote)
        OptionOutcome.Correct -> if (option.isChosen) {
            "${stringResource(R.string.poll_option_your_vote)}, ${stringResource(R.string.poll_option_correct)}"
        } else {
            stringResource(R.string.poll_option_correct)
        }
        OptionOutcome.Wrong -> "${stringResource(R.string.poll_option_your_vote)}, " +
            stringResource(R.string.poll_option_wrong)
        OptionOutcome.None -> null
    }

    val interaction = when {
        onTap != null && multiSelect -> Modifier.toggleable(
            value = isStaged,
            role = Role.Checkbox,
            onValueChange = { onTap() },
        )
        onTap != null -> Modifier.selectable(selected = false, role = Role.RadioButton, onClick = onTap)
        outcomeLabel != null -> Modifier.semantics(mergeDescendants = true) { stateDescription = outcomeLabel }
        else -> Modifier.semantics(mergeDescendants = true) {}
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(OptionShape)
            .background(base)
            .drawBehind {
                // Draw-phase read: the fill animation redraws this layer only.
                val w = size.width * fill.value
                if (w > 0f) {
                    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f
                    drawRect(fillColor, topLeft = Offset(left, 0f), size = Size(w, size.height))
                }
            }
            .then(interaction)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (!showResults) {
            Box(Modifier.height(firstLine), contentAlignment = Alignment.Center) {
                PollControl(
                    multiSelect = multiSelect,
                    checked = isStaged,
                    pending = option.isBeingChosen,
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        option.thumb?.let { t ->
            val thumbShape = RoundedCornerShape(8.dp)
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(thumbShape)
                    // Small option thumbnail (rectangular photographic). Kept at 8 dp rather
                    // than shapes.small (12 dp): a 12 dp radius reads heavy on a 36 dp tile.
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
            style = MaterialTheme.typography.bodyLarge,
            color = cs.onSurface,
            fontWeight = if (outcome == OptionOutcome.None) null else FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (showResults && (outcome != OptionOutcome.None || showNumbers || option.isBeingChosen)) {
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier.height(firstLine),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (option.isBeingChosen) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else if (outcome != OptionOutcome.None) {
                    OutcomeMark(outcome)
                }
                if (showNumbers) {
                    Text(
                        text = "${option.percent}%",
                        style = MaterialTheme.typography.labelLarge.tabularFigures(),
                        fontWeight = FontWeight.SemiBold,
                        color = if (outcome == OptionOutcome.None) cs.onSurfaceVariant else cs.onSurface,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/**
 * Pre-vote control: a radio ring (single answer) or a rounded checkbox (multiple answers).
 * A pending vote (TDLib `isBeingChosen`) swaps the control for a small spinner.
 */
@Composable
private fun PollControl(multiSelect: Boolean, checked: Boolean, pending: Boolean) {
    val cs = MaterialTheme.colorScheme
    if (pending) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        return
    }
    if (multiSelect) {
        val boxShape = RoundedCornerShape(6.dp)
        Box(
            modifier = Modifier
                .size(ControlSize)
                .clip(boxShape)
                .then(
                    if (checked) {
                        Modifier.background(cs.primary)
                    } else {
                        Modifier.border(2.dp, cs.outline, boxShape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) DrawCheck(color = cs.onPrimary, size = 14.dp)
        }
    } else {
        Box(
            modifier = Modifier
                .size(ControlSize)
                .border(2.dp, cs.outline, CircleShape),
        )
    }
}

/** Trailing result mark: check on a primary / tertiary disc, cross on an error disc. */
@Composable
private fun OutcomeMark(outcome: OptionOutcome) {
    val cs = MaterialTheme.colorScheme
    val (container, content) = when (outcome) {
        OptionOutcome.Wrong -> cs.error to cs.onError
        OptionOutcome.Correct -> cs.tertiary to cs.onTertiary
        else -> cs.primary to cs.onPrimary
    }
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        if (outcome == OptionOutcome.Wrong) {
            Symbol(name = "close", tint = content, size = 14.dp)
        } else {
            DrawCheck(color = content, size = 12.dp)
        }
    }
}

/**
 * Hand-drawn checkmark. Used instead of a `sym_check.xml` drawable to keep the icon pack
 * lean — two strokes, drawn in the draw phase, coloured by the host theme.
 */
@Composable
private fun DrawCheck(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 2.dp.toPx()
        val p1 = Offset(w * 0.18f, h * 0.54f)
        val p2 = Offset(w * 0.42f, h * 0.78f)
        val p3 = Offset(w * 0.82f, h * 0.30f)
        drawLine(color = color, start = p1, end = p2, strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color = color, start = p2, end = p3, strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

/**
 * One footer slot under the options. Normally the vote count on the post's text edge
 * (tabular figures) with the poll's actions at the end: Retract (regular, revotable, voted,
 * open), Why? (quiz explanation, after answering) and Propose an option (taps through to
 * Telegram) — quiet text buttons, the poll body stays `quiet` in the intensity budget.
 *
 * A votable multi-answer poll puts its "Vote" button in the same slot instead of the count,
 * from the first frame: disabled (M3's quiet disabled colours, announced as disabled) until
 * at least one option is staged. Committing swaps it for the count + actions, which occupy
 * the same ≥ 48 dp slot, so neither staging nor voting changes the block's height.
 *
 * Tried: the Vote button as an extra row below the options, expanding in only once a row was
 * staged. In the reverse-layout channel feed the list is anchored at the bottom, so the
 * growing block pushed every option above it up by the button's height under the finger —
 * the second tap landed on a different option. Voting then collapsed the row again.
 */
@Composable
private fun PollFooter(
    content: PostContent.Poll,
    voting: PollVoting?,
    staged: MutableState<Set<Int>>,
    onOpenInSource: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .heightIn(min = 48.dp),
    ) {
        val multiVote = voting.takeIf { !content.isClosed && !content.hasVoted && allowsMultipleAnswers(content) }
        if (multiVote != null) {
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    multiVote.onCommit(staged.value.sorted().toIntArray())
                    staged.value = emptySet()
                },
                enabled = staged.value.isNotEmpty(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = OptionShape,
            ) {
                Symbol(name = "how_to_vote", size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.poll_action_vote))
            }
        } else {
            val totalLabel = if (content.totalVotes == 0) {
                stringResource(R.string.poll_total_votes_empty)
            } else {
                pluralStringResource(R.plurals.poll_total_votes_plural, content.totalVotes, content.totalVotes)
            }
            Text(
                text = totalLabel,
                style = MaterialTheme.typography.labelMedium.tabularFigures(),
                color = cs.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        // Retraction affordance for regular polls only (quiz polls cannot be re-voted).
        val regular = content.kind as? PollKind.Regular
        if (
            voting != null &&
            content.hasVoted &&
            !content.isClosed &&
            regular?.allowsRevoting == true
        ) {
            TextButton(onClick = { voting.onCommit(IntArray(0)) }) {
                Text(stringResource(R.string.poll_action_retract))
            }
        }

        // Quiz explanation — only when the explanation text exists AND the user has
        // already answered (TDLib reveals the explanation only post-answer).
        val quiz = content.kind as? PollKind.Quiz
        if (quiz != null && content.hasVoted && quiz.explanation.text.isNotBlank()) {
            TextButton(onClick = onOpenInSource) {
                Symbol(name = "lightbulb", tint = cs.tertiary, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.poll_action_view_explanation))
            }
        }

        // "Propose an option" — Polls 2.0 lets viewers add new options. Hortay is a reader
        // app, so this taps through to Telegram.
        if (content.canAddOption && !content.isClosed) {
            TextButton(onClick = onOpenInSource) {
                Text(stringResource(R.string.poll_action_add_option))
            }
        }
    }
}
