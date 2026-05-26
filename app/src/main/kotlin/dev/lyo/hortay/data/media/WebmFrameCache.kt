package dev.lyo.hortay.data.media

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-wide decoded-loop cache, keyed by (id, sizePx). Decodes once off-thread and shares the
 *  result across every visible instance of the same sticker/emoji. Byte-bounded LRU so a long
 *  sticker-heavy scroll can't grow unbounded. [decodeDispatcher] is injectable for tests. */
class WebmFrameCache(
    private val scope: CoroutineScope,
    private val maxBytes: Long = 24L * 1024 * 1024,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val decode: (path: String, sizePx: Int) -> DecodedWebm? = WebmAlphaDecoder::decode,
) {
    data class Key(val id: String, val sizePx: Int)
    private val flows = HashMap<Key, MutableStateFlow<DecodedWebm?>>()
    private val lru = LinkedHashMap<Key, DecodedWebm>(16, 0.75f, true)
    private val inFlight = HashSet<Key>()
    private val mutex = Mutex()
    private var bytes = 0L

    /** Observe decoded frames for (id,size). First observer triggers an off-thread decode of [path];
     *  emits null until ready, then the [DecodedWebm]. Safe to call from composition. */
    fun observe(key: Key, path: String): StateFlow<DecodedWebm?> {
        val flow = synchronized(flows) { flows.getOrPut(key) { MutableStateFlow(lru[key]) } }
        if (flow.value == null) ensure(key, path)
        return flow
    }

    private fun ensure(key: Key, path: String) {
        scope.launch(decodeDispatcher) {
            mutex.withLock { if (key in inFlight || lru[key] != null) return@launch; inFlight += key }
            val decoded = runCatching { decode(path, key.sizePx) }.getOrNull()
            mutex.withLock {
                inFlight -= key
                if (decoded != null) {
                    lru[key] = decoded
                    bytes += sizeOf(decoded.frames.size, decoded.width, decoded.height)
                    evictDown()
                    synchronized(flows) { flows[key] }?.value = decoded
                }
            }
        }
    }

    private fun sizeOf(frames: Int, w: Int, h: Int): Long = frames.toLong() * w * h * 4
    private fun evictDown() {
        val it = lru.entries.iterator()
        while (bytes > maxBytes && it.hasNext()) {
            val e = it.next()
            bytes -= sizeOf(e.value.frames.size, e.value.width, e.value.height)
            it.remove()
            synchronized(flows) { flows[e.key] }?.value = null
        }
    }
    internal fun currentBytes() = bytes
}
