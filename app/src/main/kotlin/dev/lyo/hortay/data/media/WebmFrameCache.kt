package dev.lyo.hortay.data.media

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Process-wide decoded-loop cache, keyed by (id, widthPx, heightPx). Decodes once off-thread and
 *  shares the result across every visible instance of the same sticker/emoji at the same pixel box.
 *  Byte-bounded LRU so a long sticker-heavy scroll can't grow unbounded. [decodeDispatcher] is
 *  injectable for tests.
 *
 *  Threading: a SINGLE monitor ([lock]) guards [flows], [lru], [inFlight] and [bytes]. [observe]
 *  runs on the composition thread; the decode continuation runs on [decodeDispatcher]. They touch
 *  the same maps, so they must share one lock — an earlier split (synchronized + Mutex) raced on
 *  the access-ordered [lru], whose `get()` structurally relinks nodes. The critical sections never
 *  suspend (the decode itself runs outside the lock), so a plain `synchronized` is correct and
 *  cheaper than a coroutine Mutex.
 *
 *  Eviction trade-off: dropping an entry nulls its flow even if a composable is still observing it,
 *  so a sticker evicted while on-screen goes blank until recomposition re-observes (and re-decodes).
 *  That's the price of actually releasing the bitmap bytes — keeping the flow would pin the decoded
 *  frames the counter already considers freed. With (w,h) keying and the 24 MB budget this only
 *  bites under genuine memory pressure. */
class WebmFrameCache(
    private val scope: CoroutineScope,
    private val maxBytes: Long = 24L * 1024 * 1024,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val decode: (path: String, widthPx: Int, heightPx: Int) -> DecodedWebm? =
        WebmAlphaDecoder::decode,
) {
    data class Key(val id: String, val widthPx: Int, val heightPx: Int)

    private val lock = Any()
    private val flows = HashMap<Key, MutableStateFlow<DecodedWebm?>>()
    private val lru = LinkedHashMap<Key, DecodedWebm>(16, 0.75f, true)
    private val inFlight = HashSet<Key>()
    private var bytes = 0L

    /** Observe decoded frames for (id, w, h). First observer triggers an off-thread decode of
     *  [path]; emits null until ready, then the [DecodedWebm]. Safe to call from composition. */
    fun observe(key: Key, path: String): StateFlow<DecodedWebm?> {
        val flow: MutableStateFlow<DecodedWebm?>
        val needDecode: Boolean
        synchronized(lock) {
            flow = flows.getOrPut(key) { MutableStateFlow(lru[key]) }
            needDecode = flow.value == null && key !in inFlight
            if (needDecode) inFlight += key
        }
        if (needDecode) launchDecode(key, path)
        return flow
    }

    private fun launchDecode(key: Key, path: String) {
        scope.launch(decodeDispatcher) {
            val decoded = runCatching { decode(path, key.widthPx, key.heightPx) }.getOrNull()
            synchronized(lock) {
                inFlight -= key
                if (decoded != null) {
                    lru[key] = decoded
                    bytes += sizeOf(decoded)
                    evictDown()
                    flows[key]?.value = decoded
                }
            }
        }
    }

    private fun sizeOf(d: DecodedWebm): Long = d.frames.size.toLong() * d.width * d.height * 4

    /** Caller holds [lock]. */
    private fun evictDown() {
        val it = lru.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next()
            bytes -= sizeOf(e.value)
            it.remove()
            // Prune the flow entry too (not just null its value) so it doesn't leak across a long
            // session and a later observe() of the same key re-decodes from scratch.
            flows.remove(e.key)?.value = null
        }
    }

    internal fun currentBytes() = synchronized(lock) { bytes }
}
