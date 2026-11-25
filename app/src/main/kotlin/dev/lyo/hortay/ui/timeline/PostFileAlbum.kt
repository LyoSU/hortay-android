package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostContent

/** Gap between two members of a file group — the files read as one list, not separate cards. */
private val FILE_ALBUM_GAP = 8.dp

/**
 * A media group of documents or audio tracks ([PostContent.FileAlbum]): every member in
 * posting order, stacked, each followed by its own caption — Telegram's layout, where a
 * bot's caption on the last file sits under that file. Each audio member is its own
 * [AudioBlock], so it plays independently through the shared inline audio player.
 *
 * [translation] is the post's translated caption; it replaces the first captioned member's
 * caption, the one [PostContent.FileAlbum.captionPlain] leads with.
 */
@Composable
internal fun FileAlbumBlock(
    content: PostContent.FileAlbum,
    captionLimit: Int,
    translation: FormattedText?,
    onOpenInSource: () -> Unit,
) {
    val translatedId = remember(content) {
        content.items.firstOrNull { it.file.caption.text.isNotBlank() }?.messageId
    }
    content.items.forEachIndexed { index, item ->
        key(item.messageId) {
            if (index > 0) Spacer(Modifier.height(FILE_ALBUM_GAP))
            val itemTranslation = translation.takeIf { item.messageId == translatedId }
            when (val file = item.file) {
                is PostContent.Document -> DocumentBlock(file, captionLimit, itemTranslation, onOpenInSource)
                is PostContent.Audio -> AudioWithCaption(file, captionLimit, itemTranslation, onOpenInSource)
            }
        }
    }
}

/** An audio track with the caption the poster gave it, below the player (audio has no caption-above). */
@Composable
internal fun AudioWithCaption(
    content: PostContent.Audio,
    captionLimit: Int,
    translation: FormattedText?,
    onOpenInSource: () -> Unit,
) {
    AudioBlock(content, onOpenInSource)
    MediaCaption(translation ?: content.caption, captionLimit, above = false, show = true)
}
