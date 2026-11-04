package com.wanderwildwood.davsync.accounts

import android.content.Context
import android.text.format.DateUtils
import com.wanderwildwood.davsync.R

/** The quiet line under an account: when it last synced, and what went wrong if anything did. */
object StatusText {
    fun of(context: Context, status: AccountStore.Status, syncing: Boolean): String {
        if (syncing) return context.getString(R.string.davsync_syncing)
        val line = plain(context, status)
        val said = status.detail?.takeIf { status.problem != null && status.problem != AccountStore.Status.Problem.PASSWORD && it.isNotBlank() }
        return if (said == null) line else line + "\n" + context.getString(R.string.davsync_said, said)
    }

    private fun plain(context: Context, status: AccountStore.Status): String {
        val last = if (status.lastGood > 0) `when`(context, status.lastGood) else null
        return when (status.problem) {
            null -> if (last == null) context.getString(R.string.davsync_never_synced) else context.getString(R.string.davsync_synced, last)
            AccountStore.Status.Problem.UNREACHABLE ->
                if (last == null) context.getString(R.string.davsync_unreachable_never) else context.getString(R.string.davsync_unreachable, last)
            AccountStore.Status.Problem.PASSWORD -> context.getString(R.string.davsync_refused)
            AccountStore.Status.Problem.ERROR ->
                if (last == null) context.getString(R.string.davsync_failed_never) else context.getString(R.string.davsync_failed, last)
        }
    }

    private fun `when`(context: Context, at: Long): String {
        val flags = if (DateUtils.isToday(at)) DateUtils.FORMAT_SHOW_TIME
            else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME
        return DateUtils.formatDateTime(context, at, flags)
    }
}
