package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Extracted metadata from a TDLib [org.drinkless.tdlib.TdApi.MessageContent] that
 * can be serialized to disk via [ContentBlobCodec] (ProtoBuf).
 *
 * Kept deliberately narrow — only the fields needed to render a revision diff in
 * [PostRevisionSheet] and to populate the archive search index.
 *
 * [entitiesJson] / [forwardJson] / [replyJson] are serialized as JSON strings so
 * the schema can evolve without a ProtoBuf field-number migration.
 */
@Immutable
@Serializable
data class TdlibContentMeta(
    /** Full message text (caption for media, question for poll). Authoritative source for the diff view. */
    val text: String,
    /** JSON-serialized `TdApi.TextEntity[]` — formatting preserved per revision. */
    val entitiesJson: String,
    /** JSON-serialized media descriptor: `{type, count, w, h, durationMs}`. Null when post has no media. */
    val mediaSummaryJson: String?,
    /** JSON-serialized poll snapshot (question + options). Null when post is not a poll. */
    val pollJson: String?,
    /** JSON-serialized forward-source summary. Null when post is original. */
    val forwardJson: String?,
    /** JSON-serialized reply-to summary. Null when post is not a reply. */
    val replyJson: String?,
) {
    /** First 200 chars of [text]; what the search index and list rows display. */
    val textPreview: String get() = text.take(200)
}
