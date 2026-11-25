package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import dev.lyo.hortay.data.archive.db.ArchivedMessage
import dev.lyo.hortay.data.archive.db.LatestForMessage
import dev.lyo.hortay.data.archive.db.SelectForMessageKey
import dev.lyo.hortay.data.archive.db.SelectTombstones
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * Storage model (schema 3, see `ArchivedMessage.sq` / `MessageVersion.sq`): one
 * `ArchivedMessage` per message, one `MessageVersion` per distinct observed state, ordered
 * by an explicit sort key (Telegram's edit date, else the publication time, else the
 * observation time). Deletion is the message's `deleted_at_ms`, recorded only for
 * messages the archive holds a version of. Media files are referenced by
 * `MessageVersion.media_sha` and reclaimed by [ArchivedMediaStore.sweepOrphans].
 *
 * Threading: `capture*` methods are `suspend` and use an internal mutex to serialize
 * writes, so calls from the [ArchiveRecorder] consumer and from WebFeedSource's IO scope
 * race safely. Reads (`observe*`) use SQLDelight's `Query.asFlow()` and do not hold the
 * mutex.
 *
 * Capture *must* be called BEFORE the upstream pipeline mutates its state, so the old
 * snapshot is observed. The lambda passed to `_posts.update {}` in PostsRepository must
 * remain a pure function of the snapshot — side effects (DB writes) belong outside.
 */
