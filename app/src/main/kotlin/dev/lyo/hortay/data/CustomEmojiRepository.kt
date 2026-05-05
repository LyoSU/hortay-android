package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves Telegram `customEmojiId`s into renderable [CustomEmojiSticker]s. Two TDLib
 * affordances we lean on:
 *
 *   1. **`GetCustomEmojiStickers` is batched** — one call accepts up to 200 ids and
 *      returns the cached sticker descriptors. Issuing a separate call per inline emoji
 *      would burn round-trips; we collect ids in a debounce window and flush as one
 *      [TdApi.GetCustomEmojiStickers] per 200-id chunk.
 *   2. **TDLib already caches sticker metadata in its DB** — repeat calls for the same
 *      id are local-fast. Still, we keep an in-memory map so the UI never even has to
 *      cross the suspend boundary on a hit.
 *
 * Concurrency model:
 *   • [request] is fire-and-forget from any thread (Compose effects). It registers ids
 *     into [pending] under a mutex and (re)schedules a single debounced flush.
 *   • [flush] drains [pending] in chunks; results land in [_stickers]; ids TDLib reports
 *     as missing land in [missing] so we never re-request them.
 *   • UI subscribes to [stickers] (a StateFlow) and reads the resolved sticker by id.
 *
 * Memory: the store is kept as a [PersistentMap] so subscribers can do cheap structural
 * comparisons. We don't bound the map — typical channel feeds reference at most a few
 * hundred unique custom emojis per session, well under any GC pressure threshold.
 */
class CustomEmojiRepository(
    private val td: TdSender,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _stickers = MutableStateFlow<PersistentMap<Long, CustomEmojiSticker>>(persistentMapOf())
    val stickers: StateFlow<PersistentMap<Long, CustomEmojiSticker>> = _stickers.asStateFlow()

    /** Ids TDLib explicitly returned no sticker for — never re-fetched in-session. */
    private val missing = ConcurrentHashMap.newKeySet<Long>()

    private val pending = HashSet<Long>()
    private val pendingLock = Mutex()
    private var flushJob: Job? = null

    /**
     * Synchronous accessor for already-resolved stickers. Useful from places that can't
     * collect a flow (mappers, snapshot reads) and where a null result is fine.
     */
    fun get(id: Long): CustomEmojiSticker? = _stickers.value[id]

    /**
     * Populate the in-memory sticker store directly. Used by guest (web) mode to
     * bridge results from [dev.lyo.hortay.data.web.WebCustomEmojiResolver] (which
     * resolves t.me/i/emoji/<id>.json) into the same map TDLib mode populates via
     * GetCustomEmojiStickers. Lets [dev.lyo.hortay.ui.media.CustomEmojiInlineView]
     * stay the single rendering path for both modes — the resolver writes here,
     * the inline view reads via [stickers], the same Composable renders.
     *
     * Idempotent: re-publishing the same id is a no-op structurally but cheap.
     * Marking an id as known-missing here also prevents the TDLib resolver loop
     * from later re-fetching it.
     */
    fun populate(entries: Map<Long, CustomEmojiSticker>) {
        if (entries.isEmpty()) return
        _stickers.update { it.builder().also { b -> b.putAll(entries) }.build() }
    }

    /**
     * Hint: schedule resolution for these ids. Idempotent — already-resolved or known-missing
     * ids are skipped at zero cost. Safe to call from a Composable's `LaunchedEffect`.
     */
    fun request(ids: Iterable<Long>) {
        if (!ids.iterator().hasNext()) return
        scope.launch {
            pendingLock.withLock {
                val store = _stickers.value
                var added = false
                for (id in ids) {
                    if (id == 0L) continue
                    if (store.containsKey(id)) continue
                    if (id in missing) continue
                    if (pending.add(id)) added = true
                }
                if (!added) return@withLock
                flushJob?.cancel()
                flushJob = scope.launch(ioDispatcher) {
                    delay(BATCH_DEBOUNCE_MS)
                    flushPending()
                }
            }
        }
    }

    private suspend fun flushPending() {
        val drained: List<Long> = pendingLock.withLock {
            if (pending.isEmpty()) emptyList()
            else pending.toList().also { pending.clear() }
        }
        if (drained.isEmpty()) return

        // TDLib accepts at most 200 ids per call. Sequential (not parallel) chunking keeps
        // a burst of 800 emojis from triggering a 4× send fanout — TDLib serialises these
        // anyway and parallelising just adds JNI contention.
        withContext(ioDispatcher) {
            for (chunk in drained.chunked(MAX_IDS_PER_CALL)) {
                val request = TdApi.GetCustomEmojiStickers(chunk.toLongArray())
                val result = runCatching { td.send(request) }
                    .warnUnlessCancelled(TAG, "GetCustomEmojiStickers(${chunk.size})")
                    .getOrNull() ?: continue
                val byId = HashMap<Long, CustomEmojiSticker>(chunk.size)
                for (sticker in result.stickers.orEmpty()) {
                    val id = (sticker.fullType as? TdApi.StickerFullTypeCustomEmoji)
                        ?.customEmojiId ?: continue
                    byId[id] = sticker.toCustomEmojiSticker(id)
                }
                // Anything chunked but not returned is permanently missing for this session.
                for (id in chunk) if (!byId.containsKey(id)) missing.add(id)
                if (byId.isNotEmpty()) {
                    _stickers.update { it.builder().also { b -> b.putAll(byId) }.build() }
                }
            }
        }
    }

    private companion object {
        const val TAG = "CustomEmojiRepository"
        // Window during which back-to-back UI requests collapse into one TDLib call. 50ms
        // covers a single LazyColumn frame burst (a screen-full of posts mounting at once)
        // without making the user perceive a delay before custom emojis appear.
        const val BATCH_DEBOUNCE_MS = 50L
        const val MAX_IDS_PER_CALL = 200
    }
}

