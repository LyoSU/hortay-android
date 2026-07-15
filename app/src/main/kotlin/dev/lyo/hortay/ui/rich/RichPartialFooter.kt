package dev.lyo.hortay.ui.rich

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.SCREEN_MOUNT_GRACE_MS
import dev.lyo.hortay.data.posts.RichFullFetchStatus
import dev.lyo.hortay.ui.media.rememberDeferredLoading

/**
 * Loading / retry affordance shown BELOW the already-rendered partial rich body on the
 * post-detail surface while its full document is fetched on demand
 * ([dev.lyo.hortay.data.posts.PostsRepository.ensureFullRichMessage]).
 *
 * The partial content stays in place; this only fills the truncation point:
 *  - fetching → a few skeleton text lines, gated behind [rememberDeferredLoading] with the
 *    screen-mount grace so a fast fetch paints zero skeleton;
 *  - failed → a compact "couldn't load" row with a Retry action ([onRetry] re-invokes the fetch).
 *
 * On success the whole footer is removed by the caller (the post is no longer partial) and the
 * appended content grows the single post item downward, leaving the already-read upper part put.
 *
 * [key] resets the skeleton grace per anchor (message identity).
 */
@Composable
internal fun RichPartialFooter(
    status: RichFullFetchStatus?,
    onRetry: () -> Unit,
    key: Any,
    modifier: Modifier = Modifier,
) {
    when (status) {
        RichFullFetchStatus.Failed -> RichFullError(onRetry, modifier)
        // Fetching, or the brief window before the fetch marks itself Fetching — both are "loading".
        else -> {
            val show = rememberDeferredLoading(pending = true, key = key, graceMs = SCREEN_MOUNT_GRACE_MS)
            if (show) RichLoadingLines(modifier)
        }
    }
}

@Composable
private fun RichLoadingLines(modifier: Modifier) {
    // Band metrics mirror SkeletonFeed's rows (surfaceVariant, 6 dp rounded, 15 dp bands at a
    // ~22 dp line pitch) so the placeholder reads as the same "loading text" vocabulary.
    val color = MaterialTheme.colorScheme.surfaceVariant
    val band = RoundedCornerShape(6.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.height(15.dp).fillMaxWidth().clip(band).background(color))
        Box(Modifier.height(15.dp).fillMaxWidth(0.92f).clip(band).background(color))
        Box(Modifier.height(15.dp).fillMaxWidth(0.6f).clip(band).background(color))
    }
}

@Composable
private fun RichFullError(onRetry: () -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.rich_full_post_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.rich_full_post_retry),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onRetry)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
