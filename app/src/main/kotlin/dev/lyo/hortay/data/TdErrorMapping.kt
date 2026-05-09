package dev.lyo.hortay.data

import androidx.annotation.StringRes
import dev.lyo.hortay.R
import kotlinx.coroutines.CancellationException

/**
 * Categorisation of a TDLib failure into a small set of user-meaningful kinds. The
 * raw [TdClient.TdException.code] is too granular to drive UI off — an error code
 * tells you the protocol bucket but not "should I tell the user, should I retry,
 * should I shrug it off because they're offline anyway".
 *
 * UI uses [TdErrorKind] to decide:
 *   • Whether to surface a Snackbar at all (e.g. [Network] is suppressed when the
 *     connection banner already explains the situation).
 *   • What tone to use (transient/server vs. user-actionable).
 *   • Whether to offer a retry control.
 */
enum class TdErrorKind {
    Network,        // No connection or upstream MTProto link is down — banner already says so.
    FloodWait,      // Telegram per-method rate limit. Throttle, inform user.
    AccessDenied,   // 401 / 403 — auth needed or operation forbidden.
    NotFound,       // 404 — chat/message/file deleted or never existed for this account.
    ServerError,    // 500..599 — Telegram side, transient.
    Cancelled,      // Coroutine cancellation; not a real error to surface.
    Unknown,        // Anything we haven't categorised yet.
}

/**
 * Translate any [Throwable] (TDLib-originated or otherwise) into a user-facing,
 * locale-resolved message tied to a specific [operationRes] verb in the infinitive
 * form (e.g. R.string.op_refresh_feed → "refresh the feed" / "оновити стрічку").
 * Returning a [Pair] keeps the [TdErrorKind] available for callers that want to
 * suppress presentation when the network is already known to be down.
 *
 * [CancellationException] is preserved by re-throwing — silently swallowing it would
 * break structured concurrency.
 */
fun Throwable.toUserFacing(
    res: StringResolver,
    @StringRes operationRes: Int,
): Pair<TdErrorKind, String> {
    if (this is CancellationException) throw this
    val code = (this as? TdClient.TdException)?.code ?: 0
    val raw = stripCode(this.message.orEmpty())
    val operation = res.getString(operationRes)

    return when {
        // Both 420 (legacy MTProto) and 429 (translated layer) signify rate limiting;
        // see [TdClient.isFloodWaitCode] for the rationale.
        TdClient.isFloodWaitCode(code) -> {
            val seconds = parseLeadingDigits(raw)
            val human = seconds?.let { humaniseSeconds(res, it) }
            val msg = if (human != null) res.getString(R.string.err_flood_with_time, human)
            else res.getString(R.string.err_flood_generic)
            TdErrorKind.FloodWait to msg
        }
        code == 401 -> TdErrorKind.AccessDenied to res.getString(R.string.err_unauthorized)
        code == 403 -> TdErrorKind.AccessDenied to res.getString(R.string.err_forbidden, operation)
        code == 404 -> TdErrorKind.NotFound to res.getString(R.string.err_not_found, operation)
        code in 500..599 -> TdErrorKind.ServerError to res.getString(R.string.err_server)
        // 0 covers non-TdException throwables (timeouts, JNI quirks, IO exceptions). The
        // underlying cause is usually a network blip — call it that explicitly so the
        // message lines up with the connection banner's wording.
        code == 0 -> TdErrorKind.Network to res.getString(R.string.err_no_connection)
        else -> TdErrorKind.Unknown to res.getString(R.string.err_failed_op, operation, code)
    }
}

/** TdException stores `[code] message` — strip the bracketed prefix to get the raw token. */
private fun stripCode(full: String): String {
    val close = full.indexOf(']')
    return if (close >= 0 && full.startsWith('[')) full.substring(close + 1).trim() else full.trim()
}

private fun parseLeadingDigits(text: String): Long? =
    Regex("(\\d+)").find(text)?.value?.toLongOrNull()

private fun humaniseSeconds(res: StringResolver, total: Long): String = when {
    total < 60 -> res.getString(R.string.duration_seconds_short, total.toInt())
    total < 3600 -> res.getString(R.string.duration_minutes_short, (total / 60).toInt())
    else -> res.getString(R.string.duration_hours_short, (total / 3600).toInt())
}

/**
 * Convenience: classify a throwable, then post to [bus] only when the user actually
 * benefits from a Snackbar. Currently we suppress:
 *   • [TdErrorKind.Cancelled] — coroutine cancellation is not an error.
 *   • [TdErrorKind.Network] when the connection banner already shows "Waiting for
 *     network" / "Очікує мережі" — duplicate UI. The banner state is the source of
 *     truth; piling a Snackbar on top reads as panic.
 *
 * Use this from any user-initiated repository call site. Background ops
 * (interaction-info coalesce, viewMessages, prefetch) should NOT use this — they
 * stay silent and rely on the connection banner for status.
 */
fun Throwable.surfaceTo(
    bus: UserMessageBus,
    res: StringResolver,
    @StringRes operationRes: Int,
    connection: ConnectionStatus,
) {
    val (kind, msg) = try {
        toUserFacing(res, operationRes)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    when {
        kind == TdErrorKind.Cancelled -> return
        kind == TdErrorKind.Network && connection == ConnectionStatus.WaitingForNetwork -> return
        else -> bus.post(msg, severityFor(kind))
    }
}

private fun severityFor(kind: TdErrorKind): UserMessageBus.Severity = when (kind) {
    TdErrorKind.FloodWait, TdErrorKind.ServerError -> UserMessageBus.Severity.Warning
    TdErrorKind.Network -> UserMessageBus.Severity.Info
    else -> UserMessageBus.Severity.Error
}
