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
    val textPreview: String,
    val entitiesJson: String,
    val forwardJson: String?,
    val replyJson: String?,
)
