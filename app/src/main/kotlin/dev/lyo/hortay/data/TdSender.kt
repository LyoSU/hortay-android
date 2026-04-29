package dev.lyo.hortay.data

import kotlinx.coroutines.flow.SharedFlow
import org.drinkless.tdlib.TdApi

/**
 * Narrow surface that repositories actually need from the TDLib daemon: a request/response
 * channel and a hot stream of updates. Pulling this out of [TdClient] (which also owns auth
 * flow, process lifecycle and JNI startup) lets tests swap in a fake without standing up an
 * actual native client — and keeps the repo layer free of auth concerns.
 */
interface TdSender {
    suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T
    val updates: SharedFlow<TdApi.Update>
}
