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
import kotlinx.coroutines.launch
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
    /**
     * Optional companion for storing media file bytes. Tests + legacy call sites
     * can pass null — capture still records a [TdlibContentMeta] with the
     * structured [ArchivedMediaRef] (remoteId, uniqueId, minithumb), but
     * `localArchiveSha` stays null so the revision sheet falls back to the
     * minithumb / Telegram-side re-download path.
     */
    private val mediaStore: ArchivedMediaStore? = null,
    /**
     * Background scope for fire-and-forget media-refcount releases triggered
     * by hot-path cap eviction. When null, hot-path orphans accumulate and
     * are reclaimed by the nightly [ArchiveSweep] instead — fine for tests
     * and call sites that prefer synchronous semantics.
     */
    private val releaseScope: kotlinx.coroutines.CoroutineScope? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val writeMutex = Mutex()
    private val writeCounter = AtomicInteger(0)
    private val capEvictionEvery = 100

    private val _events = MutableSharedFlow<ArchiveEvent>(extraBufferCapacity = 64)
    val events: Flow<ArchiveEvent> = _events

    /** Synchronous accessor for the `enabled` toggle. Equivalent to
     *  `settings.value.enabled` but lets call sites read the current state without
     *  taking a dependency on the [StateFlow] type. */
    fun isEnabled(): Boolean = settings.value.enabled

    /**
     * Capture the as-published baseline VERSION row — `seen_at_ms = originalDateMs`,
     * `edited_at_ms = null` — so the revision timeline anchors on "when the post
     * was published" rather than "when Hortay first observed it".
     *
     * Idempotency: skips when a baseline row already exists for [messageKey]
     * regardless of content hash. There is exactly one publication event per
     * message; the baseline is a fixed left anchor on the revision timeline.
     *
     * **A content-hash check would be wrong here.** Cold-start ingest fires
     * the baseline call via `scope.launch` after merging the post into `_posts`;
     * an `UpdateMessageEdited(editDate > 0)` can land for the same message
     * before the launched coroutine acquires `writeMutex`. The legacy unified
     * capture path then saw the edit row as "latest" and either dropped the
     * baseline as a dup (when content matched) or wrote it with `clock()` as
     * `seen_at_ms` (when it didn't) — both broke the timeline ordering. The
     * `selectBaselineForMessage` existence check is invariant under that race.
     *
     * @param meta extracted from `TdApi.MessageContent`. Repository stays TDLib-free.
     * @param originalDateMs the post's publication time in ms. Required —
     *   without it there is no "baseline" semantic, the caller should use
     *   [captureTdlibEdit] instead.
     */
    suspend fun captureTdlibBaseline(
        chat: ChatRef,
        messageKey: String,
        albumKey: String?,
        meta: TdlibContentMeta,
        originalDateMs: Long,
        minithumb: ByteArray? = null,
        isComment: Boolean = false,
    ) {
        val s = settings.first()
        if (!s.enabled || !s.captureEdits) return
        if (chat in s.excludedChats) return

        // [meta] already carries any pre-computed [ArchivedMediaRef.localArchiveSha]
        // (the caller invoked [ArchivedMediaStore.copyIfAvailable] before invoking
        // capture). Repository stays TDLib-free.
        val blob = ContentBlobCodec.encode(meta)
        // Hash a *normalized* projection so unstable counters (poll voterCount,
        // isChosen, etc.) cannot poison dedup. See [ContentNormalizer] KDoc.
        val hash = ContentBlobCodec.hash(ContentNormalizer.canonicalBytes(meta))
        writeMutex.withLock {
            val existing = db.postSnapshotQueries.selectBaselineForMessage(
                chat.kind.name, chat.key, messageKey,
            ).executeAsOneOrNull()
            if (existing != null) {
                // Baseline already captured — release the refcount bump from
                // copyIfAvailable so the media file row isn't leaked.
                meta.mediaRef?.localArchiveSha?.let { sha ->
                    runCatching { mediaStore?.releaseRef(sha) }
                }
                return
            }
            insertSnapshot(
                chat = chat, messageKey = messageKey, albumKey = albumKey,
                kind = SnapshotKind.VERSION, editedAtMs = null,
                contentKind = "tdlib", blob = blob, hash = hash,
                textPreview = meta.textPreview,
                minithumb = minithumb ?: meta.mediaRef?.minithumbBytes,
                deletedKeys = null, isComment = isComment,
                seenAtMs = originalDateMs,
            )
            maybeEvictByCap()
        }
        _events.tryEmit(ArchiveEvent.Captured(chat, messageKey))
    }

    /**
     * Capture an admin-edit VERSION row — `seen_at_ms = clock()`,
     * `edited_at_ms = editedAtMs`. One call per paired
     * `UpdateMessageContent` + `UpdateMessageEdited(editDate > 0)` event.
     *
     * Idempotency: skips when the latest row for this message already carries
     * the same content_hash (TDLib re-emitting the same pair on cold-start
     * catch-up / reconnect).
     *
     * `seen_at_ms` is **always** `clock()`. Even when the call happens to be
     * the first row for this message (no baseline ever captured, e.g. the
     * comment archive that intentionally skips baseline), the edit must keep
     * its clock-time stamp — otherwise a later baseline call would land at
     * publication time and the edit would precede it on the ASC timeline.
     */
    suspend fun captureTdlibEdit(
        chat: ChatRef,
        messageKey: String,
        albumKey: String?,
        editedAtMs: Long,
        meta: TdlibContentMeta,
        minithumb: ByteArray? = null,
        isComment: Boolean = false,
    ) {
        val s = settings.first()
        if (!s.enabled || !s.captureEdits) return
        if (chat in s.excludedChats) return

        val blob = ContentBlobCodec.encode(meta)
        val hash = ContentBlobCodec.hash(ContentNormalizer.canonicalBytes(meta))
        writeMutex.withLock {
            val existing = db.postSnapshotQueries.latestForMessage(
                chat.kind.name, chat.key, messageKey,
            ).executeAsOneOrNull()
            if (existing?.content_hash == hash) {
                meta.mediaRef?.localArchiveSha?.let { sha ->
                    runCatching { mediaStore?.releaseRef(sha) }
                }
                return
            }
            insertSnapshot(
                chat = chat, messageKey = messageKey, albumKey = albumKey,
                kind = SnapshotKind.VERSION, editedAtMs = editedAtMs,
                contentKind = "tdlib", blob = blob, hash = hash,
                textPreview = meta.textPreview,
                minithumb = minithumb ?: meta.mediaRef?.minithumbBytes,
                deletedKeys = null, isComment = isComment,
                seenAtMs = clock(),
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

    suspend fun clear() {
        writeMutex.withLock {
            db.transaction {
                db.postSnapshotQueries.clearAll()
                db.archivedChannelQueries.clearAll()
            }
        }
        // Outside writeMutex — mediaStore takes its own mutex; calling under
        // ours would invert lock order with copyIfAvailable's release path.
        runCatching { mediaStore?.clearAll() }
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

    suspend fun purge(ids: List<Long>) {
        if (ids.isEmpty()) return
        val shasToRelease: List<String> = writeMutex.withLock {
            val blobs = db.postSnapshotQueries.selectBlobsByIds(ids).executeAsList()
            db.postSnapshotQueries.deleteByIds(ids)
            blobs.mapNotNull { blob ->
                runCatching { ContentBlobCodec.decode(blob).mediaRef?.localArchiveSha }
                    .getOrNull()?.takeIf { it.isNotEmpty() }
            }
        }
        // Release outside the writeMutex: mediaStore takes its own mutex, and
        // nesting could deadlock with copyIfAvailable's release path.
        for (sha in shasToRelease) {
            runCatching { mediaStore?.releaseRef(sha) }
        }
    }

    /**
     * Stream the entire archive as JSON into [out].
     *
     * Uses [android.util.JsonWriter] so the in-memory cost stays bounded by
     * the largest single row (its base64-encoded blob), not by the total
     * archive size — a 5000-row archive with minithumbs can be ~25 MB which
     * previously sat in RAM as a single ByteArray for the duration of the
     * export.
     *
     * @return the number of records written.
     */
    suspend fun exportTo(out: java.io.OutputStream): Int = writeMutex.withLock {
        val writer = android.util.JsonWriter(out.writer(Charsets.UTF_8).buffered())
        var count = 0
        writer.beginObject()
        writer.name("version").value(1L)
        writer.name("exportedAtMs").value(clock())
        writer.name("records").beginArray()
        // executeAsList still materialises the row metadata, but the heavy
        // payload (blobs) is base64-encoded once per row and written straight
        // through — no second copy.
        val rows = db.postSnapshotQueries.selectAllForExport().executeAsList()
        for (r in rows) {
            writer.beginObject()
            writer.name("sourceKind").value(r.source_kind)
            writer.name("sourceKey").value(r.source_key)
            writer.name("messageKey").value(r.message_key)
            writer.name("kind").value(r.kind)
            writer.name("seenAtMs").value(r.seen_at_ms)
            writer.name("textPreview").value(r.text_preview)
            writer.name("contentKind").value(r.content_kind)
            writer.name("contentBlobBase64").value(
                android.util.Base64.encodeToString(r.content_blob, android.util.Base64.NO_WRAP),
            )
            r.media_minithumb?.let {
                writer.name("minithumbBase64").value(
                    android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP),
                )
            }
            writer.endObject()
            count++
        }
        writer.endArray()
        writer.endObject()
        writer.flush()
        count
    }

    suspend fun storageBytes(): Long = writeMutex.withLock {
        db.postSnapshotQueries.storageBytes().executeAsOne()
    }

    /**
     * Emits every TDLib-mode DELETED snapshot the archive has captured, joined with
     * the most recent VERSION snapshot for the same message (the pre-delete content)
     * and the denormalised channel metadata. Used by PostsRepository to reconstruct
     * "ghost" tombstone posts in the feed on cold start — TDLib doesn't return
     * deleted messages, so without these we'd silently lose every deleted post on
     * relaunch even though the archive still holds the snapshot.
     *
     * Implementation: single `LEFT JOIN` SQL query ([selectTombstonesJoined]) so
     * each flow emission is O(deletedRows) over one cursor instead of the previous
     * N+M synchronous SELECTs per row (1000+ extra queries on a busy archive).
     */
    fun observeTdlibTombstones(): kotlinx.coroutines.flow.Flow<ImmutableList<TombstoneRecord>> =
        db.postSnapshotQueries.selectTombstonesJoined()
            .asFlow().mapToList(Dispatchers.IO)
            .map { rows -> rows.mapNotNull(::buildTombstoneFromJoinedRow).toPersistentList() }

    /**
     * Emits a `(chatId, messageId) → revisionCount` map. Used to seed
     * [TimelinePost.revisionCount] on cold start so the EditedChip survives a
     * relaunch — TDLib hands us posts with no archive metadata, and we'd
     * otherwise have to wait for the next edit to repopulate the counter.
     *
     * `revisionCount == COUNT(VERSION) - 1`: every archived message starts
     * with a baseline VERSION (Phase 4 — captureBaselineSnapshot on first
     * ingest), so the edit count is total rows minus that baseline. SQL
     * already filters cnt > 0 via HAVING; Kotlin filters cnt >= 1 defensively.
     */
    fun observeTdlibRevisionCounts(): kotlinx.coroutines.flow.Flow<Map<Pair<Long, Long>, Int>> =
        db.postSnapshotQueries.selectTdlibVersionCounts()
            .asFlow().mapToList(Dispatchers.IO)
            .map { rows ->
                val out = HashMap<Pair<Long, Long>, Int>(rows.size)
                rows.forEach { r ->
                    val chatId = r.source_key.toLongOrNull()
                    val msgId = r.message_key.toLongOrNull()
                    if (chatId != null && msgId != null && r.cnt >= 1L) {
                        out[chatId to msgId] = r.cnt.toInt()
                    }
                }
                out
            }

    private fun buildTombstoneFromJoinedRow(
        row: dev.lyo.hortay.data.archive.db.SelectTombstonesJoined,
    ): TombstoneRecord? {
        val chatId = row.d_source_key.toLongOrNull() ?: return null
        val deletedKeys = row.d_deleted_msg_keys
            ?.let { runCatching { Json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
            ?: listOf(row.d_message_key)
        val allMessageIds = deletedKeys.mapNotNull { it.toLongOrNull() }
        val primaryId = allMessageIds.firstOrNull() ?: return null

        val meta = row.v_content_blob
            ?.let { runCatching { ContentBlobCodec.decode(it) }.getOrNull() }

        return TombstoneRecord(
            chatId = chatId,
            primaryMessageId = primaryId,
            allMessageIds = allMessageIds,
            deletedAtMs = row.d_seen_at_ms,
            originalSeenAtMs = row.v_seen_at_ms ?: row.d_seen_at_ms,
            text = meta?.text.orEmpty(),
            channelTitle = row.c_title.orEmpty(),
            channelHandle = row.c_handle,
            channelPhotoMinithumb = row.c_photo_minithumb,
            isVerified = (row.c_is_verified ?: 0L) == 1L,
        )
    }

    // --- Private helpers ---

    private fun isDuplicate(chat: ChatRef, messageKey: String, hash: String): Boolean {
        val last = db.postSnapshotQueries.latestForMessage(
            chat.kind.name, chat.key, messageKey,
        ).executeAsOneOrNull() ?: return false
        return last.content_hash == hash
    }

    private fun insertSnapshot(
        chat: ChatRef, messageKey: String, albumKey: String?,
        kind: SnapshotKind, editedAtMs: Long?, contentKind: String,
        blob: ByteArray, hash: String, textPreview: String,
        minithumb: ByteArray?, deletedKeys: String?, isComment: Boolean,
        seenAtMs: Long = clock(),
    ) {
        db.postSnapshotQueries.insert(
            source_kind = chat.kind.name,
            source_key = chat.key,
            message_key = messageKey,
            album_key = albumKey,
            kind = kind.name,
            seen_at_ms = seenAtMs,
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
        if (count % capEvictionEvery != 0) return
        val cap = settings.value.maxRecords
        if (cap == Int.MAX_VALUE) return
        // Snapshot soon-evicted blobs, DELETE inside the mutex (we're already
        // holding writeMutex via the calling capture*), then release refs in
        // a fire-and-forget coroutine outside our lock to avoid inverting
        // mediaStore's own mutex order.
        val store = mediaStore
        val rs = releaseScope
        if (store == null || rs == null) {
            db.postSnapshotQueries.deleteByCap(cap.toLong())
            return
        }
        val blobs = db.postSnapshotQueries.selectBlobsByCap(cap.toLong()).executeAsList()
        db.postSnapshotQueries.deleteByCap(cap.toLong())
        if (blobs.isEmpty()) return
        rs.launch {
            for (blob in blobs) {
                val sha = runCatching {
                    ContentBlobCodec.decode(blob).mediaRef?.localArchiveSha
                }.getOrNull()
                if (!sha.isNullOrEmpty()) {
                    runCatching { store.releaseRef(sha) }
                }
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

/**
 * Denormalised record built from a DELETED [PostSnapshot] row + the most recent
 * VERSION snapshot for the same message + the cached channel metadata. Enough to
 * reconstruct a minimal "ghost" feed card after TDLib forgets the message.
 */
@Immutable
data class TombstoneRecord(
    val chatId: Long,
    /** Anchor message id (first member of an album, or the standalone post id). */
    val primaryMessageId: Long,
    /** Full set of deleted ids (album members), or just `[primaryMessageId]` for solo. */
    val allMessageIds: List<Long>,
    val deletedAtMs: Long,
    /** When Hortay first saw a VERSION of this message — used as the ghost's `date`. */
    val originalSeenAtMs: Long,
    /** Last captured text content (caption or message body). Empty when no VERSION was captured. */
    val text: String,
    val channelTitle: String,
    val channelHandle: String?,
    val channelPhotoMinithumb: ByteArray?,
    val isVerified: Boolean,
)
