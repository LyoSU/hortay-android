package dev.lyo.telread.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import dev.lyo.telread.data.DownloadPriority
import dev.lyo.telread.data.TdMedia

/**
 * 3-tier avatar pyramid. Each tier paints over the previous so we never show an empty circle:
 *
 *   1. **Initial letter** (always rendered) — synchronous, never blocks.
 *   2. **Minithumb** (~40×40 inline JPEG from TDLib's `Minithumbnail.data`, ships in the same
 *      payload as the post/sender — zero extra requests).
 *   3. **Small file** (160×160) — downloaded with [DownloadPriority.Avatar] so it never
 *      contends with media. Replaces the minithumb once decoded.
 *
 * If both `thumb` and `fileId` are null, we render only the letter on a colored disc — that's
 * the case for users / chats with no profile photo at all.
 */
@Composable
fun TdAvatar(
    name: String,
    thumb: ByteArray?,
    fileId: Int?,
    size: Dp,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.primaryContainer,
    textColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            style = textStyle,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
        )
        if (thumb != null) {
            MinithumbImage(bytes = thumb, contentDescription = name)
        }
        if (fileId != null) {
            TdMediaImage(
                media = TdMedia(fileId = fileId, width = 0, height = 0, minithumbBytes = null),
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                placeholderColor = null,
                showProgress = false,
                priority = DownloadPriority.Avatar,
            )
        }
    }
}