/**
 * UI-friendly view of a `TdApi.Sticker` returned by `GetCustomEmojiStickers`.
 *
 * [thumb] is the sticker's static WEBP/PNG thumbnail — TDLib delivers it in the same
 * response (or has it cached locally), so it can render INSTANTLY while the larger
 * animated [media] file downloads. This is the "instant preview" pattern Telegram itself
 * uses; it's the difference between a chat that pops in stickers and one that flashes
 * blank squares while the .tgs files trickle over the wire.
 *
 * [needsRepainting] reflects `StickerFullTypeCustomEmoji.needsRepainting` — when true, the
 * sticker is monochrome and must be recoloured to the surrounding text colour at render
 * time (Telegram Premium emoji-status / inline status pills behaviour). The renderer
 * applies this via a Compose `ColorFilter`.
 */
@Immutable
data class CustomEmojiSticker(
    val customEmojiId: Long,
    val media: TdMedia,
    val thumb: TdMedia?,
    val format: StickerFormat,
    val needsRepainting: Boolean,
)

internal fun TdApi.Sticker.toCustomEmojiSticker(customEmojiId: Long): CustomEmojiSticker {
    val needsRepainting = (fullType as? TdApi.StickerFullTypeCustomEmoji)?.needsRepainting == true
    val format = when (format) {
        is TdApi.StickerFormatTgs -> StickerFormat.Tgs
        is TdApi.StickerFormatWebm -> StickerFormat.Webm
        else -> StickerFormat.Webp
    }
    val mediaWidth = if (width > 0) width else (thumbnail?.width ?: 0)
    val mediaHeight = if (height > 0) height else (thumbnail?.height ?: 0)
    return CustomEmojiSticker(
        customEmojiId = customEmojiId,
        media = TdMedia(
            fileId = sticker?.id,
            width = mediaWidth,
            height = mediaHeight,
            minithumbBytes = null,
        ),
        thumb = thumbnail?.toMedia(),
        format = format,
        needsRepainting = needsRepainting,
    )
}
