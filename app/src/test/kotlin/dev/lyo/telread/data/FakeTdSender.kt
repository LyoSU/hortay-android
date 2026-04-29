package dev.lyo.telread.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.drinkless.tdlib.TdApi

/**
 * In-memory [TdSender] for tests. Each request is matched against a queue of canned
 * answers; updates are pushed via [emitUpdate]. Unknown requests fail loudly so a missed
 * stub surfaces immediately instead of silently returning defaults.
 */
class FakeTdSender : TdSender {
    private val responders = ArrayDeque<(TdApi.Function<*>) -> TdApi.Object?>()
    private val _updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 64)
    override val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    /** Register a one-shot responder. They fire FIFO — call in the order of expected calls. */
    fun onNext(handle: (TdApi.Function<*>) -> TdApi.Object) {
        responders.addLast(handle)
    }

    suspend fun emitUpdate(update: TdApi.Update) {
        _updates.emit(update)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T {
        val responder = responders.removeFirstOrNull()
            ?: error("Unexpected TdSender.send: ${query::class.simpleName} — register a responder via onNext()")
        return responder(query) as T
    }
}
