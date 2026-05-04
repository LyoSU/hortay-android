package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.lyo.hortay.R
import dev.lyo.hortay.data.ReplyMediaKind

/**
 * Single source of truth for translating [ReplyMediaKind] into a localised label and a
 * Material Symbol name. Both PostCard's quote card (feed) and CommentsScreen's quote
 * card (discussion thread) call into here so the two surfaces never drift on icons or
 * wording. Kept in `ui.timeline` because PostCard is the canonical owner of the quote
 * design language; CommentsScreen reuses by import.
 */
@Composable
internal fun ReplyMediaKind.label(): String = when (this) {
    ReplyMediaKind.None -> ""
    ReplyMediaKind.Photo -> stringResource(R.string.reply_kind_photo)
    ReplyMediaKind.Video -> stringResource(R.string.reply_kind_video)
    ReplyMediaKind.Animation -> stringResource(R.string.reply_kind_animation)
    ReplyMediaKind.Document -> stringResource(R.string.reply_kind_document)
    ReplyMediaKind.Audio -> stringResource(R.string.reply_kind_audio)
    ReplyMediaKind.VoiceNote -> stringResource(R.string.reply_kind_voice)
    ReplyMediaKind.VideoNote -> stringResource(R.string.reply_kind_video_note)
    ReplyMediaKind.Sticker -> stringResource(R.string.reply_kind_sticker)
    ReplyMediaKind.Poll -> stringResource(R.string.reply_kind_poll)
}

internal fun ReplyMediaKind.symbolName(): String? = when (this) {
    ReplyMediaKind.None -> null
    ReplyMediaKind.Photo -> "image"
    ReplyMediaKind.Video -> "play_circle"
    ReplyMediaKind.Animation -> "gif_box"
    ReplyMediaKind.Document -> "description"
    ReplyMediaKind.Audio -> "audio_file"
    ReplyMediaKind.VoiceNote -> "mic"
    ReplyMediaKind.VideoNote -> "videocam"
    ReplyMediaKind.Sticker -> "sentiment_satisfied"
    ReplyMediaKind.Poll -> "poll"
}
