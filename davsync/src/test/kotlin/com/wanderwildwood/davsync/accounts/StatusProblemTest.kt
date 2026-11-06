package com.wanderwildwood.davsync.accounts

import com.wanderwildwood.davsync.sync.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a finished sync is recorded as, for the status line and the refused-password notice. */
class StatusProblemTest {

    @Test
    fun aCleanSyncIsRecordedAsSynced() {
        assertNull(AccountStore.Status.problemOf(SyncResult()))
    }

    @Test
    fun aFailedSyncIsNeverRecordedAsSynced() {
        assertEquals(AccountStore.Status.Problem.ERROR, AccountStore.Status.problemOf(SyncResult(hardError = true)))
        assertEquals(AccountStore.Status.Problem.UNREACHABLE, AccountStore.Status.problemOf(SyncResult(softError = true)))
    }

    @Test
    fun aWrongClockIsNeverARefusedPassword() {
        val result = SyncResult(clockWrong = true, authenticationFailed = true, hardError = true)
        assertEquals(AccountStore.Status.Problem.CLOCK, AccountStore.Status.problemOf(result))
    }

    @Test
    fun aRefusedPasswordStaysOne() {
        val result = SyncResult(authenticationFailed = true, hardError = true)
        assertEquals(AccountStore.Status.Problem.PASSWORD, AccountStore.Status.problemOf(result))
    }
}
