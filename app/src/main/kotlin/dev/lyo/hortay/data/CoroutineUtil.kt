package dev.lyo.hortay.data

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * Log non-cancellation failures and rethrow [CancellationException]. The kotlinx.coroutines
 * canonical rule is "never swallow CancellationException" — doing so detaches a coroutine
 * from its parent's cancellation, which would log [LeftCompositionCancellationException] as
 * if it were a real failure (it isn't — composables leaving the tree do that on every nav).
 */
internal fun <T> Result<T>.warnUnlessCancelled(tag: String, label: String = ""): Result<T> {
    val e = exceptionOrNull() ?: return this
    if (e is CancellationException) throw e
    Log.w(tag, if (label.isEmpty()) "failed" else "$label failed", e)
    return this
}

/** Same as [warnUnlessCancelled] but with a fixed [PostsRepository]-style "TAG". */
internal fun <T> Result<T>.warnUnlessCancelled(label: String = ""): Result<T> =
    warnUnlessCancelled(tag = "Hortay", label = label)
