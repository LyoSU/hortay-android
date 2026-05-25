package dev.lyo.hortay.data.archive

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.drinkless.tdlib.TdApi

/**
 * Builds [TdlibContentMeta] from a live [TdApi.MessageContent] at capture time.
 *
 * Single extraction path: every snapshot in the archive comes from a real
 * [TdApi.MessageContent] (UpdateMessageContent's `newContent`, or the initial
 * mapping in handleNewMessage). A previous variant that derived meta from a
 * mapped [dev.lyo.hortay.data.TimelinePost] was removed — it stripped entities
 * and produced a different `content_hash` for the same logical post, which
 * spammed the archive with "phantom" edits visible only as duplicated rows.
 */
object TdlibContentMetaExtractor {

    fun extract(
        content: TdApi.MessageContent,
        forwardJson: String? = null,
        replyJson: String? = null,
    ): TdlibContentMeta {
        val (text, entities) = textWithEntities(content)
        return TdlibContentMeta(
            text = text,
            entitiesJson = encodeEntities(entities),
            mediaSummaryJson = mediaSummary(content),
            pollJson = pollSnapshot(content),
            forwardJson = forwardJson,
            replyJson = replyJson,
            mediaRef = extractMediaRef(content),
        )
    }

    /**
     * Extracts an [ArchivedMediaRef] from a media-bearing [TdApi.MessageContent].
     * Returns null for text-only / poll-without-banner / call / venue / contact /
     * location / sticker content — all carry no downloadable media file.
     *
     * Stickers are intentionally excluded: their lifecycle is tied to the
     * StickerSet, not the message; storing per-snapshot copies would balloon the
     * archive on busy reaction streams.
     *
     * The largest [TdApi.PhotoSize] is selected for photos because that's the one
     * Telegram serves on viewer open — capturing a thumb would be useless for the
     * "what did the deleted post look like" UX.
     */
    private fun extractMediaRef(c: TdApi.MessageContent): ArchivedMediaRef? = when (c) {
        is TdApi.MessagePhoto -> {
            val largest = c.photo.sizes.maxByOrNull { it.width.toLong() * it.height } ?: return null
            buildMediaRef(
                type = "photo", file = largest.photo,
                width = largest.width, height = largest.height,
                durationMs = 0L, mimeType = "image/jpeg", fileName = null,
                minithumb = c.photo.minithumbnail?.data,
            )
        }
        is TdApi.MessageVideo -> buildMediaRef(
            type = "video", file = c.video.video,
            width = c.video.width, height = c.video.height,
            durationMs = c.video.duration.toLong() * 1000,
            mimeType = c.video.mimeType, fileName = c.video.fileName.ifEmpty { null },
            minithumb = c.video.minithumbnail?.data,
        )
        is TdApi.MessageAnimation -> buildMediaRef(
            type = "animation", file = c.animation.animation,
            width = c.animation.width, height = c.animation.height,
            durationMs = c.animation.duration.toLong() * 1000,
            mimeType = c.animation.mimeType, fileName = c.animation.fileName.ifEmpty { null },
            minithumb = c.animation.minithumbnail?.data,
        )
        is TdApi.MessageDocument -> buildMediaRef(
            type = "document", file = c.document.document,
            width = 0, height = 0, durationMs = 0L,
            mimeType = c.document.mimeType, fileName = c.document.fileName.ifEmpty { null },
            minithumb = c.document.minithumbnail?.data,
        )
        is TdApi.MessageAudio -> buildMediaRef(
            type = "audio", file = c.audio.audio,
            width = 0, height = 0,
            durationMs = c.audio.duration.toLong() * 1000,
            mimeType = c.audio.mimeType, fileName = c.audio.fileName.ifEmpty { null },
            minithumb = null,
        )
        is TdApi.MessageVoiceNote -> buildMediaRef(
            type = "voice", file = c.voiceNote.voice,
            width = 0, height = 0,
            durationMs = c.voiceNote.duration.toLong() * 1000,
            mimeType = c.voiceNote.mimeType, fileName = null,
            minithumb = null,
        )
        is TdApi.MessageVideoNote -> buildMediaRef(
            type = "videoNote", file = c.videoNote.video,
            width = c.videoNote.length, height = c.videoNote.length,
            durationMs = c.videoNote.duration.toLong() * 1000,
            mimeType = null, fileName = null,
            minithumb = c.videoNote.minithumbnail?.data,
        )
        else -> null
    }

