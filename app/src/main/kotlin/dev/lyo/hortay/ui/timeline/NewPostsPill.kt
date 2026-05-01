package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdAvatar

/**
 * Floating pill that surfaces unrevealed feed updates Twitter-style: stacked channel
 * avatars on the left, count + label on the right. Tap reveals.
 */
@Composable
fun NewPostsPill(
    channels: List<ChannelBadge>,
    pendingCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (channels.isNotEmpty()) {
                AvatarStack(channels = channels)
                Spacer(Modifier.width(10.dp))
            }
            Symbol(name = "arrow_upward", size = 18.dp)
            Spacer(Modifier.width(6.dp))
            val ctx = LocalContext.current
            Text(
                text = newPostsLabel(ctx.resources, pendingCount),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun AvatarStack(channels: List<ChannelBadge>) {
    val borderColor = MaterialTheme.colorScheme.primary
    Box(modifier = Modifier.height(AVATAR_SIZE)) {
        // Render right-to-left so the first channel sits on top of the stack.
        channels.forEachIndexed { idx, ch ->
            Box(
                modifier = Modifier
                    .padding(start = (idx * AVATAR_OFFSET.value).dp)
                    .size(AVATAR_SIZE)
                    .zIndex((channels.size - idx).toFloat())
                    .clip(CircleShape)
                    .border(2.dp, borderColor, CircleShape),
            ) {
                TdAvatar(
                    name = ch.title,
                    thumb = ch.thumb,
                    fileId = ch.fileId,
                    size = AVATAR_SIZE,
                    textStyle = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * Plural form for "new post(s)". Defers to Android's CLDR-driven [android.content.res.Resources.getQuantityString]
 * — Ukrainian gets one/few/many forms, English gets one/other, both via the same plurals
 * resource. Counts above 99 are clamped to a static overflow label so transient state
 * flickers can't surface alarming numbers.
 */
private fun newPostsLabel(res: android.content.res.Resources, n: Int): String {
    if (n > 99) return res.getString(R.string.new_posts_overflow)
    return res.getQuantityString(R.plurals.new_posts, n, n)
}

private val AVATAR_SIZE = 28.dp
private val AVATAR_OFFSET = 18.dp
