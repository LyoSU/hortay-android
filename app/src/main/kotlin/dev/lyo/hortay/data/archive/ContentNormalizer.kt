package dev.lyo.hortay.data.archive

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Builds a stable byte representation of a [TdlibContentMeta] for **content-hash**
 * computation only.
 *
 * The full meta is still stored verbatim in `content_blob` (used by
 * [PostRevisionSheet][dev.lyo.hortay.ui.archive.PostRevisionSheet] for rendering).
 * The hash, however, is computed over a **normalized** view that strips fields
 * which mutate WITHOUT a paired admin edit:
 *
 *  - **`pollJson.voterCount`** — incremented on every vote tick.
 *  - **`pollJson.isChosen`** — flips per-viewer.
 *  - **media `durationMs`** — Telegram occasionally re-encodes thumbnails which
 *    causes the duration field to round slightly differently. Rounded to 100 ms.
 *  - **forwardJson / replyJson** kept as-is (stable across non-edit mutations).
 *
 * Without this normalizer, [ArchiveRepository.isDuplicate] would have returned
 * `false` on every poll vote → new VERSION row → spam ("phantom edits"). Pairing
 * via [PendingEditBuffer] is the **primary** gate (real admin edits only); this
 * normalizer is the **second line** — even if a paired UME were to fire for a
 * non-content change (e.g. fact-check landing as `editDate > 0` — see tdlib/td#2294
 * for non-edit UME), the captured snapshot would still dedup against the
 * previous one.
 */
object ContentNormalizer {

    /**
     * Returns deterministic bytes suitable as input to [ContentBlobCodec.hash].
     */
    fun canonicalBytes(meta: TdlibContentMeta): ByteArray {
        val obj = buildJsonObject {
            put("text", JsonPrimitive(meta.text))
            put("entities", parseOrEmpty(meta.entitiesJson))
            put("media", normalizeMedia(meta.mediaSummaryJson))
            put("poll", normalizePoll(meta.pollJson))
            put("forward", parseOrNull(meta.forwardJson))
            put("reply", parseOrNull(meta.replyJson))
        }
        return Json.encodeToString(JsonObject.serializer(), obj).toByteArray(Charsets.UTF_8)
    }

    private fun parseOrEmpty(json: String): JsonArray {
        if (json.isBlank()) return JsonArray(emptyList())
        return runCatching {
            Json.parseToJsonElement(json) as? JsonArray ?: JsonArray(emptyList())
        }.getOrDefault(JsonArray(emptyList()))
    }

    private fun parseOrNull(json: String?): JsonPrimitive {
        // Forwards and replies are opaque to the normalizer — we treat the
        // JSON as a string token (presence/identity only, no field walk).
        return JsonPrimitive(json.orEmpty())
    }

    /**
     * Strips fluid fields from media descriptors. Photo / animation / document /
     * voice-note geometry is stable; durations are rounded down to the nearest 100 ms.
     */
    private fun normalizeMedia(mediaJson: String?): JsonObject {
        if (mediaJson.isNullOrBlank()) return JsonObject(emptyMap())
        val src = runCatching { Json.parseToJsonElement(mediaJson).jsonObject }.getOrNull()
            ?: return JsonObject(emptyMap())
        val type = src["type"]?.jsonPrimitive?.contentOrNull().orEmpty()
        val w = src["w"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull() ?: 0
        val h = src["h"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull() ?: 0
        val durMs = src["durationMs"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull() ?: 0L
        val fileName = src["fileName"]?.jsonPrimitive?.contentOrNull().orEmpty()
        val title = src["title"]?.jsonPrimitive?.contentOrNull().orEmpty()
        return buildJsonObject {
            put("type", JsonPrimitive(type))
            put("w", JsonPrimitive(w))
            put("h", JsonPrimitive(h))
            // Bucket to 100 ms so micro-jitters from server re-encoding don't change hash.
            put("durBucket", JsonPrimitive(durMs / 100L))
            if (fileName.isNotEmpty()) put("fileName", JsonPrimitive(fileName))
            if (title.isNotEmpty()) put("title", JsonPrimitive(title))
        }
    }

    /**
     * Polls: keep ONLY the question text + option texts. `voterCount`, `isChosen`,
     * `isAnonymous` (technically stable but tied to admin choice — included),
     * `correctOptionId` (quiz) all removed: these change on votes without admin
     * editing the post.
     */
    private fun normalizePoll(pollJson: String?): JsonObject {
        if (pollJson.isNullOrBlank()) return JsonObject(emptyMap())
        val src = runCatching { Json.parseToJsonElement(pollJson).jsonObject }.getOrNull()
            ?: return JsonObject(emptyMap())
        val question = src["question"]?.jsonPrimitive?.contentOrNull().orEmpty()
        val anon = src["isAnonymous"]?.jsonPrimitive?.contentOrNull() ?: "false"
        val optionsArr = runCatching { src["options"]?.jsonArray }.getOrNull() ?: JsonArray(emptyList())
        val optionTexts = optionsArr.map { el ->
            JsonPrimitive(el.jsonObject["text"]?.jsonPrimitive?.contentOrNull().orEmpty())
        }
        return buildJsonObject {
            put("question", JsonPrimitive(question))
            put("isAnonymous", JsonPrimitive(anon))
            put("optionTexts", JsonArray(optionTexts))
        }
    }

    private fun JsonPrimitive.contentOrNull(): String? = content
}
