package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable

enum class SnapshotKind { VERSION, DELETED }

/**
 * How the archive came to hold a version — stored as `MessageVersion.origin`. Lets the
 * revision sheet be honest about gaps: only [PUBLISHED] and [LIVE_EDIT] versions are known
 * to have no unseen state right before them.
 */
enum class VersionOrigin {
    /** First observed as published, never edited (the ingest baseline). */
    PUBLISHED,

    /** First observed already edited: the published state was never seen. */
    FIRST_SEEN_EDITED,

    /** An edit update received while the app was running. */
    LIVE_EDIT,

    /**
     * Found on re-ingest with a newer edit date than the archive held: the edit happened
     * while the app was closed (or the live update was lost), so edits in between may be
     * missing.
     */
    RECONCILED,

    /** A guest-mode (t.me/s/) version, as a refresh found it. */
    WEB,
}

/**
 * One entry of a message's history, as the revision sheet reads it: a stored version, or
 * (kind [SnapshotKind.DELETED], content empty, [origin] null) the message's deletion,
 * synthesised from `ArchivedMessage.deleted_at_ms` — deletion is a state of the message,
 * not a stored row.
 *
 * @property id the version row id; for the deletion entry, minus the message row id.
 * @property seenAtMs the version's place on the timeline (`MessageVersion.sort_at_ms`):
 *   Telegram's edit date, else the publication time, else the time it was observed. For
 *   the deletion entry, the time the deletion was observed.
 * @property editedAtMs Telegram's edit date of this state; null = as published / unknown.
 * @property observedAtMs device time the archive saw this state.
 */
@Immutable
data class PostSnapshot(
    val id: Long,
    val chat: ChatRef,
    val messageKey: String,
    val albumKey: String?,
    val kind: SnapshotKind,
    val seenAtMs: Long,
    val editedAtMs: Long?,
    val content: ArchivedContent,
    val mediaMinithumb: ByteArray?,
    val isComment: Boolean = false,
    val origin: VersionOrigin? = null,
    val observedAtMs: Long = seenAtMs,
) {
    override fun equals(other: Any?): Boolean = other is PostSnapshot && other.id == id
    override fun hashCode(): Int = id.hashCode()
}
