package dev.lyo.hortay.ui.rich

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.rich.RichDocument
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.text.ClampedContent

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
 * collapses to the same height as a text post); the blocks render at their own [RichType] scale.
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
    RichTonalAction(text = stringResource(R.string.rich_read_full_post), onClick = onClick)
}

/**
 * The shared low-profile tonal affordance the rich renderer uses to invite a deeper surface —
 * "Read full post ›" under a clamped feed body, "View full table ›" under a compact table
 * preview. A full-width `surfaceContainerHigh` row with a primary label and a trailing chevron
 * glyph (the chevron lives in the Compose row, never in the translated string).
 */
@Composable
internal fun RichTonalAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
            Symbol(
                name = "chevron_right",
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                size = 18.dp,
            )
        }
    }
}
