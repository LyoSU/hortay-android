package dev.lyo.hortay.data.proxy

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Client side of a SOCKS5 CONNECT (RFC 1928) with optional username/password authentication
 * (RFC 1929), run over an already-connected socket to the proxy.
 *
 * Why our own instead of `java.net.Socket(Proxy(SOCKS, …))`: the JDK's `SocksSocketImpl` takes
 * SOCKS credentials only from the process-global `java.net.Authenticator`, which any library can
 * replace and which would hand the proxy password to every other caller asking for SOCKS auth.
 * Running the handshake ourselves keeps the credentials inside the one OkHttp client that needs
 * them, and lets us always send the target as a host NAME so the proxy resolves it — a local DNS
 * query for t.me is exactly what a censored network blocks or logs.
 *
 * Reads exactly the bytes of each reply — no buffering — so the stream is positioned at the
 * first tunnelled byte afterwards and TLS can start right on top of it.
 */
internal object Socks5Handshake {

    /**
     * Negotiate a tunnel to [host]:[port]. [address] is used instead of the name when the
     * caller already holds a real resolved address (an IP-literal URL); otherwise the name goes
     * to the proxy (ATYP DOMAINNAME). Throws [IOException] on any refusal.
     */
    fun connect(
        input: InputStream,
        output: OutputStream,
        host: String,
        address: InetAddress?,
        port: Int,
        username: String,
        password: String,
    ) {
        val inp = DataInputStream(input)
        val withAuth = username.isNotEmpty()

        // Greeting: offer "no auth" always, and username/password when we have credentials.
        output.write(
            if (withAuth) {
                byteArrayOf(VERSION, 2, METHOD_NONE, METHOD_USER_PASS)
            } else {
                byteArrayOf(VERSION, 1, METHOD_NONE)
            },
        )
        output.flush()
        ensure(inp.readUnsignedByte() == VERSION.toInt()) { "bad server version" }
        when (inp.readUnsignedByte()) {
            METHOD_NONE.toInt() -> Unit
            METHOD_USER_PASS.toInt() -> {
                ensure(withAuth) { "proxy demands credentials" }
                authenticate(inp, output, username, password)
            }
            else -> fail("no acceptable authentication method")
        }

        // CONNECT request.
        val request = java.io.ByteArrayOutputStream()
        request.write(byteArrayOf(VERSION, CMD_CONNECT, 0))
        when (address) {
            is Inet4Address -> { request.write(ATYP_IPV4.toInt()); request.write(address.address) }
            is Inet6Address -> { request.write(ATYP_IPV6.toInt()); request.write(address.address) }
            else -> {
                val name = host.toByteArray(Charsets.US_ASCII)
                ensure(name.isNotEmpty() && name.size <= MAX_FIELD) { "bad host name length" }
                request.write(ATYP_DOMAIN.toInt())
                request.write(name.size)
                request.write(name)
            }
        }
        request.write(port ushr 8 and 0xFF)
        request.write(port and 0xFF)
        output.write(request.toByteArray())
        output.flush()

        ensure(inp.readUnsignedByte() == VERSION.toInt()) { "bad reply version" }
        val reply = inp.readUnsignedByte()
        ensure(reply == 0) { "connect refused (code $reply)" }
        inp.readUnsignedByte() // RSV
        // Skip BND.ADDR + BND.PORT — we have no use for the proxy's bound address.
        val addrLen = when (inp.readUnsignedByte()) {
            ATYP_IPV4.toInt() -> 4
            ATYP_IPV6.toInt() -> 16
            ATYP_DOMAIN.toInt() -> inp.readUnsignedByte()
            else -> fail("bad reply address type")
        }
        inp.readFully(ByteArray(addrLen + 2))
    }

    private fun authenticate(inp: DataInputStream, output: OutputStream, username: String, password: String) {
        val user = username.toByteArray(Charsets.UTF_8)
        val pass = password.toByteArray(Charsets.UTF_8)
        ensure(user.size <= MAX_FIELD && pass.size <= MAX_FIELD) { "credentials too long" }
        val auth = java.io.ByteArrayOutputStream()
        auth.write(AUTH_VERSION.toInt())
        auth.write(user.size)
        auth.write(user)
        auth.write(pass.size)
        auth.write(pass)
        output.write(auth.toByteArray())
        output.flush()
        inp.readUnsignedByte() // sub-negotiation version
        ensure(inp.readUnsignedByte() == 0) { "authentication rejected" }
    }

    private inline fun ensure(condition: Boolean, message: () -> String) {
        if (!condition) fail(message())
    }

    private fun fail(message: String): Nothing = throw IOException("SOCKS5: $message")

    private const val VERSION: Byte = 5
    private const val AUTH_VERSION: Byte = 1
    private const val METHOD_NONE: Byte = 0
    private const val METHOD_USER_PASS: Byte = 2
    private const val CMD_CONNECT: Byte = 1
    private const val ATYP_IPV4: Byte = 1
    private const val ATYP_DOMAIN: Byte = 3
    private const val ATYP_IPV6: Byte = 4
    private const val MAX_FIELD = 255
}
