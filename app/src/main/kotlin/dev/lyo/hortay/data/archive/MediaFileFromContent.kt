package dev.lyo.hortay.data.archive

import org.drinkless.tdlib.TdApi

/**
 * Pulls the downloadable [TdApi.File] handle out of a [TdApi.MessageContent].
 *
 * Mirrors the type-by-type dispatch in [TdlibContentMetaExtractor.extractMediaRef] —
 * kept as a thin separate helper so [ArchiveRepository] stays TDLib-free (the file
 * copy is invoked by capture-site code in PostsRepository / CommentsRepository
 * before they hand the resulting SHA to the repository).
 *
 * Returns `null` for text-only / poll-without-banner / sticker / venue / contact
 * content — types that carry no downloadable media file.
 */
object MediaFileFromContent {

    fun extract(content: TdApi.MessageContent): TdApi.File? = when (content) {
        is TdApi.MessagePhoto -> content.photo.sizes
            .maxByOrNull { it.width.toLong() * it.height }?.photo
        is TdApi.MessageVideo -> content.video.video
        is TdApi.MessageAnimation -> content.animation.animation
        is TdApi.MessageDocument -> content.document.document
        is TdApi.MessageAudio -> content.audio.audio
        is TdApi.MessageVoiceNote -> content.voiceNote.voice
        is TdApi.MessageVideoNote -> content.videoNote.video
        else -> null
    }
}
