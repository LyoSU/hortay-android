package dev.lyo.hortay.data.archive

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.drinkless.tdlib.TdApi
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.TimelinePost

/**
 * Builds [TdlibContentMeta] from a live [TdApi.MessageContent] at capture time.
 *
 * `entitiesJson` / `mediaSummaryJson` / `pollJson` use plain Kotlinx JSON so the
 * schema can evolve without rewriting the ProtoBuf wrapper. forward/reply summaries
 * are NOT in MessageContent — caller supplies them if available.
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
        )
    }

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

    /**
     * Snapshot a live in-memory post — used to capture the BEFORE state when an
     * UpdateMessageContent arrives, so the archive contains the original version
     * the user actually saw, not just the post-edit content. Lossy on rich
     * formatting (Hortay's [PostContent] doesn't round-trip TDLib's
     * `Array<TextEntity>` — the spans are kept as Hortay-native types). For diff
     * purposes the text + media-summary fidelity is what matters.
     */
    fun extractFromPost(post: TimelinePost): TdlibContentMeta {
        val content = post.content
        return TdlibContentMeta(
            text = content.captionPlain,
            entitiesJson = "[]",
            mediaSummaryJson = postContentMediaSummary(content),
            pollJson = null,
            forwardJson = null,
            replyJson = null,
        )
    }

    private fun postContentMediaSummary(c: PostContent): String? = when (c) {
        is PostContent.Text -> null
        is PostContent.PhotoAlbum -> buildJsonObject {
            put("type", JsonPrimitive("photo"))
            put("count", JsonPrimitive(c.items.size))
        }.toString()
        is PostContent.Video -> buildJsonObject {
            put("type", JsonPrimitive("video"))
            put("durationMs", JsonPrimitive(c.durationSec.toLong() * 1000))
        }.toString()
        is PostContent.Animation -> buildJsonObject {
            put("type", JsonPrimitive("animation"))
        }.toString()
        is PostContent.Document -> buildJsonObject {
            put("type", JsonPrimitive("document"))
        }.toString()
        is PostContent.Audio -> buildJsonObject {
            put("type", JsonPrimitive("audio"))
        }.toString()
        is PostContent.VoiceNote -> buildJsonObject {
            put("type", JsonPrimitive("voice"))
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
