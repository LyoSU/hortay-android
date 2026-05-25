@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Extracted metadata from a TDLib [org.drinkless.tdlib.TdApi.MessageContent] that
 * can be serialized to disk via [ContentBlobCodec] (ProtoBuf).
 *
 * Kept deliberately narrow — only the fields needed to render a revision diff in
 * [PostRevisionSheet] and to populate the archive search index.
 *
 * [entitiesJson] / [forwardJson] / [replyJson] are serialized as JSON strings so
 * the schema can evolve without a ProtoBuf field-number migration.
 *
 * ProtoBuf field numbers are pinned via [ProtoNumber] so old blobs decoded with a
 * newer schema (additional optional fields) stay readable. New fields MUST use a
 * fresh number and tolerate default values for existing rows.
 */
@Immutable
@Serializable
data class TdlibContentMeta(
    /** Full message text (caption for media, question for poll). Authoritative source for the diff view. */
    @ProtoNumber(1) val text: String,
    /** JSON-serialized `TdApi.TextEntity[]` — formatting preserved per revision. */
    @ProtoNumber(2) val entitiesJson: String,
    /** JSON-serialized media descriptor: `{type, count, w, h, durationMs}`. Null when post has no media. */
    @ProtoNumber(3) val mediaSummaryJson: String?,
    /** JSON-serialized poll snapshot (question + options). Null when post is not a poll. */
    @ProtoNumber(4) val pollJson: String?,
    /** JSON-serialized forward-source summary. Null when post is original. */
    @ProtoNumber(5) val forwardJson: String?,
    /** JSON-serialized reply-to summary. Null when post is not a reply. */
    @ProtoNumber(6) val replyJson: String?,
    /**
     * Structured media reference for the captured snapshot. Null when post carries
     * no media (text-only, poll without photo banner, etc.). Carries persistent
     * remote identifiers + optional local-archive SHA so the revision sheet can
     * render the media even after the original message is deleted server-side.
     */
    @ProtoNumber(7) val mediaRef: ArchivedMediaRef? = null,
) {
    /** First 200 chars of [text]; what the search index and list rows display. */
    val textPreview: String get() = text.take(200)
}