    private fun buildMediaRef(
        type: String,
        file: TdApi.File,
        width: Int, height: Int,
        durationMs: Long, mimeType: String?, fileName: String?,
        minithumb: ByteArray?,
    ): ArchivedMediaRef = ArchivedMediaRef(
        type = type,
        width = width, height = height,
        durationMs = durationMs,
        sizeBytes = if (file.size > 0) file.size else file.expectedSize,
        mimeType = mimeType, fileName = fileName,
        remoteId = file.remote?.id.orEmpty(),
        uniqueId = file.remote?.uniqueId.orEmpty(),
        minithumbBytes = minithumb,
        localArchiveSha = null, // populated by ArchivedMediaStore.copyIfAvailable
    )

    private fun textWithEntities(c: TdApi.MessageContent): Pair<String, Array<TdApi.TextEntity>> = when (c) {
        is TdApi.MessageText -> c.text.text to c.text.entities
        is TdApi.MessagePhoto -> c.caption.text to c.caption.entities
        is TdApi.MessageVideo -> c.caption.text to c.caption.entities
        is TdApi.MessageAnimation -> c.caption.text to c.caption.entities
        is TdApi.MessageDocument -> c.caption.text to c.caption.entities
        is TdApi.MessageAudio -> c.caption.text to c.caption.entities
        is TdApi.MessageVoiceNote -> c.caption.text to c.caption.entities
        is TdApi.MessageVideoNote -> "" to emptyArray()
        is TdApi.MessagePoll -> c.poll.question.text to c.poll.question.entities
        else -> "" to emptyArray()
    }

    private fun encodeEntities(entities: Array<TdApi.TextEntity>): String {
        val arr = entities.map { e ->
            buildJsonObject {
                put("offset", JsonPrimitive(e.offset))
                put("length", JsonPrimitive(e.length))
                put("type", JsonPrimitive(e.type::class.simpleName ?: "Unknown"))
            }
        }
        return Json.encodeToString(ListSerializer(JsonObject.serializer()), arr)
    }

    private fun mediaSummary(c: TdApi.MessageContent): String? = when (c) {
        is TdApi.MessagePhoto -> {
            val largest = c.photo.sizes.lastOrNull()
            buildJsonObject {
                put("type", JsonPrimitive("photo"))
                put("w", JsonPrimitive(largest?.width ?: 0))
                put("h", JsonPrimitive(largest?.height ?: 0))
            }.toString()
        }
        is TdApi.MessageVideo -> buildJsonObject {
            put("type", JsonPrimitive("video"))
            put("w", JsonPrimitive(c.video.width))
            put("h", JsonPrimitive(c.video.height))
            put("durationMs", JsonPrimitive(c.video.duration.toLong() * 1000))
        }.toString()
        is TdApi.MessageAnimation -> buildJsonObject {
            put("type", JsonPrimitive("animation"))
            put("w", JsonPrimitive(c.animation.width))
            put("h", JsonPrimitive(c.animation.height))
        }.toString()
        is TdApi.MessageDocument -> buildJsonObject {
            put("type", JsonPrimitive("document"))
            put("fileName", JsonPrimitive(c.document.fileName))
        }.toString()
        is TdApi.MessageAudio -> buildJsonObject {
            put("type", JsonPrimitive("audio"))
            put("title", JsonPrimitive(c.audio.title))
            put("durationMs", JsonPrimitive(c.audio.duration.toLong() * 1000))
        }.toString()
        is TdApi.MessageVoiceNote -> buildJsonObject {
            put("type", JsonPrimitive("voice"))
            put("durationMs", JsonPrimitive(c.voiceNote.duration.toLong() * 1000))
        }.toString()
        is TdApi.MessageVideoNote -> buildJsonObject {
            put("type", JsonPrimitive("videoNote"))
            put("durationMs", JsonPrimitive(c.videoNote.duration.toLong() * 1000))
        }.toString()
        else -> null
    }

    private fun pollSnapshot(c: TdApi.MessageContent): String? = (c as? TdApi.MessagePoll)?.let { mp ->
        val options = mp.poll.options.map { o ->
            buildJsonObject {
                put("text", JsonPrimitive(o.text.text))
                put("voterCount", JsonPrimitive(o.voterCount))
            }
        }
        buildJsonObject {
            put("question", JsonPrimitive(mp.poll.question.text))
            put("isAnonymous", JsonPrimitive(mp.poll.isAnonymous))
            put("options", Json.parseToJsonElement(
                Json.encodeToString(ListSerializer(JsonObject.serializer()), options)))
        }.toString()
    }
}
