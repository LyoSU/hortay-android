package dev.lyo.hortay.data.archive

import dev.lyo.hortay.data.suspendRunCatching
import dev.lyo.hortay.data.warnUnlessCancelled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * The single ordered writer for TDLib-mode archive captures (posts and comments).
 *
 * ## Why one writer
 *
 * Captures used to be one `scope.launch` per update, each with suspension points
 * (media copy on IO, a `GetMessage` round-trip) before `ArchiveRepository.writeMutex`,
 * so the order rows were written in was not the order TDLib reported the changes in —
 * two quick edits could land newest-first, and only the stale-edit-date guard in
 * [ArchiveRepository.captureTdlibEdit] kept that from alternating the history. Now the
 * update handlers only **enqueue** (a non-suspending `trySend` on an unlimited channel)
 * and ONE consumer coroutine processes events strictly in enqueue order; media copies
 * and TDLib reads happen inside the consumer, so a slow one delays later events instead
 * of being overtaken by them.
 *
 * ## Events
 *
 *  - [recordIngest] — a message reached the feed (`PostsRepository.ingest`). A message
 *    the feed did not hold before gets its first-observed baseline; one it already held
 *    is re-checked only when it carries an `editDate` (reconcile, below).
 *  - [recordEdit] — `UpdateMessageEdited(editDate > 0)` for a post or a comment.
 *  - [recordDelete] — `UpdateDeleteMessages` for channel posts; per-chat sliding 200 ms
 *    debounce so split pulses (album members) collapse into one smart-grouped DELETED
 *    write. The ids are enqueued at once; the timer only enqueues a flush marker, and
 *    the consumer flushes only on the marker of the chat's LATEST batch.
 *  - [recordCommentDelete] — comment deletions, written immediately, ungrouped (as before).
 *
 * ## Edit content source (verified against TDLib, pin 1b08c83b in scripts/tdlib-version.txt)
 *
 * `td/telegram/MessagesManager.cpp`, `MessagesManager::update_message` — the path every
 * server-side edit of a message TDLib already knows takes — first writes
 * `old_message->edit_date`, then `update_message_content(...)` replaces the content of
 * the in-memory `Message` in place, then calls `send_update_message_content` (emits
 * `updateMessageContent`) and only after it `send_update_message_edited` (emits
 * `updateMessageEdited`). Both go through `send_closure(G()->td(), &Td::send_update, …)`
 * from the same actor, so they reach the client in that order; the only other caller of
 * `send_update_message_edited` is `on_message_edited`, and only for bots. So by the time
 * the client sees `updateMessageEdited`, MessagesManager's message object already holds
 * the new content and edit date, and a `getMessageLocally` issued after it (processed by
 * the same actor, later) returns that state — or a newer one if another edit followed,
 * which is why the consumer records the content together with the message's OWN
 * `editDate` from that read. `updateMessageContent` is not even guaranteed to exist
 * (`send_update_message_content_impl` skips it while `!m->is_update_sent`), and the
 * `td_api.tl` note "Changes in the message content will come in a separate
 * updateMessageContent" promises no order.
 *
 * Therefore: on an edit the consumer reads the message with [TdApi.GetMessageLocally]
 * (offline request — bypasses the FLOOD_WAIT gate, never reaches the server) and uses
 * its content + `editDate`. The [PendingEditBuffer] content the handler paired at
 * enqueue time is kept only as the fallback when the local read fails (404 after an
 * eviction, a closed client) — it can no longer be the primary source, and its TTL
 * pairing no longer decides anything except that fallback. Content updates without an
 * `updateMessageEdited(editDate > 0)` (poll votes, live location, paid-media reveal,
 * fact checks) never enqueue anything, so they still never create versions.
 *
 * ## Reconcile on re-ingest
 *
 * A message the archive already holds that comes back through ingest with an `editDate`
 * newer than the latest stored version's `edited_at_ms` (or the latest has none) and a
 * different normalized hash gets a VERSION with `edited_at_ms = editDate` — the edit
 * happened while the app was closed or the channel difference was too long, and the
 * live update never came. Hot-path cost: [recordIngest] returns before enqueueing when
 * the archive is off for the chat, when the message has no `editDate` and is not new to
 * the feed, or when this session already enqueued it at that `editDate` ([ingestSeen]).
 * The consumer then decides with two indexed SELECTs ([ArchiveRepository.planTdlibIngest])
 * and extracts + hashes the content only when the stored state is older.
 *
 * ## Session
 *
 * Every event carries the [generation] it was enqueued in. [reset] (logout, via
 * `AppGraph.session.onEnd`) bumps it, waits for the event in flight, drains the queue and
 * wipes the session maps, so a late event can never write the previous account's data.
 * The consumer runs in the session scope ([scope] is read on every launch) and is
 * relaunched lazily by the next enqueue after the old scope was cancelled.
 *
 * Every decision is written to [journal] (see [ArchiveJournalRecord]).
 */
