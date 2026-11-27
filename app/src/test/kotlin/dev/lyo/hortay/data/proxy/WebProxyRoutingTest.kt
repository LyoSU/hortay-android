package dev.lyo.hortay.data.proxy

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Locks how the app's own HTTPS follows TDLib's enabled proxy: the per-kind mapping (SOCKS5 and
 * HTTP-with-CONNECT carry it; HTTP-only and MTProto go direct), the selector never offering a
 * direct fallback, Basic proxy auth that gives up instead of looping, the SOCKS5 handshake with
 * remote DNS, and connection-pool eviction on a route change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebProxyRoutingTest {

    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = scope.cancel()

    private fun entry(kind: ProxyKind, enabled: Boolean = true, server: String = "proxy.example", port: Int = 1080) =
        ProxyUi(id = 1, server = server, port = port, kind = kind, isEnabled = enabled, health = ProxyHealth.Unknown)

    private fun pool(vararg entries: ProxyUi) = ProxyPoolUiState(entries = persistentListOf(*entries), loaded = true)

    private fun routing(state: MutableStateFlow<ProxyPoolUiState>, timeoutMs: Long = 2_000) =
        WebProxyRouting(state, scope, loadTimeoutMs = timeoutMs)

    // ── Mapping ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `socks5 and http CONNECT carry web traffic, http-only and mtproto go direct`() {
        assertEquals(
            WebProxyRoute.Socks5("proxy.example", 1080, "u", "p"),
            entry(ProxyKind.Socks5("u", "p")).toWebRoute(),
        )
        assertEquals(
            WebProxyRoute.Http("proxy.example", 1080, "u", "p"),
            entry(ProxyKind.Http("u", "p", httpOnly = false)).toWebRoute(),
        )
        assertEquals(WebProxyRoute.Direct, entry(ProxyKind.Http("u", "p", httpOnly = true)).toWebRoute())
        assertEquals(WebProxyRoute.Direct, entry(ProxyKind.Mtproto("ee00")).toWebRoute())
        assertEquals(WebProxyRoute.Direct, (null as ProxyUi?).toWebRoute())

        assertTrue(ProxyKind.Socks5("", "").carriesWebTraffic())
        assertTrue(ProxyKind.Http("", "", httpOnly = false).carriesWebTraffic())
        assertFalse(ProxyKind.Http("", "", httpOnly = true).carriesWebTraffic())
        assertFalse(ProxyKind.Mtproto("ee00").carriesWebTraffic())
    }

    @Test
    fun `pool route follows the enabled entry only`() {
        val disabled = entry(ProxyKind.Socks5("", ""), enabled = false)
        assertEquals(WebProxyRoute.Direct, pool(disabled).webRoute())
        assertEquals(WebProxyRoute.Direct, ProxyPoolUiState().webRoute())
    }

    // ── Selector / DNS ──────────────────────────────────────────────────────────────────────

    @Test
    fun `selector returns only the http proxy, never an empty list`() {
        val state = MutableStateFlow(pool(entry(ProxyKind.Http("", "", httpOnly = false), port = 3128)))
        val r = routing(state)
        val uri = URI("https://t.me/s/durov")

        val proxies = r.selector.select(uri)
        assertEquals(1, proxies.size)
        assertEquals(Proxy.Type.HTTP, proxies[0].type())
        val address = proxies[0].address() as InetSocketAddress
        assertEquals("proxy.example", address.hostString)
        assertEquals(3128, address.port)
        assertTrue(address.isUnresolved, "the proxy host must not be resolved on the selector thread")

        state.value = pool(entry(ProxyKind.Socks5("", "")))
        assertEquals(listOf(Proxy.NO_PROXY), r.selector.select(uri))
        state.value = pool(entry(ProxyKind.Mtproto("ee00")))
        assertEquals(listOf(Proxy.NO_PROXY), r.selector.select(uri))
        state.value = pool()
        assertEquals(listOf(Proxy.NO_PROXY), r.selector.select(uri))
    }

    @Test
    fun `socks route resolves nothing locally - per-host placeholders carry the name`() {
        val state = MutableStateFlow(pool(entry(ProxyKind.Socks5("", ""))))
        val r = routing(state)

        val tme = r.dns.lookup("t.me").single()
        val cdn1 = r.dns.lookup("cdn1.telesco.pe").single()
        val cdn4 = r.dns.lookup("cdn4.telesco.pe").single()
        assertEquals("t.me", tme.hostName)
        assertTrue(WebProxyRouting.isPlaceholder(InetSocketAddress(tme, 443)))
        assertNotEquals(cdn1, cdn4, "distinct hosts must not share a placeholder (HTTP/2 coalescing)")

        state.value = pool()
        val direct = r.dns.lookup("localhost")
        assertTrue(direct.none { WebProxyRouting.isPlaceholder(InetSocketAddress(it, 443)) })
    }

    @Test
    fun `targetsProxy accepts the proxy by name or IP literal and nothing else`() {
        val byName = InetSocketAddress(InetAddress.getByAddress("proxy.example", byteArrayOf(10, 0, 0, 1)), 3128)
        assertTrue(WebProxyRouting.targetsProxy(byName, "proxy.example", 3128))
        assertFalse(WebProxyRouting.targetsProxy(byName, "proxy.example", 8080))
        val literal = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1)), 3128)
        assertTrue(WebProxyRouting.targetsProxy(literal, "10.0.0.1", 3128))
        val elsewhere = InetSocketAddress(InetAddress.getByAddress("t.me", byteArrayOf(1, 2, 3, 4)), 3128)
        assertFalse(WebProxyRouting.targetsProxy(elsewhere, "proxy.example", 3128))
    }

    // ── Authenticator ───────────────────────────────────────────────────────────────────────

    private fun challenge(request: Request) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(407)
        .message("Proxy Authentication Required")
        .build()

    @Test
    fun `http proxy auth sends basic credentials once, then gives up`() {
        val state = MutableStateFlow(pool(entry(ProxyKind.Http("alice", "s3cret", httpOnly = false))))
        val r = routing(state)
        val request = Request.Builder().url("https://t.me/").build()

        val authed = r.authenticator.authenticate(null, challenge(request))
        assertEquals(okhttp3.Credentials.basic("alice", "s3cret"), authed?.header("Proxy-Authorization"))

        assertNull(r.authenticator.authenticate(null, challenge(authed!!)), "wrong credentials must not loop")
    }

    @Test
    fun `no credentials or a non-http route never answers a 407`() {
        val state = MutableStateFlow(pool(entry(ProxyKind.Http("", "", httpOnly = false))))
        val r = routing(state)
        val request = Request.Builder().url("https://t.me/").build()
        assertNull(r.authenticator.authenticate(null, challenge(request)))

        state.value = pool(entry(ProxyKind.Socks5("alice", "s3cret")))
        assertNull(r.authenticator.authenticate(null, challenge(request)))
    }

    // ── SOCKS5 end to end ───────────────────────────────────────────────────────────────────

    /**
     * A one-connection fake SOCKS5 proxy that also plays the target: it records what the client
     * asked for, then answers one keep-alive HTTP/1.1 request itself.
     */
    private class FakeSocks5(private val requireAuth: Boolean) : AutoCloseable {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        @Volatile var requestedHost: String? = null
        @Volatile var requestedPort: Int = -1
        @Volatile var credentials: Pair<String, String>? = null
        @Volatile var failure: Throwable? = null

        private val worker = thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val inp = DataInputStream(s.getInputStream())
                    val out = s.getOutputStream()
                    check(inp.readUnsignedByte() == 5)
                    val methods = ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) }
                    if (requireAuth) {
                        check(2.toByte() in methods)
                        out.write(byteArrayOf(5, 2))
                        check(inp.readUnsignedByte() == 1)
                        val user = String(ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) })
                        val pass = String(ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) })
                        credentials = user to pass
                        out.write(byteArrayOf(1, 0))
                    } else {
                        out.write(byteArrayOf(5, 0))
                    }
                    check(inp.readUnsignedByte() == 5)
                    check(inp.readUnsignedByte() == 1)
                    inp.readUnsignedByte()
                    check(inp.readUnsignedByte() == 3) { "expected a host NAME (remote DNS)" }
                    requestedHost = String(ByteArray(inp.readUnsignedByte()).also { inp.readFully(it) })
                    requestedPort = inp.readUnsignedShort()
                    out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    out.flush()
                    // Read the HTTP request head, answer with a keep-alive response.
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(inp.readUnsignedByte().toChar())
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray())
                    out.flush()
                    // Hold the connection open until the client (or the eviction) closes it.
                    runCatching { while (inp.read() != -1) Unit }
                }
            }.onFailure { failure = it }
        }

        override fun close() {
            server.close()
            worker.join(2_000)
        }
    }

    @Test
    fun `socks5 tunnels with credentials and remote DNS, and a route change evicts the pool`() {
        FakeSocks5(requireAuth = true).use { fake ->
            val state = MutableStateFlow(
                pool(entry(ProxyKind.Socks5("alice", "s3cret"), server = "127.0.0.1", port = fake.server.localPort)),
            )
            val r = routing(state)
            val client = r.install(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS)).build()
            var evictions = 0
            r.onRouteChanged {
                evictions++
                client.connectionPool.evictAll()
            }

            val body = client.newCall(Request.Builder().url("http://blocked.example.test/s/x").build())
                .execute().use { it.body.string() }

            assertEquals("ok", body)
            assertNull(fake.failure)
            assertEquals("blocked.example.test", fake.requestedHost)
            assertEquals(80, fake.requestedPort)
            assertEquals("alice" to "s3cret", fake.credentials)
            assertEquals(1, client.connectionPool.connectionCount())

            // A health-only update (same route) is not a change.
            state.value = pool(
                entry(ProxyKind.Socks5("alice", "s3cret"), server = "127.0.0.1", port = fake.server.localPort)
                    .copy(health = ProxyHealth.Reachable(42)),
            )
            assertEquals(0, evictions)

            state.value = pool()
            assertEquals(1, evictions)
            assertEquals(0, client.connectionPool.connectionCount())
        }
    }

    @Test
    fun `a dead socks proxy fails the request instead of going direct`() {
        val deadPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val state = MutableStateFlow(pool(entry(ProxyKind.Socks5("", ""), server = "127.0.0.1", port = deadPort)))
        val r = routing(state)
        val client = r.install(OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS)).build()

        assertThrows<IOException> {
            client.newCall(Request.Builder().url("http://blocked.example.test/").build()).execute().close()
        }
    }

    @Test
    fun `a placeholder target is never dialled directly`() {
        val state = MutableStateFlow(pool())
        val r = routing(state)
        val socket = r.socketFactory.createSocket()
        val placeholder = InetSocketAddress(WebProxyRouting.placeholderFor("t.me"), 443)
        assertThrows<IOException> { socket.connect(placeholder, 1_000) }
    }

    // ── Cold start ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `before the pool loads the first lookup waits, then opens the gate once`() {
        val state = MutableStateFlow(ProxyPoolUiState(loaded = false))
        val r = routing(state, timeoutMs = 50)
        assertEquals(WebProxyRoute.Direct, r.current().route)

        state.value = pool(entry(ProxyKind.Socks5("", "")))
        assertTrue(r.current().route is WebProxyRoute.Socks5)
    }

    @Test
    fun `a pool loaded later is picked up by the waiting caller`() {
        val state = MutableStateFlow(ProxyPoolUiState(loaded = false))
        val r = routing(state, timeoutMs = 5_000)
        thread {
            Thread.sleep(50)
            state.value = pool(entry(ProxyKind.Http("", "", httpOnly = false)))
        }
        assertTrue(r.current().route is WebProxyRoute.Http)
    }
}
