package dev.lyo.hortay.ui.media

import java.util.LinkedHashMap

/**
 * Factory for a size-bounded [LinkedHashMap] that evicts its eldest entry once it
 * grows past [maxEntries].
 *
 * Several process-wide media caches (parsed sticker outlines, Lottie compositions,
 * resolved custom emoji, parsed web post bodies) all hand-rolled the same
 * `object : LinkedHashMap(...) { override removeEldestEntry = size > N }` boilerplate.
 * This collapses that duplication into one call.
 *
 * Why this is NOT [android.util.LruCache]: LruCache synchronises every `get`/`put`
 * on its own monitor. These caches run in single-threaded Compose / UI context
 * (or already guard themselves with their own `synchronized` / `Mutex`), so the
 * extra per-access lock is pure overhead. A plain access-ordered [LinkedHashMap]
 * is the lighter, correct primitive here. Caches with genuine multi-thread access
 * (e.g. `MinithumbImage`, `TelegramLinkResolver`) intentionally stay on `LruCache`.
 *
 * @param maxEntries hard cap on resident entries.
 * @param accessOrder `true` for recency-ordered eviction (least-recently-accessed
 *   drops first); `false` for insertion-order eviction. Match the call site's
 *   existing [LinkedHashMap] constructor argument exactly.
 * @param onEvict optional hook invoked with the evicted entry just before it is
 *   removed — used to release resources (e.g. recycle bitmaps) the entry owns.
 */
internal fun <K, V> boundedLruCache(
    maxEntries: Int,
    accessOrder: Boolean = true,
    onEvict: ((K, V) -> Unit)? = null,
): LinkedHashMap<K, V> = object : LinkedHashMap<K, V>(16, 0.75f, accessOrder) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
        val over = size > maxEntries
        if (over && onEvict != null && eldest != null) onEvict(eldest.key, eldest.value)
        return over
    }
}
