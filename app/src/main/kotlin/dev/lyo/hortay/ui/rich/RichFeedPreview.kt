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
import dev.lyo.hortay.ui.text.TonalActionRow

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
 * no separate technical "load more" wording. Tapping the button runs the exact expand action the
 * clamp's own toggle uses (open the post via `LocalShowFullPost`, or expand in place where there
 * is no detail surface), so it is never a parallel navigation path.
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
    // Reference inequality: previewProjection returns the SAME list instance when nothing is
    // dropped, so `!==` is a cheap, allocation-free "blocks were trimmed" test.
    val projectedOrPartial = remember(document) {
        !document.isFull || document.previewProjection() !== document.blocks
    }
    val fadeColor = MaterialTheme.colorScheme.background
    ClampedContent(
        key = document,
        maxLines = maxLines,
        style = clampStyle,
        fadeColor = fadeColor,
        forceAffordance = projectedOrPartial,
        affordance = { onExpand -> RichReadFullButton(onClick = onExpand) },
        content = { RichMessageBody(document, modifier = modifier, mode = RichMessageMode.FeedPreview) },
    )
}

/** Low-profile tonal "Read full post ›" button. */
@Composable
private fun RichReadFullButton(onClick: () -> Unit) {
    TonalActionRow(text = stringResource(R.string.rich_read_full_post), onClick = onClick)
}
