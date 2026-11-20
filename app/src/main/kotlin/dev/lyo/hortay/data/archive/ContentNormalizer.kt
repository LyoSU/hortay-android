package dev.lyo.hortay.data.archive

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
 *  - **entities** reduced to one canonical form — see [canonicalEntities].
 *
 * Changing this projection changes the hash of unchanged content. Rows already stored
 * keep their old `content_hash`, so every comparison against a stored row re-derives
 * the row's hash from its blob with the current projection before calling a mismatch a
 * new version — see [checkAgainstLatest].
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

    /** One entity of the canonical projection: what the hash and the revision sheet compare. */
    data class CanonicalEntity(val offset: Int, val length: Int, val type: String)

    /**
     * Returns deterministic bytes suitable as input to [ContentBlobCodec.hash].
     */
    fun canonicalBytes(meta: TdlibContentMeta): ByteArray =
        Json.encodeToString(JsonObject.serializer(), JsonObject(components(meta))).toByteArray(Charsets.UTF_8)

    /** [ContentBlobCodec.hash] of [canonicalBytes] — the `content_hash` a TDLib row is written with. */
    fun canonicalHash(meta: TdlibContentMeta): String = ContentBlobCodec.hash(canonicalBytes(meta))

    /**
     * The normalized components, keyed by [COMPONENTS] — the hash input, and what
     * [ContentDiff] compares component by component.
     */
    internal fun components(meta: TdlibContentMeta): Map<String, JsonElement> = linkedMapOf(
        TEXT to JsonPrimitive(meta.text),
        ENTITIES to JsonArray(
            canonicalEntities(meta.entitiesJson).map { e ->
                buildJsonObject {
                    put(ArchivedEntityKeys.OFFSET, JsonPrimitive(e.offset))
                    put(ArchivedEntityKeys.LENGTH, JsonPrimitive(e.length))
                    put(ArchivedEntityKeys.TYPE, JsonPrimitive(e.type))
                }
            },
        ),
        MEDIA to normalizeMedia(meta.mediaSummaryJson),
        POLL to normalizePoll(meta.pollJson),
        FORWARD to parseOrNull(meta.forwardJson),
        REPLY to parseOrNull(meta.replyJson),
    )

    /**
     * The formatting of a stored `entitiesJson` in one canonical form, so two lists TDLib
     * may hand out for the same formatting compare equal:
     *
     *  - **offset / length / type only.** The extractor later started storing render
     *    payloads too (link url, custom emoji id, mention user id, code language); hashing
     *    those would give an unchanged post a different hash from its own older row, and
     *    the next content-less `updateMessageEdited` (a reply-markup edit, a catch-up
     *    re-emit) would record a phantom version.
     *  - **No `MediaTimestamp`, no `BotCommand`.** TDLib computes their visibility per read,
     *    not per message version (td `MessageEntity.cpp` `get_text_entities_object`): a
     *    media timestamp is dropped while it exceeds `get_message_max_media_timestamp`,
     *    which for a text post is the duration of its link preview's media in the web-page
     *    cache, or of the replied-to media — both can be unknown at one read and known at the
     *    next. Bot commands are dropped by `need_skip_bot_commands`, which for a group
     *    (comments) depends on whether TDLib has loaded the chat's bot list yet. Neither is
     *    an author's formatting, so neither may decide whether a version is new.
     *  - **Style runs merged.** Bold / italic / underline / strikethrough / spoiler are
     *    TDLib's "splittable" entities: `fix_entities` keeps a valid list as-is but resplits
     *    an invalid one around links and custom emoji ("can merge some entities"), so the
     *    same styled range can arrive as one entity or as several touching pieces. Touching
     *    or overlapping same-type pieces are merged here; they render identically. Every
     *    other type (links, mentions, custom emoji, code, quotes) is NOT merged — two
     *    adjacent links are two links.
     *  - **Sorted** by offset, then length descending (an enclosing entity first), then
     *    type name, so list order never matters. That is TDLib's own order (`MessageEntity`
     *    `operator<`) except for its type-priority tie-break, so most rows hashed before
     *    this canonical form still hash the same.
     *
     * Malformed entries and empty ranges are dropped.
     */
    fun canonicalEntities(entitiesJson: String): List<CanonicalEntity> {
        val parsed = parseOrEmpty(entitiesJson).mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val offset = obj[ArchivedEntityKeys.OFFSET]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val length = obj[ArchivedEntityKeys.LENGTH]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val type = obj[ArchivedEntityKeys.TYPE]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (length <= 0 || offset < 0 || type in VOLATILE_TYPES) null else CanonicalEntity(offset, length, type)
        }
        val (styles, others) = parsed.partition { it.type in SPLITTABLE_TYPES }
        val merged = styles.groupBy { it.type }.flatMap { (type, runs) -> mergeRuns(type, runs) }
        return (others + merged).sortedWith(
            compareBy<CanonicalEntity> { it.offset }.thenByDescending { it.length }.thenBy { it.type },
        )
    }

    /** The raw stored entities as (offset, length, type), in stored order — diagnostics only. */
    internal fun rawEntities(entitiesJson: String): List<CanonicalEntity> = parseOrEmpty(entitiesJson).mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        CanonicalEntity(
            offset = obj[ArchivedEntityKeys.OFFSET]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
            length = obj[ArchivedEntityKeys.LENGTH]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
            type = obj[ArchivedEntityKeys.TYPE]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
        )
    }

    private fun mergeRuns(type: String, runs: List<CanonicalEntity>): List<CanonicalEntity> {
        val out = mutableListOf<CanonicalEntity>()
        var start = -1
        var end = -1
        for (r in runs.sortedBy { it.offset }) {
            if (start >= 0 && r.offset <= end) {
                end = maxOf(end, r.offset + r.length)
                continue
            }
            if (start >= 0) out += CanonicalEntity(start, end - start, type)
            start = r.offset
            end = r.offset + r.length
        }
        if (start >= 0) out += CanonicalEntity(start, end - start, type)
        return out
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

    internal const val TEXT = "text"
    internal const val ENTITIES = "entities"
    internal const val MEDIA = "media"
    internal const val POLL = "poll"
    internal const val FORWARD = "forward"
    internal const val REPLY = "reply"

    /** Component names, in hash order. */
    internal val COMPONENTS = listOf(TEXT, ENTITIES, MEDIA, POLL, FORWARD, REPLY)

    /** Entity types TDLib shows or hides per read — see [canonicalEntities]. */
    private val VOLATILE_TYPES = setOf("TextEntityTypeMediaTimestamp", "TextEntityTypeBotCommand")

    /** TDLib's splittable entity types (td `MessageEntity.cpp` `get_splittable_entities_mask`). */
    private val SPLITTABLE_TYPES = setOf(
        "TextEntityTypeBold",
        "TextEntityTypeItalic",
        "TextEntityTypeUnderline",
        "TextEntityTypeStrikethrough",
        "TextEntityTypeSpoiler",
    )
}
