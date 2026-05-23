package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import dev.lyo.hortay.data.web.WebForwardSource
import dev.lyo.hortay.data.web.WebMedia
import dev.lyo.hortay.data.web.WebPost
import dev.lyo.hortay.data.web.WebPreview
import dev.lyo.hortay.data.web.WebReaction
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-writer capture API for the post archive.
 *
 * Threading: `capture*` methods are `suspend` and use an internal mutex to serialize
 * writes, so calls from PostsRepository's CAS-loop dispatcher and from WebFeedSource's
 * IO scope race safely. Reads (`observe*`) use SQLDelight's `Query.asFlow()` and do not
 * hold the mutex.
 *
 * Capture *must* be called BEFORE the upstream pipeline mutates its state, so the old
 * snapshot is observed. The lambda passed to `_posts.update {}` in PostsRepository must
 * remain a pure function of the snapshot — side effects (DB writes) belong outside.
 */
class ArchiveRepository(
    private val db: ArchiveDatabase,
    private val settings: StateFlow<ArchiveSettings>,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val writeMutex = Mutex()
    private val writeCounter = AtomicInteger(0)
    private val capEvictionEvery = 100

    private val _events = MutableSharedFlow<ArchiveEvent>(extraBufferCapacity = 64)
    val events: Flow<ArchiveEvent> = _events

    /**
     * Capture a TDLib content snapshot (a VERSION row).
     *
     * @param meta extracted by the caller from `TdApi.MessageContent`. Repository is
     *   intentionally TDLib-free for testability.
     * @param minithumb optional. Pass the raw `Minithumbnail.data` for singles or use
     *   [MinithumbCompositor.composite] for albums.
     */
    suspend fun captureTdlibVersion(
        chat: ChatRef,
        messageKey: String,
        albumKey: String?,
        editedAtMs: Long?,
        meta: TdlibContentMeta,
        minithumb: ByteArray? = null,
        isComment: Boolean = false,
    ) {
        val s = settings.first()
        if (!s.enabled || !s.captureEdits) return
        if (chat in s.excludedChats) return

        val blob = ContentBlobCodec.encode(meta)
        val hash = ContentBlobCodec.hash(blob)
        writeMutex.withLock {
            if (isDuplicate(chat, messageKey, hash)) return
            insertSnapshot(
                chat = chat, messageKey = messageKey, albumKey = albumKey,
                kind = SnapshotKind.VERSION, editedAtMs = editedAtMs,
                contentKind = "tdlib", blob = blob, hash = hash,
                textPreview = meta.textPreview,
                minithumb = minithumb, deletedKeys = null, isComment = isComment,
            )
            maybeEvictByCap()
        }
        _events.tryEmit(ArchiveEvent.Captured(chat, messageKey))
    }

    /**
     * Capture a TDLib delete event (a DELETED marker row).
     *
     * Albums: pass all `messageKeys` together. The repository writes a single composite
     * DELETED row anchored at the first key, with the full list in `deleted_msg_keys`.
     */
    suspend fun captureTdlibDelete(
        chat: ChatRef,
        messageKeys: List<String>,
        albumKey: String?,
        isComment: Boolean,
    ) {
        val s = settings.first()
        if (!s.enabled || !s.captureDeletes) return
        if (chat in s.excludedChats) return
        if (messageKeys.isEmpty()) return

        val anchorKey = messageKeys.first()
        val deletedJson = Json.encodeToString(
            ListSerializer(String.serializer()),
            messageKeys,
        )
        writeMutex.withLock {
            val markerBlob = byteArrayOf(0)
            val markerHash = ContentBlobCodec.hash(markerBlob + deletedJson.toByteArray())
            if (isDuplicate(chat, anchorKey, markerHash)) return
            insertSnapshot(
                chat = chat, messageKey = anchorKey, albumKey = albumKey,
                kind = SnapshotKind.DELETED, editedAtMs = null,
                contentKind = "marker", blob = markerBlob, hash = markerHash,
                textPreview = "", minithumb = null,
                deletedKeys = deletedJson, isComment = isComment,
            )
            maybeEvictByCap()
        }
        _events.tryEmit(ArchiveEvent.Deleted(chat, messageKeys))
    }

    /**
     * Capture a guest-mode (t.me/s/) version snapshot. The previous `WebPost` is stored
     * as JSON — guest mode is read-only so we keep the full structure cheap to serialize.
     */
    suspend fun captureWebVersion(
        chat: ChatRef,
        messageKey: String,
        previous: WebPost,
        seenAtOverrideMs: Long? = null,
    ) {
        val s = settings.first()
        if (!s.enabled || !s.captureEdits) return
        if (chat in s.excludedChats) return

        val json = Json.encodeToString(JsonObject.serializer(), webPostToJson(previous))
        val blob = json.toByteArray(Charsets.UTF_8)
        val hash = ContentBlobCodec.hash(blob)
        writeMutex.withLock {
            if (isDuplicate(chat, messageKey, hash)) return
            db.postSnapshotQueries.insert(
                source_kind = chat.kind.name,
                source_key = chat.key,
                message_key = messageKey,
                album_key = null,
                kind = SnapshotKind.VERSION.name,
                seen_at_ms = seenAtOverrideMs ?: clock(),
                edited_at_ms = null,
                content_kind = "web",
                content_blob = blob,
                content_hash = hash,
                text_preview = previous.textHtml.replace(Regex("<[^>]+>"), "").take(200),
                media_minithumb = null,
                deleted_msg_keys = null,
                is_comment = 0,
            )
            maybeEvictByCap()
        }
        _events.tryEmit(ArchiveEvent.Captured(chat, messageKey))
    }

    /** Capture a guest-mode delete. */
    suspend fun captureWebDelete(chat: ChatRef, messageKey: String) {
        captureTdlibDelete(chat, listOf(messageKey), albumKey = null, isComment = false)
    }

    /** UPSERT the denormalised channel-index row. Call on every capture. */
    suspend fun upsertChannel(
        chat: ChatRef, title: String, handle: String?,
        photoMinithumb: ByteArray?, isVerified: Boolean,
    ) = writeMutex.withLock {
        val now = clock()
        db.transaction {
            db.archivedChannelQueries.upsertInsert(
                source_kind = chat.kind.name,
                source_key = chat.key,
                title = title, handle = handle,
                photo_minithumb = photoMinithumb,
                is_verified = if (isVerified) 1L else 0L,
                last_snapshot_at_ms = now,
            )
            db.archivedChannelQueries.upsertUpdate(
                title = title, handle = handle,
                photo_minithumb = photoMinithumb,
                is_verified = if (isVerified) 1L else 0L,
                last_snapshot_at_ms = now,
                source_kind = chat.kind.name, source_key = chat.key,
            )
        }
    }

    suspend fun clear() = writeMutex.withLock {
        db.transaction {
            db.postSnapshotQueries.clearAll()
            db.archivedChannelQueries.clearAll()
        }
    }

    // --- Read API ---

    fun observeRevisions(chat: ChatRef, messageKey: String): Flow<ImmutableList<PostSnapshot>> =
        db.postSnapshotQueries
            .selectRevisions(chat.kind.name, chat.key, messageKey)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map(::toDomain).toPersistentList() }

    fun observe(filter: ArchiveFilter): Flow<ImmutableList<PostSnapshot>> =
        db.postSnapshotQueries.selectAllForFilter(
            sourceKind = filter.chatKind?.name,
            kind = filter.kind?.name,
            isComment = filter.scope?.toIsCommentFlag(),
            query = filter.query?.let { "%$it%" },
        ).asFlow().mapToList(Dispatchers.IO)
            .map { rows -> rows.map(::toDomain).toPersistentList() }

    fun observeChannelIndex(): Flow<ImmutableList<ArchivedChannelEntry>> =
        db.archivedChannelQueries.countByChannel()
            .asFlow().mapToList(Dispatchers.IO)
            .map { rows ->
                rows.map { r ->
                    ArchivedChannelEntry(
                        chat = ChatRef(SourceKind.valueOf(r.source_kind), r.source_key),
                        title = r.title, handle = r.handle,
                        photoMinithumb = r.photo_minithumb,
                        snapshotCount = r.snapshot_count.toInt(),
                        lastSnapshotAtMs = r.last_snapshot_at_ms,
                    )
                }.toPersistentList()
            }

    suspend fun purge(ids: List<Long>) = writeMutex.withLock {
        db.postSnapshotQueries.deleteByIds(ids)
    }

    // --- Private helpers ---

    private fun isDuplicate(chat: ChatRef, messageKey: String, hash: String): Boolean {
        val last = db.postSnapshotQueries.latestForMessage(
            chat.kind.name, chat.key, messageKey,
        ).executeAsOneOrNull() ?: return false
        // Same hash: always deduplicate (re-capture of unchanged content).
        if (last.content_hash == hash) return true
        // Same hash within 500 ms: absorb rapid re-fire from upstream event fans.
        // Different hash — always write; this path also covers DELETED rows following
        // a VERSION row of the same message within the same millisecond (fixed-clock tests).
        return false
    }

    private fun insertSnapshot(
        chat: ChatRef, messageKey: String, albumKey: String?,
        kind: SnapshotKind, editedAtMs: Long?, contentKind: String,
        blob: ByteArray, hash: String, textPreview: String,
        minithumb: ByteArray?, deletedKeys: String?, isComment: Boolean,
    ) {
        db.postSnapshotQueries.insert(
            source_kind = chat.kind.name,
            source_key = chat.key,
            message_key = messageKey,
            album_key = albumKey,
            kind = kind.name,
            seen_at_ms = clock(),
            edited_at_ms = editedAtMs,
            content_kind = contentKind,
            content_blob = blob,
            content_hash = hash,
            text_preview = textPreview,
            media_minithumb = minithumb,
            deleted_msg_keys = deletedKeys,
            is_comment = if (isComment) 1L else 0L,
        )
    }

    private fun maybeEvictByCap() {
        val count = writeCounter.incrementAndGet()
        if (count % capEvictionEvery == 0) {
            val cap = settings.value.maxRecords
            if (cap != Int.MAX_VALUE) {
                db.postSnapshotQueries.deleteByCap(cap.toLong())
            }
        }
    }

    private fun toDomain(row: dev.lyo.hortay.data.archive.db.PostSnapshot): PostSnapshot {
        val content = when (row.content_kind) {
            "tdlib" -> ArchivedContent.Tdlib(ContentBlobCodec.decode(row.content_blob))
            "web" -> ArchivedContent.Web(
                webPostFromJson(
                    Json.decodeFromString(JsonObject.serializer(),
                        row.content_blob.toString(Charsets.UTF_8))
                )
            )
            else -> ArchivedContent.Tdlib(TdlibContentMeta(
                text = "", entitiesJson = "[]",
                mediaSummaryJson = null, pollJson = null,
                forwardJson = null, replyJson = null,
            ))
        }
        val deletedKeys = row.deleted_msg_keys?.let {
            Json.decodeFromString(ListSerializer(String.serializer()), it)
        } ?: emptyList()
        return PostSnapshot(
            id = row.id,
            chat = ChatRef(SourceKind.valueOf(row.source_kind), row.source_key),
            messageKey = row.message_key,
            albumKey = row.album_key,
            kind = SnapshotKind.valueOf(row.kind),
            seenAtMs = row.seen_at_ms,
            editedAtMs = row.edited_at_ms,
            content = content,
            mediaMinithumb = row.media_minithumb,
            deletedMessageKeys = deletedKeys.toPersistentList(),
            isComment = row.is_comment == 1L,
        )
    }

    /**
     * Serializes [WebPost] to a [JsonObject] without requiring [WebPost] to carry
     * `@Serializable`. [WebPost] fields use [ImmutableList] which lacks a built-in
     * serializer; the nested element types ([WebMedia], [WebPreview], etc.) are all
     * `@Serializable` and are encoded via their generated serializers.
     */
    private fun webPostToJson(post: WebPost): JsonObject = buildJsonObject {
        put("id", post.id)
        put("seq", post.seq)
        put("publishedAt", post.publishedAt)
        put("textHtml", post.textHtml)
        putJsonArray("media") {
            post.media.forEach { add(Json.encodeToJsonElement(WebMedia.serializer(), it)) }
        }
        post.webPreview?.let { put("webPreview", Json.encodeToJsonElement(WebPreview.serializer(), it)) }
        post.forwardedFrom?.let { put("forwardedFrom", Json.encodeToJsonElement(WebForwardSource.serializer(), it)) }
        post.views?.let { put("views", it) }
        putJsonArray("reactions") {
            post.reactions.forEach { add(Json.encodeToJsonElement(WebReaction.serializer(), it)) }
        }
    }

    /**
     * Deserializes a [JsonObject] produced by [webPostToJson] back into a [WebPost].
     * Missing optional fields default to empty/null.
     */
    private fun webPostFromJson(obj: JsonObject): WebPost {
        val mediaList = obj["media"]?.let { el ->
            Json.decodeFromJsonElement(ListSerializer(WebMedia.serializer()), el)
        } ?: emptyList()
        val reactionList = obj["reactions"]?.let { el ->
            Json.decodeFromJsonElement(ListSerializer(WebReaction.serializer()), el)
        } ?: emptyList()
        return WebPost(
            id = (obj["id"] as? JsonPrimitive)?.content ?: "",
            seq = (obj["seq"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            publishedAt = (obj["publishedAt"] as? JsonPrimitive)?.content ?: "",
            textHtml = (obj["textHtml"] as? JsonPrimitive)?.content ?: "",
            media = mediaList.toPersistentList(),
            webPreview = obj["webPreview"]?.let {
                Json.decodeFromJsonElement(WebPreview.serializer(), it)
            },
            forwardedFrom = obj["forwardedFrom"]?.let {
                Json.decodeFromJsonElement(WebForwardSource.serializer(), it)
            },
            views = (obj["views"] as? JsonPrimitive)?.content,
            reactions = reactionList.toPersistentList(),
        )
    }
}

sealed interface ArchiveEvent {
    data class Captured(val chat: ChatRef, val messageKey: String) : ArchiveEvent
    data class Deleted(val chat: ChatRef, val messageKeys: List<String>) : ArchiveEvent
}

@Immutable
data class ArchiveFilter(
    val chatKind: SourceKind? = null,
    val kind: SnapshotKind? = null,
    val scope: ArchiveScope? = null,
    val query: String? = null,
)

enum class ArchiveScope {
    POSTS, COMMENTS, ALL;
    fun toIsCommentFlag(): Long? = when (this) {
        POSTS -> 0L; COMMENTS -> 1L; ALL -> null
    }
}

@Immutable
data class ArchivedChannelEntry(
    val chat: ChatRef,
    val title: String,
    val handle: String?,
    val photoMinithumb: ByteArray?,
    val snapshotCount: Int,
    val lastSnapshotAtMs: Long,
)
