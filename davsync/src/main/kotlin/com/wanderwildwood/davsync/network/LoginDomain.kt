package com.wanderwildwood.davsync.network

import com.google.common.net.InetAddresses
import io.ktor.client.plugins.auth.AuthProvider
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.auth.HttpAuthHeader

/**
 * The domain an account's login may go to: the server's host name less its subdomains. Most
 * names end in one label anyone can register under (`example.org`, so `p12-caldav.icloud.com`
 * and `caldav.icloud.com` share `icloud.com`). Under the suffixes in [SHARED_SUFFIXES] strangers
 * hold the names next to each other (`me.duckdns.org`, `box.tail1234.ts.net`, `example.co.uk`),
 * so there the domain keeps one label more and a neighbor's host never counts as the server's.
 * An IP address is its own domain.
 */
internal object LoginDomain {

    /** Suffixes under which unrelated people hold names; each is matched as whole labels. */
    val SHARED_SUFFIXES: Set<String> = setOf(
        // Second levels of country domains
        "co.uk", "org.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk", "ac.uk", "gov.uk", "sch.uk",
        "com.au", "net.au", "org.au", "id.au", "edu.au",
        "co.nz", "net.nz", "org.nz",
        "co.jp", "ne.jp", "or.jp", "ac.jp",
        "co.kr", "or.kr", "com.cn", "net.cn", "org.cn", "com.tw", "com.hk", "com.sg",
        "co.in", "net.in", "org.in", "co.za", "org.za",
        "com.br", "net.br", "com.mx", "com.ar", "com.tr", "co.il", "com.pl",
        // Tailscale, dynamic DNS and shared hosting
        "ts.net", "duckdns.org", "dedyn.io", "dynv6.net", "ddns.net", "no-ip.org", "no-ip.biz",
        "hopto.org", "zapto.org", "sytes.net", "dyndns.org", "freedns.org", "mooo.com",
        "myfritz.net", "synology.me", "myds.me", "myqnapcloud.com", "tplinkdns.com",
        "nsupdate.info", "spdns.de", "goip.de",
        "github.io", "gitlab.io", "netlify.app", "pages.dev", "workers.dev", "herokuapp.com",
        "ngrok.io", "ngrok-free.app", "trycloudflare.com", "onrender.com", "fly.dev",
    )

    /** The login domain of [host], lower case without a trailing dot; null for no host. */
    fun of(host: String?): String? {
        if (host.isNullOrBlank()) return null
        val name = host.trim().removeSuffix(".").removePrefix("[").removeSuffix("]").lowercase()
        if (InetAddresses.isInetAddress(name)) return name
        val labels = name.split('.')
        if (labels.size <= 2) return name
        // The longest shared suffix the name ends in, counted in labels; none means one label.
        val suffixLabels = (labels.size - 1 downTo 2).firstOrNull { n ->
            labels.takeLast(n).joinToString(".") in SHARED_SUFFIXES
        } ?: 1
        return labels.takeLast(minOf(labels.size, suffixLabels + 1)).joinToString(".")
    }

    /** Whether [requestHost] lies in the same login domain as the account's [serverHost]. */
    fun matches(serverHost: String?, requestHost: String?): Boolean {
        val server = of(serverHost) ?: return false
        return server == of(requestHost)
    }
}

/**
 * Lets [inner] add the login only to requests whose host is in the account's login domain
 * ([LoginDomain]). Takes the place of dav4jvm's DomainAuthProvider, whose last-two-labels rule
 * counts every host under `ts.net` or `duckdns.org` as the server's.
 */
internal class LoginDomainGuard(
    private val serverHost: String,
    private val inner: AuthProvider,
) : AuthProvider by inner {

    override fun sendWithoutRequest(request: HttpRequestBuilder): Boolean =
        LoginDomain.matches(serverHost, request.url.host) && inner.sendWithoutRequest(request)

    override suspend fun addRequestHeaders(request: HttpRequestBuilder, authHeader: HttpAuthHeader?) {
        if (LoginDomain.matches(serverHost, request.url.host)) inner.addRequestHeaders(request, authHeader)
    }
}
