package dev.lyo.hortay.data.proxy

import android.util.Log
import dev.lyo.hortay.R
import dev.lyo.hortay.data.ConnectionStatus
import dev.lyo.hortay.data.StringResolver
import dev.lyo.hortay.data.TdClient
import dev.lyo.hortay.data.TdSender
import dev.lyo.hortay.data.UserMessageBus
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Proxy support, built as a thin driver over TDLib's native proxy API — by design we add almost
 * no logic of our own and lean on TDLib for everything it already does:
 *
 *  - **The pool and its persistence are TDLib's.** `addProxy` / `editProxy` / `removeProxy` /
 *    `enableProxy` / `disableProxy` / `getProxies` store the proxy list in TDLib's own database
 *    and the *enabled* proxy is restored and reconnected on every launch automatically. We keep
 *    NO DataStore mirror — TDLib is the single source of truth. (Consequence: clearing TDLib's
 *    database clears the pool, which is consistent with the app's privacy-as-feature stance.)
 *  - **Link parsing is TDLib's.** `getInternalLinkType` turns any `tg://proxy`, `tg://socks`,
 *    `https://t.me/proxy`, `https://t.me/socks` link into a ready [TdApi.Proxy] — we never parse
 *    proxy URLs by hand. Manual entry maps straight onto `addProxy`.
 *  - **Validation / latency is TDLib's.** `testProxy` (pre-add) and `pingProxy` (added) talk to a
 *    real Telegram DC and return Ok / latency / error.
 *  - **"Set a proxy during authorization" is TDLib's.** Per Aliaksei Levin (tdlib/td#300):
 *    *"Since TDLib 1.3.0 addProxy can be called to enable proxy any time, even before
 *    setTdlibParameters"* — and proxy methods need no network. So the auth-screen entry point
 *    works with zero extra wiring; nothing has to be applied before [TdApi.SetTdlibParameters].
 *
 * The one thing TDLib does NOT do is **rotate** between proxies — it drives a single enabled
 * proxy and retries it forever, and (again per #300) a dead proxy is never reported back through
 * the `addProxy` / `enableProxy` response; it only shows up as the connection never reaching
 * Ready. So failover is genuinely ours, and it rests on two signals: a proactive [test] /
 * [ping] (which return Ok/Error), and a reactive watchdog over [ConnectionStatus] that switches
 * proxy on a stall. The rotation decision itself is the pure [decideFailover].
 *
 * Session-only health ([ProxyHealth]) lives in [health]; TDLib exposes only `isEnabled` /
 * `lastUsedDate`, never latency, so anything richer is ephemeral by construction.
 */
class ProxyRepository(
    private val sender: TdSender,
    private val connection: StateFlow<ConnectionStatus>,
    private val userMessages: UserMessageBus,
    private val scope: CoroutineScope,
    private val res: StringResolver,
) {
    private val _state = MutableStateFlow(ProxyPoolUiState())
    val state: StateFlow<ProxyPoolUiState> = _state.asStateFlow()

    @Volatile
    private var rawProxies: List<TdApi.AddedProxy> = emptyList()
    private val health = ConcurrentHashMap<Int, ProxyHealth>()

    /** Proxies tried-and-stalled in the current failover cycle. Reset on a healthy connection. */
    private val failedThisCycle: MutableSet<Int> = java.util.Collections.synchronizedSet(mutableSetOf())

    init {
        scope.launch { refresh() }
        scope.launch { runFailoverWatchdog() }
    }

    // ── Pool reads ──────────────────────────────────────────────────────────────────────────

    suspend fun refresh() {
        val result = runCatching { sender.send(TdApi.GetProxies()) }
            .getOrElse { it.rethrowIfCancellation(); return }
        rawProxies = result.proxies?.toList().orEmpty()
        rebuild()
    }

    private fun rebuild() {
        _state.value = ProxyPoolUiState(
            entries = rawProxies
                .map { it.toUi(health[it.id] ?: ProxyHealth.Unknown) }
                .toPersistentList(),
            loaded = true,
        )
    }

    // ── Add ─────────────────────────────────────────────────────────────────────────────────

    /** Add from a pasted Telegram proxy link. Parsing is delegated entirely to TDLib. */
    suspend fun addFromLink(link: String): AddResult {
        val type = runCatching { sender.send(TdApi.GetInternalLinkType(link.trim())) }
            .getOrElse { it.rethrowIfCancellation(); return AddResult.Error(genericError()) }
        val proxy = (type as? TdApi.InternalLinkTypeProxy)?.proxy ?: return AddResult.NotAProxyLink
        return addProxy(proxy, enable = rawProxies.isEmpty())
    }

    /** Add from manually-entered fields. */
    suspend fun addManual(draft: ProxyDraft, enable: Boolean): AddResult =
        addProxy(draft.toTdProxy(), enable)

    private suspend fun addProxy(proxy: TdApi.Proxy, enable: Boolean): AddResult =
        try {
            sender.send(TdApi.AddProxy(proxy, enable))
            if (enable) failedThisCycle.clear()
            refresh()
            AddResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e !is TdClient.TdException) Log.w(TAG, "addProxy failed", e)
            AddResult.Error(genericError())
        }

    // ── Mutations ───────────────────────────────────────────────────────────────────────────

    /** User-driven activation — resets the failover cycle. */
    suspend fun enable(id: Int) {
        failedThisCycle.clear()
        sendEnable(id)
    }

    /** Master toggle off → direct connection (the only path to "no proxy"; failover never does this). */
    suspend fun disableProxy() {
        failedThisCycle.clear()
        runCatching { sender.send(TdApi.DisableProxy()) }
            .onFailure { it.rethrowIfCancellation() }
        refresh()
    }

    suspend fun remove(id: Int) {
        runCatching { sender.send(TdApi.RemoveProxy(id)) }
            .onFailure { it.rethrowIfCancellation() }
        health.remove(id)
        refresh()
    }

    private suspend fun sendEnable(id: Int) {
        runCatching { sender.send(TdApi.EnableProxy(id)) }
            .onFailure { it.rethrowIfCancellation() }
        refresh()
    }

    // ── Reachability probes ─────────────────────────────────────────────────────────────────

    /** Ping an already-added proxy and fold the latency into its [ProxyHealth]. */
    suspend fun ping(id: Int): TestResult {
        val entry = _state.value.entries.firstOrNull { it.id == id }
            ?: return TestResult.Error(genericError())
        setHealth(id, ProxyHealth.Checking)
        return runCatching { sender.send(TdApi.PingProxy(entry.toTdProxy())) }
            .fold(
                onSuccess = { secs ->
                    val ms = (secs.seconds * 1000).toLong().coerceAtLeast(0)
                    setHealth(id, ProxyHealth.Reachable(ms))
                    TestResult.Ok(ms)
                },
                onFailure = {
                    it.rethrowIfCancellation()
                    setHealth(id, ProxyHealth.Unreachable)
                    TestResult.Error(genericError())
                },
            )
    }

    /** Validate a not-yet-added proxy against a Telegram DC. */
    suspend fun test(draft: ProxyDraft): TestResult =
        runCatching { sender.send(TdApi.TestProxy(draft.toTdProxy(), TEST_DC_ID, TEST_TIMEOUT_S)) }
            .fold(
                onSuccess = { TestResult.Ok(0L) },
                onFailure = { it.rethrowIfCancellation(); TestResult.Error(genericError()) },
            )

    private fun setHealth(id: Int, value: ProxyHealth) {
        health[id] = value
        rebuild()
    }

    // ── Failover watchdog ───────────────────────────────────────────────────────────────────

    /**
     * Reactive half of failover. `collectLatest` cancels the pending stall timer the instant the
     * connection state changes, so a proxy that reaches Ready inside the window is never failed.
     */
    private suspend fun runFailoverWatchdog() {
        connection.collectLatest { status ->
            val activeId = rawProxies.firstOrNull { it.isEnabled }?.id ?: return@collectLatest
            when (status) {
                ConnectionStatus.Ready -> {
                    failedThisCycle.clear()
                    scope.launch { runCatching { ping(activeId) }.onFailure { it.rethrowIfCancellation() } }
                }
                ConnectionStatus.Updating -> failedThisCycle.clear()
                ConnectionStatus.WaitingForNetwork -> Unit // offline — not the proxy's fault
                ConnectionStatus.Connecting -> {
                    delay(PROXY_WATCHDOG_MS)
                    onProxyStall(activeId)
                }
            }
        }
    }

    private suspend fun onProxyStall(stalledId: Int) {
        val ordered = rawProxies.map { it.id }
        if (ordered.size <= 1) {
            // Nothing to rotate to — keep retrying (TDLib already does) and just say so.
            userMessages.post(res.getString(R.string.proxy_trouble), UserMessageBus.Severity.Warning)
            return
        }
        failedThisCycle.add(stalledId)
        when (val action = decideFailover(ordered, failedThisCycle.toSet())) {
            is FailoverAction.SwitchTo -> {
                Log.w(TAG, "Proxy $stalledId stalled; switching to ${action.proxyId}")
                setHealth(stalledId, ProxyHealth.Unreachable)
                userMessages.post(res.getString(R.string.proxy_switching), UserMessageBus.Severity.Info)
                sendEnable(action.proxyId)
            }
            FailoverAction.RetryCycle -> {
                Log.w(TAG, "All proxies stalled; backing off then retrying the pool")
                failedThisCycle.clear()
                userMessages.post(res.getString(R.string.proxy_all_unreachable), UserMessageBus.Severity.Warning)
                delay(PROXY_RETRY_BACKOFF_MS)
                ordered.firstOrNull()?.let { sendEnable(it) }
            }
        }
    }

    private fun genericError(): String = res.getString(R.string.proxy_error_generic)

    private fun Throwable.rethrowIfCancellation() {
        if (this is CancellationException) throw this
        if (this !is TdClient.TdException) Log.w(TAG, "proxy op failed", this)
    }

    private companion object {
        const val TAG = "ProxyRepository"

        /** A healthy proxy reaches Ready well inside this; a stall past it triggers failover. */
        const val PROXY_WATCHDOG_MS = 12_000L

        /** Pause after the whole pool failed before retrying from the top (avoids a tight loop). */
        const val PROXY_RETRY_BACKOFF_MS = 8_000L

        /** Telegram has DCs 1–5; 2 is a safe default target for a reachability test. */
        const val TEST_DC_ID = 2
        const val TEST_TIMEOUT_S = 10.0
    }
}
