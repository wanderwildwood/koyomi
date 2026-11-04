/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package com.wanderwildwood.davsync.sync

import android.os.DeadObjectException
import androidx.annotation.VisibleForTesting
import at.bitfire.dav4jvm.ktor.exception.BadGatewayException
import at.bitfire.dav4jvm.ktor.exception.DavException
import at.bitfire.dav4jvm.ktor.exception.HttpException
import at.bitfire.dav4jvm.ktor.exception.ServiceUnavailableException
import at.bitfire.dav4jvm.ktor.exception.UnauthorizedException
import com.wanderwildwood.davsync.model.Collection
import com.wanderwildwood.davsync.resource.local.LocalResource
import at.bitfire.synctools.storage.LocalStorageException
import io.ktor.http.Url
import java.io.IOException
import java.security.cert.CertificateException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CancellationException
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ssl.SSLHandshakeException

/**
 * Decides what to do about an exception that occurred during synchronization of one collection
 * (update [SyncResult], log, notify the user), so that [SyncManager] only has to orchestrate the
 * sync and doesn't need to know about error classification/presentation itself.
 */
class SyncExceptionHandler(
    private val logger: Logger
) {

    // Notifications are left out for now (phase 3, "sync status lines"): errors are logged and
    // reach the sync framework through the SyncResult.

    sealed interface SyncErrorResult {
        /** Credentials were rejected; syncing other collections of the account is pointless. */
        data object AuthenticationError : SyncErrorResult
        data class HardError(val message: String? = null) : SyncErrorResult
        data class SoftError(val delayUntil: Instant? = null, val message: String? = null) : SyncErrorResult
        data object NoError : SyncErrorResult
    }

    /**
     * Handles an exception that terminated a sync run by doing an appropriate action.
     *
     * @param exception             exception thrown during sync
     * @param collectionInfo        affected collection, used to derive the notification tag and title
     * @param local                 local resource that was being processed when the exception occurred, if any
     * @param remote                remote URL that was being processed when the exception occurred, if any
     *
     * @return whether [exception] represents an authentication error, hard error, soft error or no error
     *
     * @throws CancellationException if the sync was canceled
     * @throws DeadObjectException if the content provider process died (found anywhere in [exception]'s cause chain)
     */
    suspend fun handleException(
        exception: Throwable,
        collectionInfo: Collection,
        local: LocalResource?,
        remote: Url?
    ): SyncErrorResult =
        when (val action = classifySyncException(exception)) {
            is SyncErrorAction.LogWarning -> {
                logger.log(Level.WARNING, action.logMessage, exception)
                SyncErrorResult.NoError
            }

            is SyncErrorAction.SoftError -> {
                logger.log(Level.WARNING, action.logMessage, exception)
                SyncErrorResult.SoftError(action.delayUntil, action.notifyMessage)
            }

            is SyncErrorAction.AuthenticationError -> {
                logger.log(Level.SEVERE, action.logMessage, exception)
                SyncErrorResult.AuthenticationError
            }

            is SyncErrorAction.HardError -> {
                logger.log(Level.SEVERE, action.logMessage, exception)
                SyncErrorResult.HardError(action.notifyMessage)
            }

            is SyncErrorAction.Rethrow ->
                throw action.throwable
        }


    /**
     * What [SyncExceptionHandler] internally decides to do about an exception.
     */
    @VisibleForTesting
    internal sealed interface SyncErrorAction {
        /** Just log a warning – no error that must be reported. */
        data class LogWarning(val logMessage: String) : SyncErrorAction

        /** Temporary/recoverable error. */
        data class SoftError(
            val logMessage: String,
            val delayUntil: Instant? = null,
            val notifyMessage: String? = null
        ) : SyncErrorAction

        /** Credentials were rejected: notify once per account, not per collection. */
        data class AuthenticationError(val logMessage: String) : SyncErrorAction

        /** Non-recoverable error requiring the user's attention. */
        data class HardError(val logMessage: String, val notifyMessage: String) : SyncErrorAction

        /** Sync must be aborted/rescheduled: re-throw to [Syncer]. */
        data class Rethrow(val throwable: Throwable) : SyncErrorAction
    }

    /**
     * Classifies a sync exception into a [SyncErrorAction].
     *
     * @param exception     exception thrown during sync
     */
    @VisibleForTesting
    internal fun classifySyncException(exception: Throwable): SyncErrorAction {
        return when (exception) {
            // Sync was canceled: re-throw to Syncer
            is CancellationException ->
                SyncErrorAction.Rethrow(exception)

            // Special IOException (check before generic IOException)
            is SSLHandshakeException ->
                // when a certificate is rejected by cert4android, the cause will be a CertificateException
                if (exception.cause is CertificateException)
                    SyncErrorAction.LogWarning("SSL handshake failed (certificate rejected)")
                else
                    SyncErrorAction.SoftError(
                        logMessage = "SSL handshake failed",
                        notifyMessage = exception.localizedMessage ?: exception::class.java.simpleName
                    )

            is IOException -> SyncErrorAction.SoftError(
                logMessage = "I/O error",
                notifyMessage = exception.localizedMessage ?: exception::class.java.simpleName
            )

            // HTTP/DAV exceptions (again, check specialized classes before generic)
            is UnauthorizedException -> SyncErrorAction.AuthenticationError(
                logMessage = "Not authorized anymore"
            )

            is ServiceUnavailableException -> SyncErrorAction.SoftError(
                logMessage = "Got 503 Service unavailable, trying again later",
                delayUntil = exception.getDelayUntil()
            )

            is BadGatewayException -> SyncErrorAction.SoftError(
                logMessage = "Got 502 Bad Gateway, trying again later",
                // Service may be down temporarily, so retry after 15 minutes (arbitrary)
                delayUntil = Instant.now().plus(15, ChronoUnit.MINUTES)
            )

            is HttpException, is DavException -> SyncErrorAction.HardError(
                logMessage = "HTTP/DAV exception",
                notifyMessage = exception.localizedMessage ?: exception::class.java.simpleName
            )

            // Local storage exception
            is LocalStorageException if (exception.softError) -> SyncErrorAction.Rethrow(exception)

            is LocalStorageException -> SyncErrorAction.HardError(
                logMessage = "Couldn't access local storage",
                notifyMessage = exception.localizedMessage ?: exception::class.java.simpleName
            )

            // Unknown/unclassified exception
            else -> SyncErrorAction.HardError(
                logMessage = "Unclassified sync error",
                notifyMessage = exception.localizedMessage ?: exception::class.java.simpleName
            )
        }
    }


    /**
     * Logs the exception and notifies the user that a resource couldn't be processed and was ignored.
     *
     * @param exception             exception thrown while processing the resource
     * @param collectionId          ID of the affected collection, used to derive the notification tag
     * @param remote                URL of the resource that couldn't be processed, if it could be resolved
     */
    fun handleInvalidResourceException(
        exception: Throwable,
        collectionId: Long,
        remote: Url?
    ) {
        logger.log(Level.WARNING, "Ignoring invalid resource $remote", exception)
    }
}
