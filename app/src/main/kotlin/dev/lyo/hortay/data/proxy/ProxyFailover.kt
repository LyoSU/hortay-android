package dev.lyo.hortay.data.proxy

/**
 * Pure rotation policy for [ProxyRepository]'s failover watchdog.
 *
 * Failover is the one piece TDLib can't do for us: it drives exactly one enabled proxy and
 * retries it forever, with no notion of a pool to rotate through (and no error callback when a
 * proxy is dead — see [ProxyRepository] KDoc). So we watch the connection state and, on a stall,
 * ask this function what to switch to next. Keeping the decision pure makes the rotation testable
 * without a live TDLib client or the connection-state machine.
 */
sealed interface FailoverAction {
    /** Enable this proxy next — the first one in rotation order not yet tried this cycle. */
    data class SwitchTo(val proxyId: Int) : FailoverAction

    /**
     * Every proxy in the pool failed this cycle. Caller should clear the failed set, back off,
     * and retry from the top.
     *
     * We intentionally never fall back to a *direct* connection here: a proxy user is often
     * behind censorship, where a silent direct attempt would both fail to connect and leak the
     * connection it was meant to hide. Going direct stays an explicit user action (the master
     * toggle in the UI), never an automatic consequence of a dead proxy.
     */
    data object RetryCycle : FailoverAction
}

/**
 * @param orderedProxyIds the pool in rotation order (TDLib's `getProxies` order).
 * @param failedIds proxies already tried — and failed — this cycle (the just-stalled one included).
 */
fun decideFailover(
    orderedProxyIds: List<Int>,
    failedIds: Set<Int>,
): FailoverAction {
    val next = orderedProxyIds.firstOrNull { it !in failedIds }
    return if (next != null) FailoverAction.SwitchTo(next) else FailoverAction.RetryCycle
}
