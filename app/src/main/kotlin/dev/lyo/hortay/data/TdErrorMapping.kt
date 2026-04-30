package dev.lyo.hortay.data

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
 * Translate any [Throwable] (TDLib-originated or otherwise) into a user-facing
 * Ukrainian message tied to a specific [operation] verb in the infinitive form
 * ("оновити стрічку", "перекласти повідомлення", "приєднатися до каналу"). Returning
 * a [Pair] keeps the [TdErrorKind] available for callers that want to suppress
 * presentation when the network is already known to be down.
 *
 * [CancellationException] is preserved by re-throwing — silently swallowing it would
 * break structured concurrency.
 */
fun Throwable.toUserFacing(operation: String): Pair<TdErrorKind, String> {
    if (this is CancellationException) throw this
    val code = (this as? TdClient.TdException)?.code ?: 0
    val raw = stripCode(this.message.orEmpty())

    return when {
        code == TdClient.FLOOD_WAIT_CODE -> {
            val seconds = parseLeadingDigits(raw)
            val human = seconds?.let { humaniseSeconds(it) }
            val msg = if (human != null) "Telegram обмежив запити. Спробуйте за $human."
                else "Telegram обмежив запити. Спробуйте трохи пізніше."
            TdErrorKind.FloodWait to msg
        }
        code == 401 -> TdErrorKind.AccessDenied to "Сесію скинуто. Авторизуйтесь знову."
        code == 403 -> TdErrorKind.AccessDenied to "Не можна $operation: дію заборонено."
        code == 404 -> TdErrorKind.NotFound to "Не знайдено: $operation."
        code in 500..599 -> TdErrorKind.ServerError to "Сервер Telegram тимчасово недоступний. Спробуйте ще раз."
        // 0 covers non-TdException throwables (timeouts, JNI quirks, IO exceptions). The
        // underlying cause is usually a network blip — call it that explicitly so the
        // message lines up with the connection banner's wording.
        code == 0 -> TdErrorKind.Network to "Немає звʼязку. Перевірте Інтернет і спробуйте ще раз."
        else -> TdErrorKind.Unknown to "Не вдалося $operation. ($code)"
    }
}

/** TdException stores `[code] message` — strip the bracketed prefix to get the raw token. */
private fun stripCode(full: String): String {
    val close = full.indexOf(']')
    return if (close >= 0 && full.startsWith('[')) full.substring(close + 1).trim() else full.trim()
}

private fun parseLeadingDigits(text: String): Long? =
    Regex("(\\d+)").find(text)?.value?.toLongOrNull()

private fun humaniseSeconds(total: Long): String = when {
    total < 60 -> "${total}с"
    total < 3600 -> "${total / 60} хв"
    else -> "${total / 3600} год"
}

/**
 * Convenience: classify a throwable, then post to [bus] only when the user actually
 * benefits from a Snackbar. Currently we suppress:
 *   • [TdErrorKind.Cancelled] — coroutine cancellation is not an error.
 *   • [TdErrorKind.Network] when the connection banner already shows "Очікує мережі"
 *     — duplicate UI. The banner state is the source of truth; piling a Snackbar on
 *     top reads as panic.
 *
 * Use this from any user-initiated repository call site. Background ops
 * (interaction-info coalesce, viewMessages, prefetch) should NOT use this — they
 * stay silent and rely on the connection banner for status.
 */
fun Throwable.surfaceTo(
    bus: UserMessageBus,
    operation: String,
    connection: ConnectionStatus,
) {
    val (kind, msg) = try { toUserFacing(operation) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
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
