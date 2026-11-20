package dev.lyo.hortay.data.archive

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

/**
 * How a capture differs from the stored version it was compared against, as the capture
 * journal records it. **Content-free by construction**: component names, lengths, counts
 * and entity type names only — never text, urls, user ids or file names. It exists to
 * tell a real edit from a phantom one on a device without exporting what the post says.
 *
 * @property components the [ContentNormalizer] components whose canonical form differs
 *   (`text`, `entities`, `media`, `poll`, `forward`, `reply`); empty when the two are the
 *   same version under the current normalizer (a `dup_rehash`).
 * @property entCountOld / entCountNew entities as stored, before canonicalisation.
 * @property entityReason how the stored entity lists differ, null when they are equal:
 *   `count_changed`, `order_only` (same entities, other order), `types_changed` (same
 *   count, other type multiset), `offsets_shifted` (same types, other ranges).
 * @property typesAdded / typesRemoved entity types (without the `TextEntityType` prefix)
 *   present in one stored list more often than in the other.
 */
data class ContentDiff(
    val components: List<String>,
    val textLenOld: Int,
    val textLenNew: Int,
    val entCountOld: Int,
    val entCountNew: Int,
    val entityReason: String?,
    val typesAdded: List<String>,
    val typesRemoved: List<String>,
) {
    /** Appends the journal fields. */
    fun writeTo(builder: JsonObjectBuilder) {
        builder.put("diff", if (components.isEmpty()) DIFF_NONE else components.joinToString("+"))
        builder.put("textLenOld", textLenOld)
        builder.put("textLenNew", textLenNew)
        builder.put("entOld", entCountOld)
        builder.put("entNew", entCountNew)
        entityReason?.let { builder.put("entReason", it) }
        if (typesAdded.isNotEmpty()) builder.put("entAdded", typesAdded.joinToString("+"))
        if (typesRemoved.isNotEmpty()) builder.put("entRemoved", typesRemoved.joinToString("+"))
    }

    companion object {
        const val DIFF_NONE = "none"
        const val REASON_COUNT = "count_changed"
        const val REASON_ORDER = "order_only"
        const val REASON_TYPES = "types_changed"
        const val REASON_OFFSETS = "offsets_shifted"
        private const val TYPE_PREFIX = "TextEntityType"
        private const val MAX_TYPES = 6

        /** Compares [old] (the stored version) with [new] (the capture). */
        fun of(old: TdlibContentMeta, new: TdlibContentMeta): ContentDiff {
            val oldComponents: Map<String, JsonElement> = ContentNormalizer.components(old)
            val newComponents: Map<String, JsonElement> = ContentNormalizer.components(new)
            val changed = ContentNormalizer.COMPONENTS.filter { oldComponents[it] != newComponents[it] }
            val oldRaw = ContentNormalizer.rawEntities(old.entitiesJson)
            val newRaw = ContentNormalizer.rawEntities(new.entitiesJson)
            return ContentDiff(
                components = changed,
                textLenOld = old.text.length,
                textLenNew = new.text.length,
                entCountOld = oldRaw.size,
                entCountNew = newRaw.size,
                entityReason = entityReason(oldRaw, newRaw),
                typesAdded = typeSurplus(newRaw, oldRaw),
                typesRemoved = typeSurplus(oldRaw, newRaw),
            )
        }

        private fun entityReason(
            old: List<ContentNormalizer.CanonicalEntity>,
            new: List<ContentNormalizer.CanonicalEntity>,
        ): String? {
            val bySortKey = compareBy<ContentNormalizer.CanonicalEntity>({ it.offset }, { it.length }, { it.type })
            return when {
                old == new -> null
                old.size != new.size -> REASON_COUNT
                old.sortedWith(bySortKey) == new.sortedWith(bySortKey) -> REASON_ORDER
                old.map { it.type }.sorted() != new.map { it.type }.sorted() -> REASON_TYPES
                else -> REASON_OFFSETS
            }
        }

        /** Types [a] holds more of than [b], short names, at most [MAX_TYPES]. */
        private fun typeSurplus(
            a: List<ContentNormalizer.CanonicalEntity>,
            b: List<ContentNormalizer.CanonicalEntity>,
        ): List<String> {
            val counts = HashMap<String, Int>()
            a.forEach { counts.merge(it.type, 1, Int::plus) }
            b.forEach { counts.merge(it.type, -1, Int::plus) }
            return counts.filterValues { it > 0 }.keys.sorted().take(MAX_TYPES).map { it.removePrefix(TYPE_PREFIX) }
        }
    }
}
