/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package com.wanderwildwood.davsync.resource.remote

import at.bitfire.dav4jvm.ktor.MultiStatusItem
import at.bitfire.dav4jvm.ktor.Response
import at.bitfire.dav4jvm.ktor.omitTrailingSlash
import at.bitfire.dav4jvm.ktor.responsesWithRelation
import at.bitfire.dav4jvm.property.webdav.GetETag
import at.bitfire.dav4jvm.property.webdav.ResourceType
import at.bitfire.dav4jvm.property.webdav.WebDAV
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import java.util.logging.Logger

private val logger = Logger.getLogger("com.wanderwildwood.davsync.resource.remote.MultiStatusItemExt")

/**
 * Filters this flow down to responses that are members of the collection (not the collection's
 * own response or an unrelated resource).
 *
 * Logs a warning for every response that gets filtered out.
 */
fun Flow<MultiStatusItem.Response>.filterMembers(): Flow<MultiStatusItem.Response> =
    filter { item ->
        val isMember = item.relation == Response.HrefRelation.MEMBER
        if (!isMember)
            logger.warning("Ignoring non-member response: ${item.response.href}")
        isMember
    }

/**
 * Filters this flow down to responses that are not collections.
 *
 * Only filters anything if [WebDAV.ResourceType] is present in the response.
 * **Make sure to request [WebDAV.ResourceType] if you want this filter to actually filter anything.**
 */
fun Flow<MultiStatusItem.Response>.filterNotCollections(): Flow<MultiStatusItem.Response> =
    filterNot { item -> item.response[ResourceType::class.java]?.types?.contains(WebDAV.Collection) == true }

/**
 * Filters this flow down to successful responses.
 *
 * Logs a warning for every response that gets filtered out.
 */
fun Flow<MultiStatusItem.Response>.filterSuccessful(): Flow<MultiStatusItem.Response> =
    filter { item ->
        val isSuccess = item.response.isSuccess()
        if (!isSuccess)
            logger.warning("Ignoring non-successful (${item.response.status}) response: ${item.response.href}")
        isSuccess
    }

/**
 * Whether this response says the resource is gone for good (404 Not Found or 410 Gone).
 */
fun Response.isGone(): Boolean =
    status == HttpStatusCode.NotFound || status == HttpStatusCode.Gone

/**
 * Filters this multi-get flow down to successful responses, as [filterSuccessful], and hands the
 * href of every 404/410 response to [onGone]: the server listed the member, but it can't be
 * fetched any more (Google keeps listing a deleted "this and following" split series with a new
 * ETag, then answers the multi-get with 404). Adapted from mudita/davx5-ose-kompakt (SHP-586).
 */
fun Flow<MultiStatusItem.Response>.filterSuccessful(onGone: suspend (Url) -> Unit): Flow<MultiStatusItem.Response> =
    filter { item ->
        val response = item.response
        when {
            response.isSuccess() -> true
            response.isGone() -> {
                logger.info("Listed but no longer retrievable (${response.status}): ${response.href}")
                onGone(response.href.omitTrailingSlash())
                false
            }
            else -> {
                logger.warning("Ignoring non-successful (${response.status}) response: ${response.href}")
                false
            }
        }
    }

/**
 * Maps this flow to [InternalMemberState]s, dropping everything that isn't an actual member:
 * the collection's own response, sub-collections, and unsuccessful responses.
 *
 * Members without ETag are kept (with `null` ETag), see [InternalMemberState.eTag].
 */
fun Flow<MultiStatusItem>.toInternalMemberStates(): Flow<InternalMemberState> =
    responsesWithRelation()
        .filterMembers()
        .filterNotCollections()
        .filterSuccessful()
        .map { item ->
            InternalMemberState(
                href = item.response.href.omitTrailingSlash(),
                eTag = item.response[GetETag::class.java]?.eTag
            )
        }
