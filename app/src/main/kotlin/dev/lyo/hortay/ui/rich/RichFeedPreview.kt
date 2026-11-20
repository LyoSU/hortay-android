package dev.lyo.hortay.ui.rich

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import dev.lyo.hortay.R
import dev.lyo.hortay.data.rich.RichDocument
import dev.lyo.hortay.ui.text.ClampedContent
import dev.lyo.hortay.ui.text.ShowMoreToggle

/**
 * Feed-card rendering of a rich message: the projected [RichMessageMode.FeedPreview] body under
 * a post-wide pixel clamp, dissolving into a single "Read full post" affordance whenever there is
 * more to read past the fold.
 *
 * "More past the fold" is any of three conditions:
 *  1. the pixel clamp actually trimmed the projected prefix (detected inside [ClampedContent]);
 *  2. [RichDocument.previewProjection] dropped top-level blocks (tables / media / sections below
 *     the feed budget never composed);
 *  3. the document is [RichDocument.isFull] == false — a truncated server prefix whose remainder
 *     is fetched on demand when the post is opened.
 *
 * Conditions 2–3 can hold even when the visible prefix fits the clamp, so they are surfaced via
 * [ClampedContent]'s `forceAffordance`. All three lead to the SAME single affordance — there is
 * no separate technical "load more" wording. Tapping it runs the exact expand action the
 * clamp's own toggle uses (open the post via `LocalShowFullPost`, or expand in place where there
 * is no detail surface), so it is never a parallel navigation path.
 *
 * The affordance is the same quiet start-aligned accent link an ordinary collapsed post ends
 * with ([ShowMoreToggle]), only labelled "Read full post", at the same spacing. It used to be a
 * full-width tonal pill with a chevron (`TonalActionRow`): two neighbouring feed cards then
 * ended with two different controls for the same idea, and the pill was the heaviest element of
 * a card whose intensity budget is quiet. The tonal row stays right inside tables and code
 * blocks, where it closes a container rather than a post.
 *
 * [clampStyle] drives only the clamp's height budget (kept as the feed body style so a rich post
 * collapses to the same height as a text post); the blocks render at their own [RichTypography] scale.
 */
@Composable
internal fun RichFeedPreview(
    document: RichDocument,
    maxLines: Int,
    clampStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    // Project once here and thread it into the body, so the document isn't walked twice per card
    // (the body would otherwise re-project). Reference inequality: previewProjection returns the
    // SAME list instance when nothing is dropped, so `!==` is a cheap "blocks were trimmed" test.
    val projected = remember(document) { document.previewProjection() }
    val projectedOrPartial = remember(document, projected) {
        !document.isFull || projected !== document.blocks
    }
    val fadeColor = MaterialTheme.colorScheme.background
    ClampedContent(
        key = document,
        maxLines = maxLines,
        style = clampStyle,
        fadeColor = fadeColor,
        forceAffordance = projectedOrPartial,
        affordance = { onExpand ->
            ShowMoreToggle(onClick = onExpand, text = stringResource(R.string.rich_read_full_post))
        },
        content = {
            RichMessageBody(
                document,
                modifier = modifier,
                mode = RichMessageMode.FeedPreview,
                projectedBlocks = projected,
            )
        },
    )
}
