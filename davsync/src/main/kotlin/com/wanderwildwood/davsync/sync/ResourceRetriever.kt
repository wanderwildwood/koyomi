/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package com.wanderwildwood.davsync.sync

import com.wanderwildwood.davsync.util.DavUtils.toURIorNull
import ezvcard.util.DataUri
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Downloads a separate resource that is referenced during synchronization, for instance in
 * a vCard with `PHOTO:<external URL>`.
 *
 * Only the account's own server is asked: an http or https URL is fetched only when its host is
 * [ownHost], the host the card itself came from, and anything elsewhere is left out (the card
 * keeps no photo), so a card naming some other site never makes the phone contact it. `data:`
 * URIs are read in place. A body larger than [MAX_BYTES] is dropped rather than read into memory.
 *
 * @param ownHost  the host of the card being read; the only host a resource is fetched from
 */
class ResourceRetriever(
    private val httpClient: HttpClient,
    private val logger: Logger,
    private val ownHost: String
) {

    suspend fun retrieve(url: String): ByteArray? =
        try {
            val uri = url.toURIorNull()
            when (uri?.scheme?.lowercase()) {
                "data" ->
                    DataUri.parse(url).data

                "http", "https" ->
                    if (!uri.host.equals(ownHost, ignoreCase = true)) {
                        logger.info("External resource not on the account's server, not fetched")
                        null
                    } else httpClient.prepareGet(url).execute { response ->
                        if (!response.status.isSuccess()) {
                            logger.warning("Couldn't download external resource (${response.status})")
                            null
                        } else {
                            val bytes = response.bodyAsChannel().readBuffer(MAX_BYTES + 1).readByteArray()
                            if (bytes.size > MAX_BYTES) {
                                logger.warning("External resource larger than $MAX_BYTES bytes, dropped")
                                null
                            } else bytes
                        }
                    }

                else ->
                    null
            }
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Couldn't retrieve resource", e)
            null
        }

    companion object {
        /** A contact photo is a few hundred KiB at most; 4 MiB leaves room and keeps memory safe. */
        const val MAX_BYTES = 4L * 1024 * 1024
    }

}
