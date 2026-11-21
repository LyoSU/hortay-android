package dev.lyo.hortay.ui.archive.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.lyo.hortay.R
import dev.lyo.hortay.data.archive.ArchiveEntry
import dev.lyo.hortay.data.archive.ArchiveEntryStatus
import dev.lyo.hortay.data.archive.ArchivedThumbnailCopy
import dev.lyo.hortay.ui.archive.ArchiveAvatar
import dev.lyo.hortay.ui.media.MinithumbImage
import dev.lyo.hortay.ui.media.TdAvatar
import dev.lyo.hortay.ui.theme.tabularFigures

/**
 * One archive browser row: channel avatar, channel name and time, a status line ("Deleted"
 * in the error colour, "Edited · N versions" neutral), up to two lines of the last captured
 * text, and a small thumbnail when the post had media (sharp from the archive's own copy
 * when it holds one, else the inline minithumb). Quiet by design — a list, not a feed:
 * no card, no tint, the status word carries the meaning.
 *
 * @param avatar the chat's live avatar when it is still known; null falls back to the
 *   minithumb the archive stored, then the initial letter.
 * @param timeLabel the event time, already formatted (the day is in the section header).
 */
@Composable
fun ArchiveEntryRow(
    entry: ArchiveEntry,
    avatar: ArchiveAvatar?,
    timeLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val title = entry.channelTitle
        ?: stringResource(if (entry.isComment) R.string.archive_row_comment else R.string.archive_unknown_channel)
    val statusText = when (entry.status) {
        ArchiveEntryStatus.Deleted -> stringResource(R.string.archive_row_kind_deleted)
        ArchiveEntryStatus.Edited -> pluralStringResource(
            R.plurals.archive_row_edited_versions, entry.versionCount, entry.versionCount,
        )
    }
    val status = if (entry.isComment && entry.channelTitle != null) {
        stringResource(R.string.archive_row_status_comment, statusText)
    } else {
        statusText
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.background)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TdAvatar(
            name = title,
            thumb = avatar?.minithumb ?: entry.channelPhotoMinithumb,
            fileId = avatar?.fileId,
            size = 40.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelMedium.tabularFigures(),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (entry.status == ArchiveEntryStatus.Deleted) scheme.error else scheme.onSurfaceVariant,
            )
            val preview = entry.textPreview.trim()
            when {
                preview.isNotEmpty() -> Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                entry.mediaMinithumb == null -> MutedLine(stringResource(R.string.archive_row_no_text))
            }
        }
        val thumb = entry.mediaMinithumb
        if (thumb != null) {
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(THUMBNAIL_SIZE)
                    .clip(RoundedCornerShape(10.dp))
                    .background(scheme.surfaceContainerHigh),
            ) {
                ThumbnailImage(minithumb = thumb, copy = entry.mediaCopy)
            }
        }
    }
}

/**
 * The row's thumbnail: the archive's sharp copy when it holds one, decoded at
 * [THUMBNAIL_SIZE] (a frame for video-like copies, see [archivedFileRequest]), over the
 * inline minithumb. The minithumb is the placeholder while the copy decodes and stays as
 * the picture when there is no copy or it fails to decode; it is dropped only once the
 * copy has drawn.
 */
@Composable
private fun ThumbnailImage(minithumb: ByteArray, copy: ArchivedThumbnailCopy?) {
    var copyShown by remember(copy) { mutableStateOf(false) }
    if (!copyShown) MinithumbImage(bytes = minithumb, contentDescription = null)
    if (copy == null) return
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { THUMBNAIL_SIZE.roundToPx() }
    val request = remember(copy, sizePx, context) {
        archivedFileRequest(context, copy.path, copy.isVideoFrame, sizePx)
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        onSuccess = { copyShown = true },
        onError = { copyShown = false },
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

private val THUMBNAIL_SIZE = 52.dp

@Composable
private fun MutedLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Day section header of the archive browser. */
@Composable
fun ArchiveDayHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}
