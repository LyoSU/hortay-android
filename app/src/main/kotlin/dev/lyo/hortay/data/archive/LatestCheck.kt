package dev.lyo.hortay.data.archive

import dev.lyo.hortay.data.archive.db.LatestForMessage
import dev.lyo.hortay.data.archive.db.PostSnapshotQueries

/**
 * A capture compared with the latest stored row of its message.
 *
 * @property isDup the capture is the stored version — by stored hash, or ([detail] =
 *   [DETAIL_DUP_REHASH]) by the stored row's content re-hashed with the current normalizer.
 * @property diff how the two differ; null when the stored hashes matched (the fast path
 *   never decodes the stored row) or the stored row could not be decoded.
 */
internal data class LatestCheck(val isDup: Boolean, val detail: String?, val diff: ContentDiff?)

/**
 * Compares a TDLib capture ([newMeta], hashed [newHash]) with the latest stored row of
 * [messageKey], whose stored hash is [latestHash].
 *
 * Equal hashes are a duplicate without touching the row's blob. On a mismatch the row's
 * content is decoded and its hash re-derived with the CURRENT [ContentNormalizer]: a row
 * written before a normalizer change keeps the hash of the old projection, and comparing
 * only stored hashes would turn the next unchanged re-emit of every such post into a
 * phantom version. Equal re-derived hashes are a duplicate (`dup_rehash`). A row that
 * isn't TDLib content or can't be decoded falls back to the stored hash: not a duplicate.
 *
 * Call under `writeMutex`, so the row compared is the row a write would follow.
 */
@Suppress("LongParameterList")
internal fun checkAgainstLatest(
    queries: PostSnapshotQueries,
    chat: ChatRef,
    messageKey: String,
    latestHash: String,
    newHash: String,
    newMeta: TdlibContentMeta,
): LatestCheck {
    if (latestHash == newHash) return LatestCheck(isDup = true, detail = null, diff = null)
    val row = queries.latestContentForMessage(chat.kind.name, chat.key, messageKey).executeAsOneOrNull()
    val old = row?.let { decodeTdlibOrNull(it.content_kind, it.content_blob) }
        ?: return LatestCheck(isDup = false, detail = null, diff = null)
    val isDup = ContentNormalizer.canonicalHash(old) == newHash
    return LatestCheck(isDup = isDup, detail = if (isDup) DETAIL_DUP_REHASH else null, diff = ContentDiff.of(old, newMeta))
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
    val latestEdit = latest?.takeIf { it.kind == SnapshotKind.VERSION.name }?.edited_at_ms ?: return null
    return when {
        editedAtMs < latestEdit -> ArchiveDecision.STALE to null
        editedAtMs == latestEdit -> ArchiveDecision.STALE to DETAIL_SAME_EDIT_DATE
        else -> null
    }
}

/** TDLib content of a stored row, or null for a guest-mode / marker row or an unreadable blob. */
internal fun decodeTdlibOrNull(contentKind: String, blob: ByteArray): TdlibContentMeta? =
    if (contentKind != CONTENT_KIND_TDLIB) null else runCatching { ContentBlobCodec.decode(blob) }.getOrNull()

internal const val CONTENT_KIND_TDLIB = "tdlib"
