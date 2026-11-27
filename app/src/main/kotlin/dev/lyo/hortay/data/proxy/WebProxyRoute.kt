package dev.lyo.hortay.data.proxy

/**
 * How the app's own HTTPS traffic (guest-mode `t.me/s/` pages, Telegram CDN images and video,
 * custom-emoji JSON, TGS, suggestions, the guest views ping) reaches the network — derived from
 * the proxy TDLib currently has enabled (see [WebProxyRouting]).
 *
 * Only two of TDLib's proxy flavours can carry an arbitrary TLS connection:
 *  - **SOCKS5** — a plain TCP tunnel; credentials go through the SOCKS5 username/password
 *    sub-negotiation (RFC 1929), the target host name is resolved by the proxy (RFC 1928
 *    DOMAINNAME), so no DNS query for t.me ever leaves the device.
 *  - **HTTP with CONNECT** (`ProxyTypeHttp.httpOnly == false`) — OkHttp opens a CONNECT tunnel;
 *    credentials go as `Proxy-Authorization: Basic`.
 *
 * An HTTP-only proxy (td_api.tl: "the proxy supports only HTTP requests and doesn't support
 * transparent TCP connections via HTTP CONNECT") and an MTProto proxy (it speaks Telegram's own
 * obfuscated transport to Telegram DCs only) cannot, so with one of those enabled the app's HTTPS
 * goes [Direct] while TDLib keeps using the proxy. The Proxy screen says so on those rows in
 * guest mode — it is never silently pretended.
 */
sealed interface WebProxyRoute {
    data object Direct : WebProxyRoute

    data class Socks5(val host: String, val port: Int, val username: String, val password: String) : WebProxyRoute {
        override fun toString(): String = "Socks5($host:$port, auth=${username.isNotEmpty()})"
    }

    data class Http(val host: String, val port: Int, val username: String, val password: String) : WebProxyRoute {
        override fun toString(): String = "Http($host:$port, auth=${username.isNotEmpty()})"
    }
}

/** True when this proxy flavour can carry the app's HTTPS traffic (see [WebProxyRoute]). */
fun ProxyKind.carriesWebTraffic(): Boolean = when (this) {
    is ProxyKind.Socks5 -> true
    is ProxyKind.Http -> !httpOnly
    is ProxyKind.Mtproto -> false
}

/** The route for the app's HTTPS traffic given TDLib's enabled proxy (null = none enabled). */
fun ProxyUi?.toWebRoute(): WebProxyRoute {
    val proxy = this ?: return WebProxyRoute.Direct
    return when (val kind = proxy.kind) {
        is ProxyKind.Socks5 -> WebProxyRoute.Socks5(proxy.server, proxy.port, kind.username, kind.password)
        is ProxyKind.Http ->
            if (kind.httpOnly) {
                WebProxyRoute.Direct
            } else {
                WebProxyRoute.Http(proxy.server, proxy.port, kind.username, kind.password)
            }
        is ProxyKind.Mtproto -> WebProxyRoute.Direct
    }
}

/** The route for the pool's currently enabled proxy. */
fun ProxyPoolUiState.webRoute(): WebProxyRoute = entries.firstOrNull { it.isEnabled }.toWebRoute()
