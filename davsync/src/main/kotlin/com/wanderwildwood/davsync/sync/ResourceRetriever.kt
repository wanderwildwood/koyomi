/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package com.wanderwildwood.davsync.sync

import com.wanderwildwood.davsync.util.DavUtils.toURIorNull
import ezvcard.util.DataUri
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Downloads a separate resource that is referenced during synchronization, for instance in
 * a vCard with `PHOTO:<external URL>`.
 *
 * The [ResourceRetriever] only sends authentication for URLs on the same domain as the
 * original URL. For instance, if the vCard that references a photo is taken from
 * `example.com` ([originalHost]), then [retrieve] will send authentication
 * when downloading `https://example.com/photo.jpg`, but not for `https://external-hoster.com/photo.jpg`.
 *
 * @param accountId     [AccountId] of the account to build authentication from
 * @param originalHost  client only authenticates for the domain of this host
 */
class ResourceRetriever(
    private val httpClient: HttpClient,
    private val logger: Logger
) {

    suspend fun retrieve(url: String): ByteArray? =
        try {
            when (url.toURIorNull()?.scheme?.lowercase()) {
                "data" ->
                    DataUri.parse(url).data

                "http", "https" -> {
                    val response = httpClient.get(url)
                    if (response.status.isSuccess())
                        response.bodyAsBytes()
                    else {
                        logger.warning("Couldn't download external resource (${response.status})")
                        null
                    }
                }

                else ->
                    null
            }
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Couldn't retrieve resource", e)
            null
        }

}