class ArchiveRecorder(
    private val repo: ArchiveRepository,
    private val journal: ArchiveJournal,
    /** Scope the consumer and the delete-debounce timers launch in (the session scope). */
    private val scope: () -> CoroutineScope,
    /** `GetMessageLocally`; null when the message isn't available locally. */
    private val readLocal: suspend (chatId: Long, messageId: Long) -> TdApi.Message?,
    /** Copies the content's media file into archive storage; returns its SHA or null. */
    private val copyMedia: suspend (TdApi.MessageContent) -> String?,
    private val consumerContext: CoroutineContext = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val deleteDebounceMs: Long = DELETE_DEBOUNCE_MS,
) {

    /** Channel row metadata, snapshotted by the handler from the live feed. */
    class ChannelMeta(
        val title: String,
        val handle: String?,
        val photoMinithumb: ByteArray?,
        val isVerified: Boolean,
    )

    private sealed interface Event {
        val generation: Long
        val chatId: Long
    }

    private class Ingest(
        override val generation: Long,
        override val chatId: Long,
        val message: TdApi.Message,
        val allowBaseline: Boolean,
        val channel: ChannelMeta?,
    ) : Event

    private class Edit(
        override val generation: Long,
        override val chatId: Long,
        val messageId: Long,
        val editDate: Int,
        val buffered: TdApi.MessageContent?,
        val albumKey: String?,
        val channel: ChannelMeta?,
        val isComment: Boolean,
    ) : Event

    private class DeleteIds(
        override val generation: Long,
        override val chatId: Long,
        val ids: List<Long>,
        val seq: Long,
        val channel: ChannelMeta?,
    ) : Event

    private class DeleteFlush(
        override val generation: Long,
        override val chatId: Long,
        val seq: Long,
    ) : Event

    private class CommentDelete(
        override val generation: Long,
        override val chatId: Long,
        val keys: List<String>,
    ) : Event

    private class PendingDeletes(
        val ids: MutableList<Long> = mutableListOf(),
        var latestSeq: Long = 0L,
        var channel: ChannelMeta? = null,
    )

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val generation = AtomicLong(0L)

    /** Held while one event is processed; [reset] takes it to wait out the event in flight. */
    private val processing = Mutex()
    private val consumerLock = Any()

    @Volatile
    private var consumer: Job? = null

    /** Highest `editDate` enqueued per (chatId, messageId) this session — the ingest dedup. */
    private val ingestSeen = ConcurrentHashMap<Pair<Long, Long>, Int>()
    private val deleteSeq = AtomicLong(0L)
    private val deleteTimers = ConcurrentHashMap<Long, Job>()

    // Consumer-owned: touched only while holding [processing].
    private val pendingDeletes = HashMap<Long, PendingDeletes>()
    private val channelUpserted = HashSet<Long>()

    /** Master toggle (cheap `StateFlow.value` read). */
    fun isEnabled(): Boolean = repo.isEnabled()

    /** True when a VERSION capture for [chatId] would run — the handlers' pre-enqueue gate. */
    fun capturesEditsFor(chatId: Long): Boolean = repo.capturesEditsFor(ChatRef.tdlib(chatId))

    /**
     * A channel message was ingested into the feed. [newToFeed] = it was not in the feed
     * before this ingest (baseline candidate); otherwise only an edited message enqueues.
     */
    fun recordIngest(message: TdApi.Message, newToFeed: Boolean, channel: ChannelMeta?) {
        if (!newToFeed && message.editDate <= 0) return
        if (!capturesEditsFor(message.chatId)) return
        if (!markSeen(message.chatId, message.id, message.editDate)) return
        enqueue(Ingest(generation.get(), message.chatId, message, newToFeed, channel))
    }

    /** `UpdateMessageEdited(editDate > 0)`. [buffered] = the paired content, fallback only. */
    @Suppress("LongParameterList")
    fun recordEdit(
        chatId: Long,
        messageId: Long,
        editDate: Int,
        buffered: TdApi.MessageContent?,
        albumKey: String?,
        channel: ChannelMeta?,
        isComment: Boolean,
    ) {
        if (editDate <= 0) return
        if (!capturesEditsFor(chatId)) return
        // Deliberately NOT marked in [ingestSeen]: the ingest that first brings this
        // message into the feed can be enqueued after its edit (ingest suspends on the
        // album coalesce first) and must still get its baseline — see the race note on
        // [ArchiveRepository.captureTdlibBaseline]. A redundant reconcile costs two SELECTs.
        enqueue(Edit(generation.get(), chatId, messageId, editDate, buffered, albumKey, channel, isComment))
    }

    /** Permanent `UpdateDeleteMessages` for channel posts (debounced per chat). */
    fun recordDelete(chatId: Long, messageIds: LongArray, channel: ChannelMeta?) {
        if (messageIds.isEmpty() || !repo.isEnabled()) return
        val gen = generation.get()
        val seq = deleteSeq.incrementAndGet()
        enqueue(DeleteIds(gen, chatId, messageIds.toList(), seq, channel))
        deleteTimers.remove(chatId)?.cancel()
        // Compare-remove: a stale surviving timer must not clear the fresh one's entry.
        lateinit var timer: Job
        timer = scope().launch(consumerContext) {
            delay(deleteDebounceMs)
            deleteTimers.remove(chatId, timer)
            enqueue(DeleteFlush(gen, chatId, seq))
        }
        deleteTimers[chatId] = timer
    }

    /** Permanent `UpdateDeleteMessages` inside an observed comment thread (immediate). */
    fun recordCommentDelete(chatId: Long, messageIds: LongArray) {
        if (messageIds.isEmpty() || !repo.isEnabled()) return
        enqueue(CommentDelete(generation.get(), chatId, messageIds.map { it.toString() }))
    }

    /**
     * Logout: no event enqueued before this call writes afterwards. Registered with
     * `AppGraph.session.onEnd` ahead of the archive wipe in `runLogoutCleanup`.
     */
    suspend fun reset() {
        generation.incrementAndGet()
        deleteTimers.values.forEach { it.cancel() }
        deleteTimers.clear()
        processing.withLock {
            while (events.tryReceive().isSuccess) { /* discard the previous session's events */ }
            pendingDeletes.clear()
            channelUpserted.clear()
        }
        ingestSeen.clear()
        synchronized(consumerLock) {
            consumer?.cancel()
            consumer = null
        }
        journal.clear()
    }

    // --- Enqueue side ---

    /** Raise the seen editDate; false when this (or a newer) state was already enqueued. */
    private fun markSeen(chatId: Long, messageId: Long, editDate: Int): Boolean {
        var fresh = false
        ingestSeen.compute(chatId to messageId) { _, prev ->
            if (prev == null || editDate > prev) {
                fresh = true
                editDate
            } else {
                prev
            }
        }
        return fresh
    }

    private fun enqueue(event: Event) {
        events.trySend(event)
        ensureConsumer()
    }

    private fun ensureConsumer() {
        if (consumer?.isActive == true) return
        synchronized(consumerLock) {
            if (consumer?.isActive == true) return
            consumer = scope().launch(consumerContext) {
                for (event in events) handle(event)
            }
        }
    }

    // --- Consumer side ---

    private suspend fun handle(event: Event) {
        processing.withLock {
            if (event.generation != generation.get()) return
            suspendRunCatching { process(event) }
                .warnUnlessCancelled(TAG, "process(${event::class.simpleName},${event.chatId})")
                .onFailure { e -> recordError(event, e) }
        }
    }

    private suspend fun process(event: Event) {
        when (event) {
            is Ingest -> processIngest(event)
            is Edit -> processEdit(event)
            is DeleteIds -> {
                val p = pendingDeletes.getOrPut(event.chatId) { PendingDeletes() }
                p.ids += event.ids
                p.latestSeq = event.seq
                if (event.channel != null) p.channel = event.channel
            }
            is DeleteFlush -> processDeleteFlush(event)
            is CommentDelete -> {
                val chat = ChatRef.tdlib(event.chatId)
                val outcome = repo.captureTdlibDelete(chat, event.keys, albumKey = null, isComment = true)
                journalDelete(chat, event.keys, outcome, isComment = true)
            }
        }
    }

    private suspend fun processIngest(event: Ingest) {
        val message = event.message
        val chat = ChatRef.tdlib(event.chatId)
        val key = message.id.toString()
        val editedAtMs = message.editDate.takeIf { it > 0 }?.toLong()?.times(MS)
        val originalDateMs = message.date.toLong() * MS
        val plan = repo.planTdlibIngest(chat, key, originalDateMs, editedAtMs, event.allowBaseline) ?: return
        val outcome: CaptureOutcome
        val kind: ArchiveEventKind
        when (plan) {
            is IngestPlan.Skip -> {
                val baselineSide = plan.detail == DETAIL_BASELINE_PRESENT || (plan.detail == null && event.allowBaseline)
                kind = if (baselineSide) ArchiveEventKind.BASELINE else ArchiveEventKind.RECONCILE
                val skipped = CaptureOutcome(plan.decision, null, plan.latestHash, plan.detail)
                val attachable = plan.detail == DETAIL_BASELINE_PRESENT || plan.detail == DETAIL_NO_NEWER_EDIT
                outcome = if (attachable && plan.latestLacksMedia) {
                    attachSkippedMedia(event, chat, key, originalDateMs, skipped) ?: return
                } else {
                    skipped
                }
            }
            IngestPlan.Baseline -> {
                kind = ArchiveEventKind.BASELINE
                val meta = withMedia(TdlibContentMetaExtractor.extract(message.content), message.content)
                if (event.generation != generation.get()) return
                outcome = repo.captureTdlibBaseline(
                    chat = chat,
                    messageKey = key,
                    albumKey = albumKeyOf(message),
                    meta = meta,
                    originalDateMs = originalDateMs,
                    isComment = false,
                    priorEditedAtMs = editedAtMs,
                )
            }
            is IngestPlan.Reconcile -> {
                kind = ArchiveEventKind.RECONCILE
                val base = TdlibContentMetaExtractor.extract(message.content)
                val hash = ContentNormalizer.canonicalHash(base)
                // Equal stored hash: done without reading the version. A mismatch goes to the
                // repository, which re-derives an older-form hash before calling it a version.
                // ...unless the stored version still lacks the media file that is on disk now:
                // the repository attaches the copy to it (attachMediaIfSame).
                val attachable = plan.latestLacksMedia && mediaOnDisk(message.content)
                outcome = if (hash == plan.latestHash && !attachable) {
                    CaptureOutcome(ArchiveDecision.DUP_HASH, hash, plan.latestHash)
                } else {
                    val meta = withMedia(base, message.content)
                    if (event.generation != generation.get()) return
                    repo.captureTdlibReconcile(chat, key, albumKeyOf(message), editedAtMs ?: 0L, meta)
                }
            }
        }
        record(
            chat = chat, messageKey = key, event = kind, outcome = outcome, editDateMs = editedAtMs,
            source = ArchiveContentSource.INGEST,
            // Re-ingest no-ops repeat on every cold start for every archived post.
            // A media attach happens once per version and is worth keeping.
            persist = hasMediaAttached(outcome.detail) ||
                outcome.decision != ArchiveDecision.DUP_HASH && outcome.detail != DETAIL_NO_NEWER_EDIT &&
                outcome.detail != DETAIL_BASELINE_PRESENT,
        )
        upsertChannel(event.chatId, event.channel, outcome)
    }

    private suspend fun processEdit(event: Edit) {
        val chat = ChatRef.tdlib(event.chatId)
        val key = event.messageId.toString()
        val eventEditMs = event.editDate.toLong() * MS
        repo.versionGate(chat)?.let { decision ->
            record(chat, key, ArchiveEventKind.EDIT, CaptureOutcome(decision), eventEditMs, null, event.isComment)
            return
        }
        val local = suspendRunCatching { readLocal(event.chatId, event.messageId) }.getOrNull()
        val content: TdApi.MessageContent
        val editedAtMs: Long
        val source: ArchiveContentSource
        when {
            local != null && local.editDate >= event.editDate -> {
                content = local.content
                editedAtMs = local.editDate.toLong() * MS
                source = ArchiveContentSource.LOCAL_READ
            }
            event.buffered != null -> {
                content = event.buffered
                editedAtMs = eventEditMs
                source = ArchiveContentSource.BUFFERED
            }
            else -> {
                val detail = if (local == null) "no_local_no_buffer" else "local_older"
                record(chat, key, ArchiveEventKind.EDIT, CaptureOutcome(ArchiveDecision.NO_CONTENT, detail = detail),
                    eventEditMs, null, event.isComment)
                return
            }
        }
        val meta = withMedia(TdlibContentMetaExtractor.extract(content), content)
        if (event.generation != generation.get()) return
        val albumKey = if (event.isComment) null else local?.let(::albumKeyOf) ?: event.albumKey
        val outcome = repo.captureTdlibEdit(
            chat = chat,
            messageKey = key,
            albumKey = albumKey,
            editedAtMs = editedAtMs,
            meta = meta,
            isComment = event.isComment,
        )
        record(chat, key, ArchiveEventKind.EDIT, outcome, editedAtMs, source, event.isComment)
        // Comments skip the channel row: discussion-group metadata is the channel
        // anchor's, populated by the post-side captures.
        if (!event.isComment) upsertChannel(event.chatId, event.channel, outcome)
    }

    private suspend fun processDeleteFlush(event: DeleteFlush) {
        val pending = pendingDeletes[event.chatId] ?: return
        // A newer batch for this chat re-armed the debounce; its own flush drains everything.
        if (pending.latestSeq != event.seq) return
        pendingDeletes.remove(event.chatId)
        val chat = ChatRef.tdlib(event.chatId)
        val outcomes = repo.captureTdlibDeleteSmart(chat, pending.ids.map { it.toString() }, isComment = false)
        for (o in outcomes) journalDelete(chat, o.messageKeys, o.outcome, isComment = false)
        // Best-effort channel metadata: channels with a baseline already have a row.
        val channel = pending.channel
        if (channel != null && outcomes.any { it.outcome.decision == ArchiveDecision.WRITTEN }) {
            repo.upsertChannel(chat, channel.title, channel.handle, channel.photoMinithumb, channel.isVerified)
        }
    }

    /** Refresh the channel row on a write, and once per session per chat otherwise. */
    private suspend fun upsertChannel(chatId: Long, channel: ChannelMeta?, outcome: CaptureOutcome) {
        if (channel == null) return
        val written = outcome.decision == ArchiveDecision.WRITTEN
        if (!written && chatId in channelUpserted) return
        // A skipped capture for a chat the archive holds nothing of must not create a row.
        if (!written && outcome.latestHash == null) return
        channelUpserted += chatId
        repo.upsertChannel(
            ChatRef.tdlib(chatId), channel.title, channel.handle, channel.photoMinithumb, channel.isVerified,
        )
    }

    /**
     * A re-ingest skipped without hashing (`baseline_present`, or `no_newer_edit` — an edited
     * post re-ingested at the edit date the archive already holds) whose latest version has no
     * archived media copy: when the message's media file is on disk now, copy it and let the
     * repository re-validate under its lock and attach the copy to the latest version when that
     * is the same state ([attachMediaIfSame]). Nothing new is written. Without this, a version
     * written before the photo was downloaded showed only the blurred preview forever.
     *
     *  - `baseline_present` goes through [ArchiveRepository.captureTdlibBaseline] (the stored
     *    baseline still sits at-or-before this state, so it only re-checks and attaches).
     *  - `no_newer_edit` goes through [ArchiveRepository.attachSkippedMedia]; the decision stays
     *    STALE / `no_newer_edit`, with `media_attached` appended when the copy was attached.
     *
     * @return [skipped] when there is nothing to attach, or null when the session changed.
     */
    private suspend fun attachSkippedMedia(
        event: Ingest,
        chat: ChatRef,
        key: String,
        originalDateMs: Long,
        skipped: CaptureOutcome,
    ): CaptureOutcome? {
        val message = event.message
        if (!mediaOnDisk(message.content)) return skipped
        val meta = withMedia(TdlibContentMetaExtractor.extract(message.content), message.content)
        if (meta.mediaRef?.localArchiveSha.isNullOrEmpty()) return skipped
        if (event.generation != generation.get()) return null
        if (skipped.detail != DETAIL_BASELINE_PRESENT) {
            val attached = repo.attachSkippedMedia(chat, key, meta)
            return skipped.copy(detail = withMediaDetail(skipped.detail, attached))
        }
        return repo.captureTdlibBaseline(
            chat = chat,
            messageKey = key,
            albumKey = albumKeyOf(message),
            meta = meta,
            originalDateMs = originalDateMs,
            isComment = false,
            priorEditedAtMs = null,
        )
    }

    /** The content's media file is fully downloaded — a cheap check before hashing it. */
    private fun mediaOnDisk(content: TdApi.MessageContent): Boolean =
        MediaFileFromContent.extract(content)?.local?.isDownloadingCompleted == true

    private suspend fun withMedia(base: TdlibContentMeta, content: TdApi.MessageContent): TdlibContentMeta {
        val ref = base.mediaRef ?: return base
        val sha = suspendRunCatching { copyMedia(content) }
            .warnUnlessCancelled(TAG, "copyMedia")
            .getOrNull() ?: return base
        return base.copy(mediaRef = ref.copy(localArchiveSha = sha))
    }

    private fun albumKeyOf(message: TdApi.Message): String? =
        message.mediaAlbumId.takeIf { it != 0L }?.toString()

    // --- Journal ---

    @Suppress("LongParameterList")
    private fun record(
        chat: ChatRef,
        messageKey: String,
        event: ArchiveEventKind,
        outcome: CaptureOutcome,
        editDateMs: Long?,
        source: ArchiveContentSource?,
        isComment: Boolean = false,
        persist: Boolean = true,
    ) {
        journal.record(
            ArchiveJournalRecord(
                wallTimeMs = clock(),
                sourceKind = chat.kind,
                chatKey = chat.key,
                messageKey = messageKey,
                event = event,
                decision = outcome.decision,
                isComment = isComment,
                editDateMs = editDateMs,
                newHash = outcome.hash,
                latestHash = outcome.latestHash,
                contentSource = source,
                detail = outcome.detail,
                diff = outcome.diff,
                persist = persist,
            ),
        )
    }

    private fun journalDelete(chat: ChatRef, keys: List<String>, outcome: CaptureOutcome, isComment: Boolean) {
        val detail = listOfNotNull(outcome.detail, "keys=${keys.size}").joinToString(",")
        record(
            chat, keys.firstOrNull().orEmpty(), ArchiveEventKind.DELETE,
            outcome.copy(detail = detail), editDateMs = null, source = null, isComment = isComment,
            // Catch-up deletes of messages the archive never held arrive by the hundred on
            // a cold start; persisting them would push every real record out of the ring.
            persist = outcome.detail != DETAIL_NEVER_ARCHIVED,
        )
    }

    private fun recordError(event: Event, e: Throwable) {
        val chat = ChatRef.tdlib(event.chatId)
        val (kind, key, isComment) = when (event) {
            is Ingest -> Triple(ArchiveEventKind.BASELINE, event.message.id.toString(), false)
            is Edit -> Triple(ArchiveEventKind.EDIT, event.messageId.toString(), event.isComment)
            is DeleteIds -> Triple(ArchiveEventKind.DELETE, event.ids.firstOrNull()?.toString().orEmpty(), false)
            is DeleteFlush -> Triple(ArchiveEventKind.DELETE, "", false)
            is CommentDelete -> Triple(ArchiveEventKind.DELETE, event.keys.firstOrNull().orEmpty(), true)
        }
        // Class name only: exception messages can carry SQL, paths or content.
        val detail = e::class.simpleName ?: "Throwable"
        record(chat, key, kind, CaptureOutcome(ArchiveDecision.ERROR, detail = detail), null, null, isComment)
    }

    companion object {
        private const val TAG = "ArchiveRecorder"
        private const val MS = 1000L

        /**
         * Quiet window per chat before a channel's buffered deletions are written. TDLib
         * splits an album delete into several `UpdateDeleteMessages` pulses on slow
         * networks; recording the album's deletion as one decision (one tombstone) needs them together.
         * Sliding — every new batch for the chat re-arms it. 200 ms matches the
         * interaction-info coalesce window: large enough to absorb a typical slow-network
         * album delete fan-out (~tens of ms between siblings), small enough not to delay
         * the archived tombstone. (Was `PostsRepository.ALBUM_DELETE_DEBOUNCE_MS`.)
         */
        const val DELETE_DEBOUNCE_MS = 200L
    }
}
