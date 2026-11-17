/*
 * Adapted from DAVx5 core/network/HttpClientBuilder.kt (bitfireAT, GPL-3.0-or-later).
 * Kept: OkHttp engine, preemptive Basic/Digest for the account's own domain only, cookies,
 * timeouts, gzip, no automatic redirects. Left out: OAuth, client certificates, proxy, custom
 * trust (the system's CAs only), traffic logging.
 * Added here: the login goes only over https, or over plain http to a home or VPN address
 * (SchemeGuard), and never to "any domain" when the account's host is not known. An account's
 * client sends nothing at all over plain http to a host away from home (PlainHttpGuard): the
 * request stops with [PlainHttpRefused], so the user is told the real reason.
 */
package com.wanderwildwood.davsync.network

import at.bitfire.dav4jvm.ktor.DomainAuthProvider
import at.bitfire.dav4jvm.ktor.PreemptiveBasicDigestAuthProvider
import at.bitfire.dav4jvm.ktor.UrlUtils
import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import com.wanderwildwood.davsync.signin.SignIn
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.AuthProvider
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.util.appendIfNameAbsent
import java.io.IOException
import java.util.Locale

/** User name and password for one server. The password is only ever held in memory. */
data class Credentials(val username: String, val password: String) {
    override fun toString() = "Credentials(username=$username, password=*****)"
}

/**
 * A request of an account's client that would have gone over plain http to a host that is not at
 * home or on a VPN. Nothing was sent. The server lists something at such an address; it needs https.
 */
class PlainHttpRefused(host: String) :
    IOException("Plain http to $host refused: the login goes only over https, or plain http at home or on a VPN")

/** Stops, before anything is sent, a request that [mayCarryLogin] would not let carry the login. */
private val PlainHttpGuard = createClientPlugin("PlainHttpGuard") {
    onRequest { request, _ ->
        if (!mayCarryLogin(request.url.buildString())) throw PlainHttpRefused(request.url.host)
    }
}

object HttpClientBuilder {

    /**
     * A client for one account. [host] is the server's: the password is sent to its domain alone,
     * never to another domain a response points at.
     */
    fun build(userAgent: String, credentials: Credentials?, host: String?): HttpClient =
        HttpClient(OkHttp) {
            // Redirects are not followed automatically: it would break PROPFIND handling (DAVx5).
            followRedirects = false
            // No host, no login: DomainAuthProvider reads a missing domain as "every domain".
            if (credentials != null && !host.isNullOrBlank()) {
                install(PlainHttpGuard)
                install(Auth) {
                    providers.add(
                        SchemeGuard(
                            DomainAuthProvider(UrlUtils.hostToDomain(host), PreemptiveBasicDigestAuthProvider(credentials.username, credentials.password))
                        )
                    )
                }
            }
            install(HttpCookies)
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 120_000
            }
            install(UserAgent) { agent = userAgent }
            install(DefaultRequest) {
                val locale = Locale.getDefault()
                headers.appendIfNameAbsent(
                    HttpHeaders.AcceptLanguage,
                    "${locale.language}-${locale.country}, ${locale.language};q=0.7, *;q=0.5"
                )
            }
            ContentEncoding()
        }
}

/**
 * Whether a request to [address] may carry the account's login: https always, plain http only to
 * a home or VPN address, by the same rule the sign-in screen applies to what is typed
 * ([SignIn.plainHttpAllowed]). The domain check alone is not enough: a server reached over https
 * can list a calendar or address book as http:// on its own domain, and the login would then go
 * out in the clear at every sync.
 */
internal fun mayCarryLogin(address: String): Boolean =
    address.startsWith("https://", ignoreCase = true) ||
        (address.startsWith("http://", ignoreCase = true) && SignIn.plainHttpAllowed(address))

/** Lets [inner] add the login only where [mayCarryLogin] allows it. */
private class SchemeGuard(private val inner: AuthProvider) : AuthProvider by inner {
    override fun sendWithoutRequest(request: HttpRequestBuilder): Boolean =
        mayCarryLogin(request.url.buildString()) && inner.sendWithoutRequest(request)

    override suspend fun addRequestHeaders(request: HttpRequestBuilder, authHeader: HttpAuthHeader?) {
        if (mayCarryLogin(request.url.buildString())) inner.addRequestHeaders(request, authHeader)
    }
}
