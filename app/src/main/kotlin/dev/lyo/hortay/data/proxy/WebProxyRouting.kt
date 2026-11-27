package dev.lyo.hortay.data.proxy

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Routes the app's one shared web OkHttp client ([dev.lyo.hortay.AppGraph.webHttpClient] and the
 * clients derived from it with `newBuilder()`) through the proxy TDLib has enabled, so guest mode
 * works where t.me is blocked and the device's IP doesn't leak around a proxy the user set up.
 *
 * **One pool for both modes.** TDLib is created and started in every mode — guest mode just
 * leaves it at `authorizationStateWaitPhoneNumber` — and its proxy methods work before any
 * authorization (tdlib/td#300, see [ProxyRepository]). So [ProxyRepository.state] is live in
 * guest mode too, and this class only ever reads that snapshot: no TDLib call per request, no
 * second proxy list.
 *
 * **Mechanics** (installed once by [install]; all four pieces read the same volatile [Snapshot]):
 *  - HTTP-with-CONNECT → [selector] hands OkHttp the proxy and OkHttp tunnels itself;
 *    [authenticator] answers the 407 (and OkHttp's preemptive CONNECT challenge) with Basic
 *    credentials. Per-client, unlike `java.net.Authenticator`.
 *  - SOCKS5 → the selector says DIRECT, [dns] returns a per-host placeholder address
 *    (240.x.y.z, reserved class E, never routable) carrying the host NAME, and [socketFactory]'s
 *    socket connects to the proxy and runs [Socks5Handshake] with that name — so the proxy does
 *    the DNS, credentials stay inside this client, and nothing global is touched. The JDK's own
 *    SOCKS socket can't do this: OkHttp builds it with `Socket(proxy)` (bypassing the socket
 *    factory) and it reads credentials only from the process-global `java.net.Authenticator`.
 *    The placeholder is distinct per host on purpose: OkHttp coalesces HTTP/2 connections whose
 *    DIRECT routes share an IP, and one shared placeholder would let cdn1/cdn4.telesco.pe (one
 *    wildcard certificate, different servers) share a connection.
 *  - MTProto / HTTP-only / none → plain DIRECT (see [WebProxyRoute]).
 *
 * **Never a silent direct fallback.** With a usable proxy enabled the selector returns ONLY the
 * proxy (OkHttp tries DIRECT only for an empty list), and every socket checks at connect time
 * that its target fits the route it was created under — a placeholder can't be dialled directly
 * and a DIRECT target can't slip out while an HTTP proxy is enabled. A dead proxy therefore
 * surfaces as an ordinary network error through the existing guest error states.
 *
 * **A change applies at once.** On every route change all sockets opened under the previous
 * route are closed (in-flight requests fail and are retried by their callers) and listeners —
 * [dev.lyo.hortay.AppGraph] evicts the connection pool — run, so no pooled connection keeps
 * using the old route. The shared [okhttp3.Cache] and connection pool are unaffected by the
 * selector itself, which is why this is one selector-driven client rather than a client per route.
 *
 * **Cold start.** TDLib loads the pool asynchronously (`getProxies`, offline, milliseconds), so
 * the first request waits up to [loadTimeoutMs] for it rather than racing out directly. If the
 * pool never loads (TDLib itself is broken, so the signed-in mode is down too) the gate opens
 * once, logged, and traffic goes direct — that is the one documented direct path.
 */
class WebProxyRouting(
    state: StateFlow<ProxyPoolUiState>,
    scope: CoroutineScope,
    private val loadTimeoutMs: Long = DEFAULT_LOAD_TIMEOUT_MS,
) {
    /** The route plus a generation bumped on every change; sockets remember the one they began in. */
    data class Snapshot(val route: WebProxyRoute, val generation: Int)

    @Volatile
    private var snapshot = Snapshot(WebProxyRoute.Direct, generation = 0)

    private val loaded = CountDownLatch(1)

    @Volatile
    private var gateOpen = false

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val openSockets: MutableSet<RoutedSocket> = Collections.newSetFromMap(WeakHashMap())

    init {
        state.value.takeIf { it.loaded }?.let { apply(it.webRoute()) }
        scope.launch {
            state.filter { it.loaded }
                .map { it.webRoute() }
                .distinctUntilChanged()
                .collect { apply(it) }
        }
    }

    /** Current route; the first call(s) wait for TDLib's pool to load (see class KDoc). */
    fun current(): Snapshot {
        if (!gateOpen) awaitLoaded()
        return snapshot
    }

    /** Called after every route change, on the collecting coroutine. */
    fun onRouteChanged(listener: () -> Unit) {
        listeners += listener
    }

    /** Install the selector, authenticator, DNS and socket factory on [builder]. */
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .proxySelector(selector)
        .proxyAuthenticator(authenticator)
        .dns(dns)
        .socketFactory(socketFactory)

    private fun awaitLoaded() {
        val ok = runCatching { loaded.await(loadTimeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!ok && !gateOpen) Log.w(TAG, "Proxy pool not loaded after ${loadTimeoutMs}ms; going direct")
        gateOpen = true
    }

    private fun apply(route: WebProxyRoute) {
        val previous = snapshot
        if (route == previous.route) {
            loaded.countDown()
            return
        }
        val next = Snapshot(route, previous.generation + 1)
        snapshot = next
        loaded.countDown()
        val stale = synchronized(openSockets) { openSockets.filter { it.generation != next.generation } }
        stale.forEach { runCatching { it.close() } }
        listeners.forEach { listener -> runCatching { listener() }.onFailure { Log.w(TAG, "listener failed", it) } }
    }

    internal val selector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = when (val route = current().route) {
            is WebProxyRoute.Http ->
                listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(route.host, route.port)))
            // SOCKS5 runs inside our socket (see class KDoc), so OkHttp sees DIRECT.
            is WebProxyRoute.Socks5, WebProxyRoute.Direct -> listOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    internal val authenticator: Authenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            val proxy = current().route as? WebProxyRoute.Http ?: return null
            if (proxy.username.isEmpty()) return null
            // Already sent credentials and still got 407 — they are wrong; give up, don't loop.
            if (response.request.header(PROXY_AUTHORIZATION) != null) return null
            return response.request.newBuilder()
                .header(PROXY_AUTHORIZATION, Credentials.basic(proxy.username, proxy.password))
                .build()
        }
    }

    internal val dns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = when (current().route) {
            is WebProxyRoute.Socks5 -> listOf(placeholderFor(hostname))
            is WebProxyRoute.Http, WebProxyRoute.Direct -> Dns.SYSTEM.lookup(hostname)
        }
    }

    internal val socketFactory: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket {
            val socket = RoutedSocket(current())
            synchronized(openSockets) { openSockets += socket }
            return socket
        }

        override fun createSocket(host: String, port: Int): Socket =
            createSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            createSocket().apply {
                bind(InetSocketAddress(localHost, localPort))
                connect(InetSocketAddress(host, port))
            }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            createSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            createSocket().apply {
                bind(InetSocketAddress(localAddress, localPort))
                connect(InetSocketAddress(address, port))
            }
    }

    /** A socket bound to the route it was created under; see the class KDoc for the checks. */
    private inner class RoutedSocket(private val born: Snapshot) : Socket() {
        val generation: Int get() = born.generation

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val target = endpoint as? InetSocketAddress ?: throw IOException("Unsupported address")
            when (val route = born.route) {
                WebProxyRoute.Direct -> {
                    if (isPlaceholder(target)) throw IOException("Proxy changed; retry")
                    super.connect(target, timeout)
                }
                is WebProxyRoute.Http -> {
                    if (!targetsProxy(target, route.host, route.port)) throw IOException("Proxy changed; retry")
                    super.connect(target, timeout)
                }
                is WebProxyRoute.Socks5 -> connectViaSocks(target, route, timeout)
            }
            if (snapshot.generation != born.generation) {
                close()
                throw IOException("Proxy changed; retry")
            }
        }

        private fun connectViaSocks(target: InetSocketAddress, route: WebProxyRoute.Socks5, timeout: Int) {
            super.connect(InetSocketAddress(route.host, route.port), timeout)
            val previousSoTimeout = soTimeout
            if (timeout > 0) soTimeout = timeout
            try {
                val resolved = target.address?.takeUnless { isPlaceholder(target) }
                Socks5Handshake.connect(
                    input = getInputStream(),
                    output = getOutputStream(),
                    host = target.hostString,
                    address = resolved,
                    port = target.port,
                    username = route.username,
                    password = route.password,
                )
            } catch (e: IOException) {
                close()
                throw e
            }
            soTimeout = previousSoTimeout
        }

        override fun close() {
            synchronized(openSockets) { openSockets -= this }
            super.close()
        }
    }

    internal companion object {
        private const val TAG = "WebProxyRouting"
        private const val PROXY_AUTHORIZATION = "Proxy-Authorization"
        const val DEFAULT_LOAD_TIMEOUT_MS = 5_000L
        private const val PLACEHOLDER_FIRST_OCTET = 240
        private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        /** Per-host 240.x.y.z placeholder carrying [hostname]; see the class KDoc. */
        fun placeholderFor(hostname: String): InetAddress {
            val h = hostname.lowercase().hashCode()
            val bytes = byteArrayOf(
                PLACEHOLDER_FIRST_OCTET.toByte(),
                (h ushr 16).toByte(),
                (h ushr 8).toByte(),
                h.toByte(),
            )
            return InetAddress.getByAddress(hostname, bytes)
        }

        fun isPlaceholder(target: InetSocketAddress): Boolean {
            val raw = target.address?.address ?: return false
            return raw.size == 4 && (raw[0].toInt() and 0xFF) == PLACEHOLDER_FIRST_OCTET
        }

        /** Whether a DIRECT dial to [target] is the dial to the HTTP proxy itself. */
        fun targetsProxy(target: InetSocketAddress, host: String, port: Int): Boolean {
            if (target.port != port) return false
            if (target.hostString.equals(host, ignoreCase = true)) return true
            // Only an IP literal may be parsed here — getByName on a name would be a DNS query.
            val literal = host.removeSurrounding("[", "]")
            if (!IPV4_LITERAL.matches(literal) && !literal.contains(':')) return false
            val parsed = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
            return parsed == target.address
        }
    }
}
