/*
 * Adapted from DAVx5 core/network/HttpClientBuilder.kt (bitfireAT, GPL-3.0-or-later).
 * Kept: OkHttp engine, preemptive Basic/Digest for the account's own domain only, cookies,
 * timeouts, gzip, no automatic redirects. Left out: OAuth, client certificates, proxy, custom
 * trust (the system's CAs only), traffic logging.
 */
package com.wanderwildwood.davsync.network

import at.bitfire.dav4jvm.ktor.DomainAuthProvider
import at.bitfire.dav4jvm.ktor.PreemptiveBasicDigestAuthProvider
import at.bitfire.dav4jvm.ktor.UrlUtils
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.HttpHeaders
import io.ktor.util.appendIfNameAbsent
import java.util.Locale

/** User name and password for one server. The password is only ever held in memory. */
data class Credentials(val username: String, val password: String) {
    override fun toString() = "Credentials(username=$username, password=*****)"
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
            if (credentials != null)
                install(Auth) {
                    providers.add(
                        DomainAuthProvider(UrlUtils.hostToDomain(host), PreemptiveBasicDigestAuthProvider(credentials.username, credentials.password))
                    )
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
