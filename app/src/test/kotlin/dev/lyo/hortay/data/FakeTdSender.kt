package dev.lyo.hortay.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [TdSender] for tests. Each request is matched against a queue of canned
 * answers; updates are pushed via [emitUpdate]. Unknown requests fail loudly so a missed
 * stub surfaces immediately instead of silently returning defaults.
 *
 * Every successful `send()` is counted by the request's simple class name in [rpcCounts]
 * so tests can assert on RPC budget (e.g. "zero GetChatHistory calls were made").
 */
class FakeTdSender : TdSender {
    private val responders = java.util.concurrent.ConcurrentLinkedDeque<(TdApi.Function<*>) -> TdApi.Object?>()
    private val _updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 64)
    override val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    private val _rpcCounts = ConcurrentHashMap<String, Int>()
    val rpcCounts: Map<String, Int> get() = _rpcCounts.toMap()
    fun rpcCount(name: String): Int = _rpcCounts[name] ?: 0

    /** Register a one-shot responder. They fire FIFO — call in the order of expected calls. */
    fun onNext(handle: (TdApi.Function<*>) -> TdApi.Object) {
        responders.addLast(handle)
    }

    /**
     * Register a default responder for any request whose class simple name matches [name].
     * Used for "I don't care about ordering, just answer this RPC type with the same canned
     * value every time it's asked." Falls through to the FIFO `onNext` queue first; only
     * consulted if no FIFO responder is registered.
     */
    private val defaults = ConcurrentHashMap<String, (TdApi.Function<*>) -> TdApi.Object>()
    fun onAny(name: String, handle: (TdApi.Function<*>) -> TdApi.Object) {
        defaults[name] = handle
    }

    suspend fun emitUpdate(update: TdApi.Update) {
        _updates.emit(update)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T {
        val name = query::class.simpleName ?: "Unknown"
        _rpcCounts.merge(name, 1) { a, b -> a + b }
        val responder = responders.pollFirst()
            ?: defaults[name]
            ?: error("Unexpected TdSender.send: $name — register a responder via onNext() or onAny()")
        val result = responder(query)
        // Mirror real [TdClient.send] behaviour: a [TdApi.Error] response is
        // surfaced as a [TdClient.TdException], not returned as a value.
        // Without this, a responder that returns `TdApi.Error(420, ...)` would
        // be silently miscast through generic erasure to T, and callers that
        // use `runCatching { td.send(...) }` to detect rate-limit / 404 / etc.
        // codes would never observe the error.
        if (result is TdApi.Error) {
            throw TdClient.TdException(result.code, result.message)
        }
        return result as T
    }
}
