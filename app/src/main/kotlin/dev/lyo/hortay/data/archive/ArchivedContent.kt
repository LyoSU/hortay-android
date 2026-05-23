package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.web.WebPost

/**
 * Sealed content payload stored inside a [PostSnapshot].
 *
 * ## Why no raw TdApi.MessageContent here
 * TDLib's generated [org.drinkless.tdlib.TdApi.MessageContent] has no
 * [toByteArray] / [fromByteArray] and is not [kotlinx.serialization.Serializable].
 * Structured content that must survive across sessions is extracted into
 * [TdlibContentMeta] at capture time and persisted via [ContentBlobCodec].
 * The original TDLib object is intentionally not retained — TDLib's own local DB
 * remains the authoritative store for live content; the archive holds the
 * human-readable diff surface only.
 */
@Immutable
sealed interface ArchivedContent {
    val textPreview: String

    /**
     * Content captured from the TDLib (authenticated) path.
     * [meta] is extracted at capture time by the repository layer.
     */
    @Immutable
    data class Tdlib(
        val meta: TdlibContentMeta,
    ) : ArchivedContent {
        override val textPreview: String get() = meta.textPreview
    }

    /**
     * Content captured from the guest-mode web path ([WebPost]).
     * Text preview is the HTML body stripped of tags, capped at 200 characters.
     */
    @Immutable
    data class Web(val post: WebPost) : ArchivedContent {
        override val textPreview: String get() =
            post.textHtml.replace(Regex("<[^>]+>"), "").take(200)
    }
}
