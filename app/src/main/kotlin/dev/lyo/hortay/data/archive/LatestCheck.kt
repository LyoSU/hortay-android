package dev.lyo.hortay.data.archive

import dev.lyo.hortay.data.archive.db.LatestForMessage
import dev.lyo.hortay.data.archive.db.MessageVersionQueries

/**
 * A capture compared with the latest stored version of its message.
 *
 * @property isDup the capture is the stored version — by stored hash, or ([detail] =
 *   [DETAIL_DUP_REHASH]) by the stored version's content re-hashed with the current normalizer.
 * @property diff how the two differ; null when the stored hashes matched (the fast path
 *   never decodes the stored blob) or the stored version could not be decoded.
 */
internal data class LatestCheck(val isDup: Boolean, val detail: String?, val diff: ContentDiff?)

/**
 * Compares a TDLib capture ([newMeta], hashed [newHash]) with [latest], the latest stored
 * version of its message.
 *
 * Equal hashes are a duplicate without touching the blob. On a mismatch the stored content
 * is decoded (for the journal's [ContentDiff]); when it was hashed with an older canonical
 * form (`hash_version` < [ContentNormalizer.HASH_VERSION]) its hash is re-derived with the
 * CURRENT normalizer: a row written before a normalizer change keeps the hash of the old
 * projection, and comparing only stored hashes would turn the next unchanged re-emit of
 * every such post into a phantom version. The re-derived hash is written back with the
 * current version (same content, newer form), so the next comparison takes the fast path.
 * A current-form mismatch is never re-hashed: it is a real difference. A version that isn't
 * TDLib content or can't be decoded falls back to the stored hash: not a duplicate.
 *
 * Call under `writeMutex`, so the version compared is the one a write would follow.
 */
internal fun checkAgainstLatest(
    queries: MessageVersionQueries,
    latest: LatestForMessage,
    newHash: String,
    newMeta: TdlibContentMeta,
): LatestCheck {
    if (latest.content_hash == newHash) return LatestCheck(isDup = true, detail = null, diff = null)
    val row = queries.contentById(latest.id).executeAsOneOrNull()
    val old = row?.let { decodeTdlibOrNull(it.content_kind, it.content_blob) }
        ?: return LatestCheck(isDup = false, detail = null, diff = null)
    val diff = ContentDiff.of(old, newMeta)
    if (latest.hash_version >= ContentNormalizer.HASH_VERSION) return LatestCheck(isDup = false, detail = null, diff = diff)
    val rehash = ContentNormalizer.canonicalHash(old)
    queries.updateHash(rehash, ContentNormalizer.HASH_VERSION, latest.id)
    val isDup = rehash == newHash
    return LatestCheck(isDup = isDup, detail = if (isDup) DETAIL_DUP_REHASH else null, diff = diff)
}

/**
 * Why an admin-edit capture must not be written after [latest], or null to write it.
 *
 *  - Same content ([check]) → DUP_HASH.
 *  - An edit date strictly older than the latest version's → STALE (out-of-order capture;
 *    see the guard's history in [ArchiveRepository.captureTdlibEdit]).
 *  - The SAME edit date as the latest version's → STALE `same_edit_date`. Telegram bumps
 *    a message's edit date on every edit, so one edit date is one server version. A
 *    different capture of it is TDLib presenting that version differently between reads
 *    — an entity it shows or hides per read, a re-split style run, a message delivered
 *    first as `messageText` and later as `messageAnimatedEmoji` — and `updateMessageEdited`
 *    also fires with an unchanged edit date for reply-markup changes (td
 *    `MessagesManager::update_message`). Writing it recorded alternating phantom
 *    versions at the edit date the post already had.
 */
internal fun editSkip(latest: LatestForMessage?, check: LatestCheck?, editedAtMs: Long): Pair<ArchiveDecision, String?>? {
    if (check?.isDup == true) return ArchiveDecision.DUP_HASH to check.detail
    val latestEdit = latest?.edit_date_ms ?: return null
    return when {
        editedAtMs < latestEdit -> ArchiveDecision.STALE to null
        editedAtMs == latestEdit -> ArchiveDecision.STALE to DETAIL_SAME_EDIT_DATE
        else -> null
    }
}

/**
 * Gives [latest] the archived media copy a capture that will NOT be written carries.
 *
 * A post's first version is usually written at ingest, before Telegram has downloaded its
 * photo, so it gets `media_sha = NULL`. Later captures of the same state find the file on
 * disk and copy it ([ArchivedMediaStore.copyIfAvailable]), then end as a duplicate and
 * write nothing — and a copy no version references is removed by
 * [ArchivedMediaStore.sweepOrphans] an hour later, only to be copied again on the next
 * launch, while the version keeps showing the blurred preview. Attaching the copy to the
 * version the capture just confirmed equal closes that loop.
 *
 * Attaches only when all hold:
 *  - [newMeta] carries a non-empty `localArchiveSha`;
 *  - [latest] has no `media_sha` (a reference is never replaced — the query itself guards it);
 *  - the capture IS [latest]: [check] (computed here when null, see [checkAgainstLatest])
 *    says duplicate by current-canonical hash;
 *  - the media is the same file: when both the stored version and the capture know a media
 *    `uniqueId`, they match (the canonical hash covers the media summary, not the file).
 *
 * Only the latest version is ever touched: it is the state the capture confirmed. Call under
 * `writeMutex`, in the same critical section as the decision.
 *
 * @return true when the reference was written.
 */
internal fun attachMediaIfSame(
    queries: MessageVersionQueries,
    latest: LatestForMessage?,
    newHash: String,
    newMeta: TdlibContentMeta,
    check: LatestCheck? = null,
): Boolean {
    val ref = newMeta.mediaRef ?: return false
    val sha = ref.localArchiveSha?.takeIf { it.isNotEmpty() } ?: return false
    if (latest == null || latest.media_sha != null) return false
    val same = check ?: checkAgainstLatest(queries, latest, newHash, newMeta)
    if (!same.isDup) return false
    val row = queries.contentById(latest.id).executeAsOneOrNull() ?: return false
    val stored = decodeTdlibOrNull(row.content_kind, row.content_blob) ?: return false
    val storedIdentity = stored.mediaRef?.uniqueId?.takeIf { it.isNotEmpty() }
    if (!sameMedia(storedIdentity, ref.uniqueId.takeIf { it.isNotEmpty() })) return false
    return queries.attachMediaSha(sha = sha, id = latest.id).value > 0L
}

/** [detail] with [DETAIL_MEDIA_ATTACHED] appended when [attached]. */
internal fun withMediaDetail(detail: String?, attached: Boolean): String? =
    if (!attached) detail else listOfNotNull(detail, DETAIL_MEDIA_ATTACHED).joinToString(",")

/** True when [detail] records a media attach ([withMediaDetail]). */
internal fun hasMediaAttached(detail: String?): Boolean =
    detail?.split(',')?.contains(DETAIL_MEDIA_ATTACHED) == true

/**
 * Two versions' media identities (`uniqueId`, null when unknown) name the same file —
 * unknown on either side counts as the same.
 */
internal fun sameMedia(a: String?, b: String?): Boolean = a == null || b == null || a == b

/** TDLib content of a stored version, or null for a guest-mode version or an unreadable blob. */
internal fun decodeTdlibOrNull(contentKind: String, blob: ByteArray): TdlibContentMeta? =
    if (contentKind != CONTENT_KIND_TDLIB) null else runCatching { ContentBlobCodec.decode(blob) }.getOrNull()

internal const val CONTENT_KIND_TDLIB = "tdlib"
internal const val CONTENT_KIND_WEB = "web"
