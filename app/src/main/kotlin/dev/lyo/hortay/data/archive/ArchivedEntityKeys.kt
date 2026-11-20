package dev.lyo.hortay.data.archive

/**
 * JSON keys of one entity inside [TdlibContentMeta.entitiesJson]. Shared by the writer
 * ([TdlibContentMetaExtractor]), the hash projection ([ContentNormalizer]) and the reader
 * that turns a stored version back into formatted text.
 */
internal object ArchivedEntityKeys {
    const val OFFSET = "offset"
    const val LENGTH = "length"
    const val TYPE = "type"
    const val URL = "url"
    const val CUSTOM_EMOJI_ID = "customEmojiId"
    const val USER_ID = "userId"
    const val LANGUAGE = "language"
}
