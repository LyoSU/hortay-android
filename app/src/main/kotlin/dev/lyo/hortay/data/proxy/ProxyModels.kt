package dev.lyo.hortay.data.proxy

import androidx.compose.runtime.Immutable
import org.drinkless.tdlib.TdApi

/**
 * Thin domain layer over TDLib's proxy API. Deliberately small: TDLib owns the proxy pool and
 * persists it in its own database (see [ProxyRepository] KDoc), so these types only mirror what
 * TDLib hands back ([TdApi.AddedProxy]) plus an ephemeral, session-only health annotation that
 * TDLib does not track itself.
 */

/** A proxy's transport flavour — 1:1 with [TdApi.ProxyType]. */
@Immutable
sealed interface ProxyKind {
    @Immutable
    data class Socks5(val username: String, val password: String) : ProxyKind

    @Immutable
    data class Http(val username: String, val password: String, val httpOnly: Boolean) : ProxyKind

    @Immutable
    data class Mtproto(val secret: String) : ProxyKind
}

/**
 * Reachability of a proxy as observed *this session*. Not persisted — TDLib only exposes
 * `lastUsedDate` / `isEnabled`, never latency or a health verdict, so anything richer is ours and
 * intentionally transient.
 */
@Immutable
sealed interface ProxyHealth {
    /** Never tested this session. */
    data object Unknown : ProxyHealth

    /** A ping / test is in flight. */
    data object Checking : ProxyHealth

    /** Reachable; [latencyMs] is the round-trip to a Telegram DC through this proxy. */
    @Immutable
    data class Reachable(val latencyMs: Long) : ProxyHealth

    /** Last ping / test failed. */
    data object Unreachable : ProxyHealth
}

/** A proxy in TDLib's pool, projected for the UI. [id] is TDLib's own proxy identifier. */
@Immutable
data class ProxyUi(
    val id: Int,
    val server: String,
    val port: Int,
    val kind: ProxyKind,
    val isEnabled: Boolean,
    val health: ProxyHealth,
)

/** Snapshot of the whole pool. [useProxy] is derived — TDLib has exactly one enabled proxy at most. */
@Immutable
data class ProxyPoolUiState(
    val entries: kotlinx.collections.immutable.PersistentList<ProxyUi> =
        kotlinx.collections.immutable.persistentListOf(),
    val loaded: Boolean = false,
) {
    val useProxy: Boolean get() = entries.any { it.isEnabled }
}

/** Manually-entered proxy, before it is handed to `addProxy`. */
@Immutable
data class ProxyDraft(
    val server: String,
    val port: Int,
    val kind: ProxyKind,
) {
    fun toTdProxy(): TdApi.Proxy = TdApi.Proxy(server.trim(), port, kind.toTdType())
}

/** Outcome of an add (link or manual). */
sealed interface AddResult {
    data object Success : AddResult

    /** The pasted text resolved to a Telegram link, but not a proxy one. */
    data object NotAProxyLink : AddResult

    data class Error(val message: String) : AddResult
}

/** Outcome of a reachability probe (`pingProxy` / `testProxy`). */
sealed interface TestResult {
    data class Ok(val latencyMs: Long) : TestResult

    data class Error(val message: String) : TestResult
}

// ── TDLib mappers ───────────────────────────────────────────────────────────────────────────

fun TdApi.ProxyType.toKind(): ProxyKind = when (this) {
    is TdApi.ProxyTypeSocks5 -> ProxyKind.Socks5(username.orEmpty(), password.orEmpty())
    is TdApi.ProxyTypeHttp -> ProxyKind.Http(username.orEmpty(), password.orEmpty(), httpOnly)
    is TdApi.ProxyTypeMtproto -> ProxyKind.Mtproto(secret.orEmpty())
    else -> ProxyKind.Socks5("", "")
}

fun ProxyKind.toTdType(): TdApi.ProxyType = when (this) {
    is ProxyKind.Socks5 -> TdApi.ProxyTypeSocks5(username, password)
    is ProxyKind.Http -> TdApi.ProxyTypeHttp(username, password, httpOnly)
    is ProxyKind.Mtproto -> TdApi.ProxyTypeMtproto(secret)
}

fun ProxyUi.toTdProxy(): TdApi.Proxy = TdApi.Proxy(server, port, kind.toTdType())

fun TdApi.AddedProxy.toUi(health: ProxyHealth): ProxyUi = ProxyUi(
    id = id,
    server = proxy.server.orEmpty(),
    port = proxy.port,
    kind = proxy.type.toKind(),
    isEnabled = isEnabled,
    health = health,
)
