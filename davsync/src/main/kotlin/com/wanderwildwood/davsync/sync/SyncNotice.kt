package com.wanderwildwood.davsync.sync

import android.accounts.Account
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import com.wanderwildwood.davsync.R

/**
 * The one thing a background sync says out loud: the server refused the password, so nothing
 * will sync until the user signs in again. Everything else waits quietly for the next sync
 * and shows on the account's status line. Posted once when it starts, cleared once a sync
 * gets through again.
 */
object SyncNotice {

    private const val CHANNEL = "davsync_problems"

    private fun id(account: Account) = 0x64617600 + (account.name.hashCode() and 0xff)

    fun passwordRefused(context: Context, account: Account) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.davsync_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, id(account), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val text = context.getString(R.string.davsync_refused_notice, account.name)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
        val notice = builder
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(context.getString(R.string.davsync_refused_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(id(account), notice) }
    }

    fun clear(context: Context, account: Account) {
        context.getSystemService(NotificationManager::class.java)?.cancel(id(account))
    }
}