class ArchiveRepository(
    private val db: ArchiveDatabase,
    private val settings: StateFlow<ArchiveSettings>,
    /**
     * Optional companion for storing media file bytes. Tests can pass null — capture
     * still records a [TdlibContentMeta] with the structured [ArchivedMediaRef] (remoteId,
     * uniqueId, minithumb), but `localArchiveSha` stays null so the revision sheet falls
     * back to the minithumb / Telegram-side re-download path. Used here only by [clear].
     */
    private val mediaStore: ArchivedMediaStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Capture decision journal ([ArchiveJournal]). TDLib-mode decisions are recorded by
     * [ArchiveRecorder]; this repository records only the guest-mode [captureWebVersion]
     * decisions itself, wipes the journal in [clear] and appends it to [exportTo].
     */
    private val journal: ArchiveJournal? = null,
    /**
     * False when `archive.db` could not be opened and [GuardedSqlDriver] serves an empty
     * in-memory stand-in: captures are then skipped as DISABLED instead of writing data
     * that vanishes on exit. Forces the open, so it is read only on capture paths (the
     * recorder's consumer, IO), never in the handlers' enqueue-side gates.
     */
    private val available: () -> Boolean = { true },
    /**
     * Removes the archive's real storage — `archive.db` with its -wal / -shm / -journal
     * files and the copied media directory. Called by [clear] only while [available] is
     * false; see [wipeArchiveStorage].
     */
    private val wipeStorage: () -> Unit = {},
) {

    internal val writeMutex = Mutex()
    private val writeCounter = AtomicInteger(0)
    private val capEvictionEvery = 100

    private val _events = MutableSharedFlow<ArchiveEvent>(extraBufferCapacity = 64)
    val events: Flow<ArchiveEvent> = _events

    /** Synchronous accessor for the `enabled` toggle. Equivalent to
     *  `settings.value.enabled` but lets call sites read the current state without
     *  taking a dependency on the [StateFlow] type. */
    fun isEnabled(): Boolean = settings.value.enabled

    /**
     * True when a baseline / edit VERSION capture for [chat] would be written under the
     * current settings — the cheap enqueue-side gate (no database access). A media copy
     * made for a capture that is then skipped is harmless: an unreferenced file is
     * reclaimed by [ArchivedMediaStore.sweepOrphans].
     */
    fun capturesEditsFor(chat: ChatRef): Boolean {
        val s = settings.value
        return s.enabled && s.captureEdits && chat !in s.excludedChats
    }

    /**
     * True when a deletion in [chat] would be recorded under the current settings and the
     * archive database is open — the feed's cheap, synchronous gate for keeping a deleted
     * post as a tombstone (no database access; [ArchiveSettings.keepsDeletionsFor] on the
     * in-memory settings snapshot).
     */
    fun keepsDeletionsFor(chat: ChatRef): Boolean = settings.value.keepsDeletionsFor(chat) && available()

    /** Why a VERSION capture for [chat] would be skipped under [s], or null when it would run. */
    private fun versionGate(s: ArchiveSettings, chat: ChatRef): ArchiveDecision? = when {
        !s.enabled || !s.captureEdits -> ArchiveDecision.DISABLED
        chat in s.excludedChats -> ArchiveDecision.EXCLUDED
        !available() -> ArchiveDecision.DISABLED
        else -> null
    }

    /** [versionGate] against the current settings — the recorder's pre-work check. */
    internal fun versionGate(chat: ChatRef): ArchiveDecision? = versionGate(settings.value, chat)

    /** See [planTdlibIngest] (top level, IngestPlan.kt) — this binds it to [db]. */
    internal fun planTdlibIngest(
        chat: ChatRef,
        messageKey: String,
        originalDateMs: Long,
        editedAtMs: Long?,
        allowBaseline: Boolean,
    ): IngestPlan? = planTdlibIngest(
        db.archivedMessageQueries, db.messageVersionQueries, versionGate(chat),
        chat, messageKey, originalDateMs, editedAtMs, allowBaseline,
    )

    /**
     * Record an edit the archive missed live — the message came back through ingest (cold
     * start, history load, channel open) with an `editDate` newer than the latest stored
     * version and different content (channel difference too long, app closed through the
     * edit, a dropped live capture). Written with origin [VersionOrigin.RECONCILED] and
     * `edit_date_ms = editedAtMs`, appended after the latest version.
     *
     * Re-validated under `writeMutex` against the latest version: same content → DUP_HASH
     * (stored hash, or the version re-hashed — [checkAgainstLatest]), an edit date not newer
     * than the latest version's → STALE, a deleted message → STALE.
     */
    suspend fun captureTdlibReconcile(
        chat: ChatRef,
        messageKey: String,
        albumKey: String?,
        editedAtMs: Long,
        meta: TdlibContentMeta,
        isComment: Boolean = false,
    ): CaptureOutcome {
        versionGate(settings.first(), chat)?.let { return CaptureOutcome(it) }
        val blob = ContentBlobCodec.encode(meta)
        val hash = ContentNormalizer.canonicalHash(meta)
        val outcome = writeMutex.withLock {
            val message = messageRow(chat, messageKey)
            val latest = message?.let { latestVersion(it.id) }
            if (message == null || latest == null) {
                return@withLock CaptureOutcome(ArchiveDecision.STALE, hash, null, DETAIL_NOT_ARCHIVED)
            }
            if (message.deleted_at_ms != null) {
                return@withLock CaptureOutcome(ArchiveDecision.STALE, hash, latest.content_hash, DETAIL_DELETED)
            }
            val check = checkAgainstLatest(db.messageVersionQueries, latest, hash, meta)
            val latestEdit = latest.edit_date_ms
            when {
                check.isDup -> {
                    val attached = attachMediaIfSame(db.messageVersionQueries, latest, hash, meta, check)
                    val detail = withMediaDetail(check.detail, attached)
                    CaptureOutcome(ArchiveDecision.DUP_HASH, hash, latest.content_hash, detail, check.diff)
                }
                latestEdit != null && editedAtMs <= latestEdit ->
                    CaptureOutcome(ArchiveDecision.STALE, hash, latest.content_hash, DETAIL_NO_NEWER_EDIT, check.diff)
                else -> {
                    insertVersion(
                        chat = chat, messageKey = messageKey, albumKey = albumKey, isComment = isComment,
                        publishedAtMs = null, sortAtMs = appendSort(editedAtMs, latest), editDateMs = editedAtMs,
                        origin = VersionOrigin.RECONCILED, contentKind = CONTENT_KIND_TDLIB, blob = blob, hash = hash,
                        textPreview = meta.textPreview, mediaSha = meta.mediaRef?.localArchiveSha,
                        minithumb = meta.mediaRef?.minithumbBytes,
                    )
                    maybeEvictByCap()
                    CaptureOutcome(ArchiveDecision.WRITTEN, hash, latest.content_hash, diff = check.diff)
                }
            }
        }
        if (outcome.decision == ArchiveDecision.WRITTEN) _events.tryEmit(ArchiveEvent.Captured(chat, messageKey))
        return outcome
    }

    /**
     * Attach the media copy of a re-ingest that was skipped without writing (`no_newer_edit`)
     * to the message's latest version, when that version is the same state — see
     * [attachMediaIfSame]. Never writes a version; a deleted message or a gated chat is left alone.
     *
     * @return true when the reference was written.
     */
    internal suspend fun attachSkippedMedia(chat: ChatRef, messageKey: String, meta: TdlibContentMeta): Boolean {
        if (versionGate(settings.first(), chat) != null) return false
        val hash = ContentNormalizer.canonicalHash(meta)
        return writeMutex.withLock {
            val message = messageRow(chat, messageKey)?.takeIf { it.deleted_at_ms == null }
            attachMediaIfSame(db.messageVersionQueries, message?.let { latestVersion(it.id) }, hash, meta)
        }
    }

    /**
     * Capture the first-observed state of a message from ingest: origin
     * [VersionOrigin.PUBLISHED] (`editDate == 0`, sorted at the publication time) or
     * [VersionOrigin.FIRST_SEEN_EDITED] ([priorEditedAtMs] set, sorted at that edit date).
     * Either way `published_at_ms = originalDateMs`.
     *
     * Idempotency: skipped when the message already has a version sorted at-or-before this
     * state's place on the timeline ([baselineSortOf]), regardless of content hash: that
     * version is this state or an earlier one. This handles three cases in one rule:
     *  - re-ingest of the same post: the stored baseline sits exactly there → skip;
     *  - a later edit already recorded (sorted at its newer edit date) → the baseline
     *    still inserts AHEAD of it on the timeline;
     *  - a pre-edited post whose live edit was recorded first at the same edit date → skip,
     *    no duplicate of the same server version.
     *
     * **A content-hash check would be wrong here.** Ingest enqueues the baseline only
     * after its suspending album coalesce + `_posts` merge, while the
     * `UpdateMessageEdited(editDate > 0)` handler enqueues its edit immediately — so even
     * through the single ordered [ArchiveRecorder] the edit can be processed BEFORE the
     * baseline of the same message. A hash-keyed "latest version" check then saw the edit
     * as latest and either dropped the baseline as a dup (when content matched) or appended
     * it after the edit (when it didn't) — both broke the timeline. The sort-position rule
     * is invariant under that race: the baseline's sort key is the publication time (or
     * its older edit date), which orders it before the edit no matter which was written
     * first. An edit newer than the stored state that this rule skips is picked up by
     * [captureTdlibReconcile] instead (see [planTdlibIngest]).
     *
     * @param meta extracted from `TdApi.MessageContent`. Repository stays TDLib-free.
     * @param originalDateMs the post's publication time in ms.
     * @param priorEditedAtMs the message's edit date when it was first observed already
     *   edited (`Message.editDate > 0` on first ingest); null when as published. Without a
     *   version for such posts, their later deletion had nothing to show.
     */
    suspend fun captureTdlibBaseline(
        chat: ChatRef,
        messageKey: String,
        albumKey: String?,
        meta: TdlibContentMeta,
        originalDateMs: Long,
        minithumb: ByteArray? = null,
        isComment: Boolean = false,
        priorEditedAtMs: Long? = null,
    ): CaptureOutcome {
        // Settings can flip between the caller's [capturesEditsFor] gate and here; a media
        // file copied for a skipped capture is reclaimed by the orphan sweep.
        versionGate(settings.first(), chat)?.let { return CaptureOutcome(it) }
        val blob = ContentBlobCodec.encode(meta)
        // Hash a *normalized* projection so unstable counters (poll voterCount,
        // isChosen, etc.) cannot poison dedup. See [ContentNormalizer] KDoc.
        val hash = ContentNormalizer.canonicalHash(meta)
        val sortAt = baselineSortOf(originalDateMs, priorEditedAtMs)
        writeMutex.withLock {
            val message = messageRow(chat, messageKey)
            val minSort = message?.let { db.messageVersionQueries.minSortForMessage(it.id).executeAsOne().min_sort }
            if (minSort != null && minSort <= sortAt) {
                // Not written, but a media copy this capture made can still belong to the
                // latest version when that is the same state (attachMediaIfSame).
                val latest = if (meta.mediaRef?.localArchiveSha.isNullOrEmpty()) null else message?.let { latestVersion(it.id) }
                val attached = attachMediaIfSame(db.messageVersionQueries, latest, hash, meta)
                val detail = withMediaDetail(DETAIL_BASELINE_PRESENT, attached)
                return CaptureOutcome(ArchiveDecision.DUP_HASH, hash, latest?.content_hash, detail)
            }
            insertVersion(
                chat = chat, messageKey = messageKey, albumKey = albumKey, isComment = isComment,
                publishedAtMs = originalDateMs, sortAtMs = sortAt, editDateMs = priorEditedAtMs,
                origin = if (priorEditedAtMs == null) VersionOrigin.PUBLISHED else VersionOrigin.FIRST_SEEN_EDITED,
                contentKind = CONTENT_KIND_TDLIB, blob = blob, hash = hash, textPreview = meta.textPreview,
                mediaSha = meta.mediaRef?.localArchiveSha, minithumb = minithumb ?: meta.mediaRef?.minithumbBytes,
            )
            maybeEvictByCap()
        }
        _events.tryEmit(ArchiveEvent.Captured(chat, messageKey))
        return CaptureOutcome(ArchiveDecision.WRITTEN, hash)
    }

    /**
     * Capture an admin-edit version — origin [VersionOrigin.LIVE_EDIT],
     * `edit_date_ms = editedAtMs`, appended after the latest version. One call per
     * `UpdateMessageEdited(editDate > 0)` the recorder resolved to content.
     *
     * Idempotency: skips when the latest version already carries the same content
     * (TDLib re-emitting the same pair on cold-start catch-up / reconnect), and when the
     * edit date is not newer than the latest version's ([editSkip]).
     *
     * Also the first version of a message the archive never baselined (comments are
     * captured only on edit): its sort key is still its edit date, so a baseline written
     * later (sorted at the publication time) lands before it.
     */
    suspend fun captureTdlibEdit(
        chat: ChatRef,
        messageKey: String,
        albumKey: String?,
        editedAtMs: Long,
        meta: TdlibContentMeta,
        minithumb: ByteArray? = null,
        isComment: Boolean = false,
    ): CaptureOutcome {
        versionGate(settings.first(), chat)?.let { return CaptureOutcome(it) }

        val blob = ContentBlobCodec.encode(meta)
        val hash = ContentNormalizer.canonicalHash(meta)
        writeMutex.withLock {
            val latest = messageRow(chat, messageKey)?.let { latestVersion(it.id) }
            val check = latest?.let { checkAgainstLatest(db.messageVersionQueries, it, hash, meta) }
            // Out-of-order guard. Edit captures used to be one `scope.launch` each, with
            // suspension points before this mutex (media copy on IO, the GetMessage
            // fallback), so two edits landing close together — or a catch-up burst
            // after reconnect — could reach here in the wrong order. Comparing only the
            // hash against the latest row then wrote the older content AFTER the newer
            // one, and the next content-less UME (reply-markup edit, re-emit) wrote the
            // newer content again: three versions for two edits, alternating text.
            // [ArchiveRecorder] now serialises TDLib captures in update order and reads
            // content + edit date together, but a reconcile from ingest can still land a
            // newer version first; a capture whose edit date is strictly older than the
            // latest recorded version's is stale by construction and is dropped. One with
            // the same edit date is the same server version — see [editSkip].
            editSkip(latest, check, editedAtMs)?.let { (decision, detail) ->
                // A skipped re-emit of the stored state may still carry its first archived media copy.
                val attached = check != null && attachMediaIfSame(db.messageVersionQueries, latest, hash, meta, check)
                return CaptureOutcome(decision, hash, latest?.content_hash, withMediaDetail(detail, attached), check?.diff)
            }
            insertVersion(
                chat = chat, messageKey = messageKey, albumKey = albumKey, isComment = isComment,
                publishedAtMs = null, sortAtMs = appendSort(editedAtMs, latest), editDateMs = editedAtMs,
                origin = VersionOrigin.LIVE_EDIT, contentKind = CONTENT_KIND_TDLIB, blob = blob, hash = hash,
                textPreview = meta.textPreview, mediaSha = meta.mediaRef?.localArchiveSha,
                minithumb = minithumb ?: meta.mediaRef?.minithumbBytes,
            )
            maybeEvictByCap()
            _events.tryEmit(ArchiveEvent.Captured(chat, messageKey))
            return CaptureOutcome(ArchiveDecision.WRITTEN, hash, latest?.content_hash, diff = check?.diff)
        }
    }

    /**
     * Smart-grouped delete capture: ungrouped delete events come in as a flat list of
     * `messageKeys`; this looks each one up to recover its `album_key`, then records one
     * [captureTdlibDelete] per album bucket (members without a known album are recorded
     * one per key).
     *
     * Why it exists. Cold-start catch-up — when TDLib emits a pile of
     * `UpdateDeleteMessages` from `getChannelDifference` for posts the user never scrolled
     * to — has no live `_posts` entries to read `mediaAlbumId` from. Routing through this
     * method lets the archive recover album grouping from what it stored, so a deleted
     * 5-photo album is one decision (and one tombstone) instead of five.
     */
    suspend fun captureTdlibDeleteSmart(
        chat: ChatRef,
        messageKeys: List<String>,
        isComment: Boolean,
    ): List<DeleteOutcome> {
        if (messageKeys.isEmpty()) return emptyList()
        // Lookup album_key per message under writeMutex so a concurrent capture can't move
        // the key under us mid-grouping. SELECT only; the writes happen in captureTdlibDelete.
        val byAlbum: Map<String?, List<String>> = writeMutex.withLock {
            messageKeys.groupBy { key -> messageRow(chat, key)?.album_key }
        }
        val outcomes = ArrayList<DeleteOutcome>(byAlbum.size)
        for ((albumKey, keys) in byAlbum) {
            if (albumKey == null) {
                for (k in keys) {
                    val keyList = listOf(k)
                    outcomes += DeleteOutcome(keyList, captureTdlibDelete(chat, keyList, albumKey = null, isComment = isComment))
                }
            } else {
                outcomes += DeleteOutcome(keys, captureTdlibDelete(chat, keys, albumKey = albumKey, isComment = isComment))
            }
        }
        return outcomes
    }

    /**
     * Capture a TDLib delete event: `deleted_at_ms = now` on every message of
     * [messageKeys] the archive holds (an album: pass all members together).
     *
     * Only held messages are marked: a deletion of a message the archive never held has
     * nothing to show or restore, and creates nothing. Catch-up `UpdateDeleteMessages` from
     * busy groups the reader is a member of name ~100 such messages per cold start. When
     * none is held the outcome is NO_CONTENT / [DETAIL_NEVER_ARCHIVED] (logcat only in the
     * journal); when every held one is already deleted it is DUP_HASH.
     *
     * @param isComment kept for the journal's callers; a message's comment flag is stored
     *   with its versions.
     */
    @Suppress("UnusedParameter")
    suspend fun captureTdlibDelete(
        chat: ChatRef,
        messageKeys: List<String>,
        albumKey: String?,
        isComment: Boolean,
    ): CaptureOutcome {
        val s = settings.first()
        if (!s.enabled || !s.captureDeletes) return CaptureOutcome(ArchiveDecision.DISABLED)
        if (chat in s.excludedChats) return CaptureOutcome(ArchiveDecision.EXCLUDED)
        if (messageKeys.isEmpty()) return CaptureOutcome(ArchiveDecision.NO_CONTENT)
        if (!available()) return CaptureOutcome(ArchiveDecision.DISABLED)

        // Content-free identity of this deletion for the journal: which keys it named.
        val markerHash = ContentBlobCodec.hash(
            Json.encodeToString(ListSerializer(String.serializer()), messageKeys).toByteArray(),
        )
        val kept = writeMutex.withLock {
            val held = messageKeys.chunked(SQL_VARIABLE_CHUNK).flatMap { chunk ->
                db.archivedMessageQueries.selectHeldByKeys(chat.kind.name, chat.key, chunk).executeAsList()
            }.associateBy { it.message_key }
            val kept = messageKeys.filter { it in held }
            if (kept.isEmpty()) return CaptureOutcome(ArchiveDecision.NO_CONTENT, detail = DETAIL_NEVER_ARCHIVED)
            val fresh = kept.mapNotNull { held[it] }.filter { it.deleted_at_ms == null }.map { it.id }
            if (fresh.isEmpty()) return CaptureOutcome(ArchiveDecision.DUP_HASH, markerHash, markerHash)
            val now = clock()
            db.transaction {
                fresh.chunked(SQL_VARIABLE_CHUNK).forEach { ids ->
                    db.archivedMessageQueries.markDeleted(deletedAtMs = now, albumKey = albumKey, ids = ids)
                }
            }
            kept
        }
        _events.tryEmit(ArchiveEvent.Deleted(chat, kept))
        return CaptureOutcome(ArchiveDecision.WRITTEN, markerHash)
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
    ): CaptureOutcome {
        val outcome = captureWebVersionInternal(chat, messageKey, previous, seenAtOverrideMs)
        journal?.record(
            ArchiveJournalRecord(
                wallTimeMs = clock(),
                sourceKind = chat.kind,
                chatKey = chat.key,
                messageKey = messageKey,
                event = ArchiveEventKind.WEB,
                decision = outcome.decision,
                newHash = outcome.hash,
                latestHash = outcome.latestHash,
            ),
        )
        return outcome
    }

    private suspend fun captureWebVersionInternal(
        chat: ChatRef,
        messageKey: String,
        previous: WebPost,
        seenAtOverrideMs: Long?,
    ): CaptureOutcome {
        versionGate(settings.first(), chat)?.let { return CaptureOutcome(it) }

        val json = Json.encodeToString(JsonObject.serializer(), webPostToJson(previous))
        val blob = json.toByteArray(Charsets.UTF_8)
        val hash = ContentBlobCodec.hash(blob)
        writeMutex.withLock {
            val latest = messageRow(chat, messageKey)?.let { latestVersion(it.id) }
            if (latest?.content_hash == hash) return CaptureOutcome(ArchiveDecision.DUP_HASH, hash, hash)
            val observedAt = seenAtOverrideMs ?: clock()
            insertVersion(
                chat = chat, messageKey = messageKey, albumKey = null, isComment = false,
                publishedAtMs = null, sortAtMs = observedAt, editDateMs = null, origin = VersionOrigin.WEB,
                contentKind = CONTENT_KIND_WEB, blob = blob, hash = hash,
                textPreview = previous.textHtml.replace(Regex("<[^>]+>"), "").take(200),
                mediaSha = null, minithumb = null, observedAtMs = observedAt,
            )
            maybeEvictByCap()
        }
        _events.tryEmit(ArchiveEvent.Captured(chat, messageKey))
        return CaptureOutcome(ArchiveDecision.WRITTEN, hash)
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

    /**
     * Wipes the archive (logout, "delete archive"): every message, version and channel row,
     * schema 2's v1 rows (`PostSnapshotLegacy`, still kept by `2.sqm` — the v1 blobs are the
     * user's posts too), the copied media files and the decision journal.
     *
     * When `archive.db` could not be opened this run ([available] false), the tables wiped
     * are the in-memory stand-in's, so the real storage is removed by [wipeStorage] instead:
     * the user asked for deletion, and a file left in place would come back on the next
     * successful open. [GuardedSqlDriver] has already closed the primary in that state.
     *
     * The media wipe runs under `writeMutex` too, so no capture slips between the table wipe
     * and the file wipe. Lock order is safe: [ArchivedMediaStore]'s own mutex is never held
     * while `writeMutex` is taken (copies, claims and the orphan sweep take only the former).
     */
    suspend fun clear() {
        val onDisk = available()
        writeMutex.withLock {
            db.transaction {
                db.messageVersionQueries.clearAll()
                db.archivedMessageQueries.clearAll()
                db.archivedChannelQueries.clearAll()
                db.archivedMessageQueries.dropLegacyTable()
            }
            if (onDisk) runCatching { mediaStore?.clearAll() } else runCatching(wipeStorage)
        }
        // The decision journal describes rows that no longer exist (and, on logout, the
        // previous account's chats) — wiped with them.
        journal?.clear()
    }

    // --- Read API ---

    /**
     * A message's history, oldest first: its versions in timeline order, then — when the
     * message was deleted — a [SnapshotKind.DELETED] entry at the deletion time.
     */
    fun observeRevisions(chat: ChatRef, messageKey: String): Flow<ImmutableList<PostSnapshot>> =
        db.messageVersionQueries
            .selectForMessageKey(chat.kind.name, chat.key, messageKey)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> revisionsOf(rows).toPersistentList() }

    fun observeChannelIndex(): Flow<ImmutableList<ArchivedChannelEntry>> =
        db.archivedChannelQueries.countByChannel()
            .asFlow().mapToList(Dispatchers.IO)
            .map { rows ->
                rows.map { r ->
                    ArchivedChannelEntry(
                        chat = ChatRef(SourceKind.valueOf(r.source_kind), r.source_key),
                        title = r.title, handle = r.handle,
                        photoMinithumb = r.photo_minithumb,
                        messageCount = r.message_count.toInt(),
                        lastSnapshotAtMs = r.last_snapshot_at_ms,
                    )
                }.toPersistentList()
            }

    /**
     * The archive browser's list: one [ArchiveEntry] per deleted message (album = one) or
     * per message with at least two versions, newest event first. See
     * [buildArchiveEntries] for the grouping rules. Messages are read without their blobs;
     * minithumbs are fetched only for the versions the entries show.
     */
    fun observeEntries(): Flow<ImmutableList<ArchiveEntry>> =
        db.archivedMessageQueries.selectEntryMessages()
            .asFlow().mapToList(Dispatchers.IO)
            .map { rows -> db.toArchiveEntries(rows) }
            .flowOn(Dispatchers.IO)

    /**
     * Bytes the archive takes on disk: stored version blobs and thumbnails plus the media
     * files copied out of TDLib's cache ([ArchivedMediaStore]). The schema-2 legacy copy
     * (`PostSnapshotLegacy`, kept for one release) is not counted.
     */
    fun observeStorageBytes(): Flow<Long> = combine(
        db.messageVersionQueries.storageBytes().asFlow().mapToOne(Dispatchers.IO),
        db.archivedMediaFileQueries.storageBytes().asFlow().mapToOne(Dispatchers.IO),
    ) { rows, files -> rows + files }

    /** Removes the messages [messageIds] (`ArchiveEntry.messageIds`) with their whole history. */
    suspend fun purge(messageIds: List<Long>) {
        if (messageIds.isEmpty()) return
        writeMutex.withLock { withContext(Dispatchers.IO) { db.deleteMessages(messageIds) } }
    }

    /**
     * Stream the entire archive as JSON into [out].
     *
     * Format (`version` 2): `{ "format", "version", "exportedAtMs", "hashVersion",
     * "messages": [ { message fields…, "versions": [ { version fields…,
     * "contentBlobBase64", "minithumbBase64"? } ] } ], "captureJournal": [ … ] }`.
     * Field names spell out what each value is; `contentKind` says how to read the blob
     * (`tdlib` = [ContentBlobCodec], `web` = UTF-8 JSON). Version 1 was a flat `records`
     * array of v1 rows, deletion markers included.
     *
     * Uses [android.util.JsonWriter] and pages messages by id, so the in-memory cost stays
     * bounded by one page of message rows plus one message's versions, not by the total
     * archive size.
     *
     * Runs on [Dispatchers.IO] whatever the caller's context: SQL paging, Base64 and the
     * stream writes all block. Holds the capture lock for the whole write, so the file is one
     * consistent snapshot.
     *
     * @return the number of messages written.
     */
    suspend fun exportTo(out: java.io.OutputStream): Int = writeMutex.withLock {
        withContext(Dispatchers.IO) { writeExport(out) }
    }

    private fun writeExport(out: java.io.OutputStream): Int {
        val writer = android.util.JsonWriter(out.writer(Charsets.UTF_8).buffered())
        var count = 0
        writer.beginObject()
        writer.name("format").value(EXPORT_FORMAT)
        writer.name("version").value(EXPORT_VERSION)
        writer.name("exportedAtMs").value(clock())
        writer.name("hashVersion").value(ContentNormalizer.HASH_VERSION)
        writer.name("messages").beginArray()
        var afterId = 0L
        while (true) {
            val page = db.archivedMessageQueries.selectPageForExport(afterId, EXPORT_PAGE).executeAsList()
            if (page.isEmpty()) break
            for (m in page) {
                writeExportMessage(writer, m)
                count++
            }
            afterId = page.last().id
        }
        writer.endArray()
        // Capture decision journal (ids, hashes, codes — no text): lets a user report
        // "this post has 8 versions" come with the reason for each one.
        writer.name("captureJournal")
        writeJournalArray(writer, journal?.readAll().orEmpty())
        writer.endObject()
        writer.flush()
        return count
    }

    private fun writeExportMessage(writer: android.util.JsonWriter, m: ArchivedMessage) {
        writer.beginObject()
        writer.name("sourceKind").value(m.source_kind)
        writer.name("sourceKey").value(m.source_key)
        writer.name("messageKey").value(m.message_key)
        m.album_key?.let { writer.name("albumKey").value(it) }
        writer.name("isComment").value(m.is_comment == 1L)
        m.published_at_ms?.let { writer.name("publishedAtMs").value(it) }
        writer.name("firstCapturedAtMs").value(m.first_captured_at_ms)
        writer.name("lastEventAtMs").value(m.last_event_at_ms)
        m.deleted_at_ms?.let { writer.name("deletedAtMs").value(it) }
        writer.name("versions").beginArray()
        for (v in db.messageVersionQueries.selectForMessage(m.id).executeAsList()) {
            writer.beginObject()
            writer.name("sortAtMs").value(v.sort_at_ms)
            v.edit_date_ms?.let { writer.name("editDateMs").value(it) }
            writer.name("observedAtMs").value(v.observed_at_ms)
            writer.name("origin").value(v.origin)
            writer.name("contentKind").value(v.content_kind)
            writer.name("contentHash").value(v.content_hash)
            writer.name("hashVersion").value(v.hash_version)
            writer.name("textPreview").value(v.text_preview)
            v.media_sha?.let { writer.name("mediaSha").value(it) }
            writer.name("contentBlobBase64").value(
                android.util.Base64.encodeToString(v.content_blob, android.util.Base64.NO_WRAP),
            )
            v.media_minithumb?.let {
                writer.name("minithumbBase64").value(android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP))
            }
            writer.endObject()
        }
        writer.endArray()
        writer.endObject()
    }

    suspend fun storageBytes(): Long = writeMutex.withLock {
        db.messageVersionQueries.storageBytes().executeAsOne()
    }

    /**
     * Emits one [TombstoneRecord] per deleted TDLib channel post — an album's deleted
     * members are one record — with the latest captured content and the denormalised
     * channel metadata. Used by PostsRepository to reconstruct "ghost" tombstone posts in
     * the feed on cold start — TDLib doesn't return deleted messages, so without these we'd
     * silently lose every deleted post on relaunch even though the archive still holds it.
     *
     * One joined query per emission ([selectTombstones]), grouped here.
     */
    fun observeTdlibTombstones(): Flow<ImmutableList<TombstoneRecord>> =
        db.archivedMessageQueries.selectTombstones()
            .asFlow().mapToList(Dispatchers.IO)
            .map { rows -> buildTombstones(rows).toPersistentList() }

    /**
     * Emits a `(chatId, messageId) → editCount` map: versions beyond the first, per
     * message. Used to seed [TimelinePost.revisionCount] on cold start so the EditedChip
     * survives a relaunch — TDLib hands us posts with no archive metadata. Phantom versions
     * stored under older canonical forms are folded by the sweep's dedupe step
     * ([dedupeVersions]), so the count is a plain count of versions.
     */
    fun observeTdlibRevisionCounts(): Flow<Map<Pair<Long, Long>, Int>> =
        db.archivedMessageQueries.selectTdlibEditCounts()
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

    private fun buildTombstones(rows: List<SelectTombstones>): List<TombstoneRecord> {
        val groups = LinkedHashMap<String, MutableList<SelectTombstones>>()
        for (r in rows) {
            val key = r.album_key?.let { "a:${r.source_key}:$it" } ?: "m:${r.id}"
            groups.getOrPut(key) { mutableListOf() } += r
        }
        return groups.values.mapNotNull(::tombstoneOf)
    }

    private fun tombstoneOf(members: List<SelectTombstones>): TombstoneRecord? {
        val sorted = members.sortedWith(compareBy({ it.message_key.toLongOrNull() ?: Long.MAX_VALUE }, { it.message_key }))
        val primary = sorted.first()
        val chatId = primary.source_key.toLongOrNull() ?: return null
        val ids = sorted.mapNotNull { it.message_key.toLongOrNull() }
        val primaryId = ids.firstOrNull() ?: return null
        // A decode failure (corrupt blob, schema drift) on every member drops the record.
        val metas = sorted.mapNotNull { decodeTdlibOrNull(it.content_kind, it.content_blob) }
        if (metas.isEmpty()) return null
        return TombstoneRecord(
            chatId = chatId,
            primaryMessageId = primaryId,
            allMessageIds = ids,
            deletedAtMs = sorted.maxOf { it.deleted_at_ms },
            originalSeenAtMs = primary.published_at_ms ?: primary.sort_at_ms,
            text = metas.firstOrNull { it.text.isNotEmpty() }?.text.orEmpty(),
            channelTitle = primary.title,
            channelHandle = primary.handle,
            channelPhotoMinithumb = primary.photo_minithumb,
            isVerified = primary.is_verified == 1L,
        )
    }

    // --- Private helpers ---

    private fun messageRow(chat: ChatRef, messageKey: String): ArchivedMessage? =
        db.archivedMessageQueries.selectByKey(chat.kind.name, chat.key, messageKey).executeAsOneOrNull()

    private fun latestVersion(messageId: Long): LatestForMessage? =
        db.messageVersionQueries.latestForMessage(messageId).executeAsOneOrNull()

    /**
     * Sort key of a version appended after [latest]: its edit date, but never before the
     * latest version — rows migrated from schema 2 may sort at a device time, and an
     * append must stay last regardless of clock skew.
     */
    private fun appendSort(editedAtMs: Long, latest: LatestForMessage?): Long =
        maxOf(editedAtMs, latest?.sort_at_ms ?: editedAtMs)

    /** Writes one version, creating or touching its message row, in one transaction. */
    @Suppress("LongParameterList")
    private fun insertVersion(
        chat: ChatRef, messageKey: String, albumKey: String?, isComment: Boolean,
        publishedAtMs: Long?, sortAtMs: Long, editDateMs: Long?, origin: VersionOrigin,
        contentKind: String, blob: ByteArray, hash: String, textPreview: String,
        mediaSha: String?, minithumb: ByteArray?, observedAtMs: Long = clock(),
    ) {
        val now = clock()
        val comment = if (isComment) 1L else 0L
        db.transaction {
            val q = db.archivedMessageQueries
            q.insertIfAbsent(chat.kind.name, chat.key, messageKey, albumKey, comment, publishedAtMs, now, now)
            val id = q.selectByKey(chat.kind.name, chat.key, messageKey).executeAsOne().id
            q.touch(albumKey = albumKey, isComment = comment, publishedAtMs = publishedAtMs, eventAtMs = now, id = id)
            db.messageVersionQueries.insert(
                message_id = id,
                sort_at_ms = sortAtMs,
                edit_date_ms = editDateMs,
                observed_at_ms = observedAtMs,
                origin = origin.name,
                content_kind = contentKind,
                content_blob = blob,
                content_hash = hash,
                hash_version = ContentNormalizer.HASH_VERSION,
                text_preview = textPreview,
                media_sha = mediaSha?.takeIf { it.isNotEmpty() },
                media_minithumb = minithumb,
            )
        }
    }

    private fun maybeEvictByCap() {
        val count = writeCounter.incrementAndGet()
        if (count % capEvictionEvery != 0) return
        val cap = settings.value.maxRecords
        if (cap == Int.MAX_VALUE) return
        // Whole messages, cheapest-to-lose first (see evictToCap). Media files of evicted
        // versions become unreferenced and are reclaimed by the orphan sweep.
        db.evictToCap(cap, clock() - CAP_BASELINE_PROTECTION_MS)
    }

    /** Empty stand-in for a row we can't decode — see [decodeContent]. */
    private fun unsupportedContent(): ArchivedContent.Tdlib = ArchivedContent.Tdlib(
        TdlibContentMeta(
            text = "", entitiesJson = "[]",
            mediaSummaryJson = null, pollJson = null,
            forwardJson = null, replyJson = null,
        ),
        isUnreadable = true,
    )

    /**
     * Decode a version's `content_blob` into an [ArchivedContent], degrading gracefully on
     * failure, with the media reference's archive sha taken from the `media_sha` column
     * (the source of truth; the blob's own copy may be stale after the dedupe step moved
     * a file reference to an earlier version).
     *
     * [ContentBlobCodec] is versioned precisely so a format drift surfaces as
     * "snapshot format unsupported" rather than a deserialization crash — but
     * that promise only holds if the read path actually catches the throw. A
     * single corrupt blob, or an install of an older build over a newer
     * `archive.db` (decode hits `require(version == VERSION)`), would otherwise
     * propagate out of this `map` and terminate the entire [observeRevisions] flow,
     * blanking the whole revision sheet. The empty placeholder matches the
     * unknown-`content_kind` fallback so the row renders as an unreadable revision,
     * not an exception.
     */
    private fun decodeContent(contentKind: String, blob: ByteArray, mediaSha: String?): ArchivedContent =
        runCatching {
            when (contentKind) {
                CONTENT_KIND_TDLIB -> {
                    val meta = ContentBlobCodec.decode(blob)
                    val ref = meta.mediaRef?.copy(localArchiveSha = mediaSha)
                    ArchivedContent.Tdlib(if (ref == null) meta else meta.copy(mediaRef = ref))
                }
                CONTENT_KIND_WEB -> ArchivedContent.Web(
                    webPostFromJson(Json.decodeFromString(JsonObject.serializer(), blob.toString(Charsets.UTF_8))),
                )
                else -> unsupportedContent()
            }
        }.getOrElse { unsupportedContent() }

    private fun revisionsOf(rows: List<SelectForMessageKey>): List<PostSnapshot> {
        val first = rows.firstOrNull() ?: return emptyList()
        val sourceKind = runCatching { SourceKind.valueOf(first.source_kind) }.getOrNull() ?: return emptyList()
        val chat = ChatRef(sourceKind, first.source_key)
        val out = ArrayList<PostSnapshot>(rows.size + 1)
        rows.mapTo(out) { r ->
            PostSnapshot(
                id = r.id,
                chat = chat,
                messageKey = r.message_key,
                albumKey = r.album_key,
                kind = SnapshotKind.VERSION,
                seenAtMs = r.sort_at_ms,
                editedAtMs = r.edit_date_ms,
                content = decodeContent(r.content_kind, r.content_blob, r.media_sha),
                mediaMinithumb = r.media_minithumb,
                isComment = r.is_comment == 1L,
                origin = runCatching { VersionOrigin.valueOf(r.origin) }.getOrNull(),
                observedAtMs = r.observed_at_ms,
            )
        }
        first.deleted_at_ms?.let { deletedAt ->
            out += PostSnapshot(
                id = -first.message_id,
                chat = chat,
                messageKey = first.message_key,
                albumKey = first.album_key,
                kind = SnapshotKind.DELETED,
                seenAtMs = deletedAt,
                editedAtMs = null,
                content = unsupportedContent().copy(isUnreadable = false),
                mediaMinithumb = null,
                isComment = first.is_comment == 1L,
                observedAtMs = deletedAt,
            )
        }
        return out
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

    private companion object {
        const val EXPORT_FORMAT = "hortay-post-archive"
        const val EXPORT_VERSION = 2L
        const val EXPORT_PAGE = 200L
    }
}

sealed interface ArchiveEvent {
    data class Captured(val chat: ChatRef, val messageKey: String) : ArchiveEvent
    data class Deleted(val chat: ChatRef, val messageKeys: List<String>) : ArchiveEvent
}

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
    /** Messages the archive holds of this chat (each with its whole history). */
    val messageCount: Int,
    val lastSnapshotAtMs: Long,
)

/**
 * Denormalised record of a deleted channel post (an album's deleted members together):
 * the latest archived version + the cached channel metadata. Enough to reconstruct a
 * minimal "ghost" feed card after TDLib forgets the message.
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
    /** Last captured text (the first member with text, for an album); empty when none has text. */
    val text: String,
    val channelTitle: String,
    val channelHandle: String?,
    val channelPhotoMinithumb: ByteArray?,
    val isVerified: Boolean,
)
