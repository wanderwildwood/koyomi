package com.wanderwildwood.koyomi.alerts

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract.CalendarAlerts
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wanderwildwood.koyomi.MainActivity
import com.wanderwildwood.koyomi.R
import com.wanderwildwood.koyomi.data.CalendarStore
import com.wanderwildwood.koyomi.data.Settings
import com.wanderwildwood.koyomi.ui.Dates
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Reminders, the way Etar does them: the phone's calendar store keeps a table of alerts
 * (`CalendarAlerts`), fills it from every event's reminders — the ones Nextcloud sent as much
 * as the ones set here — and broadcasts `EVENT_REMINDER` when one falls due. This app shows
 * the due ones and marks each row fired, so a row is shown once however many times the
 * broadcast arrives.
 *
 * There is also an alarm of this app's own for the next due row, which is Etar's belt to the
 * provider's braces. Both paths run the same [check], and the row's state makes the second
 * one a no-op, so neither can double an alert.
 *
 * An event with no reminders makes no alert at all. Nothing extra fires at the start time.
 */
object Alerts {
    const val CHANNEL = "reminders"
    private const val TAG = "Alerts"

    const val ACTION_CHECK = "com.wanderwildwood.koyomi.CHECK"
    const val ACTION_SNOOZE = "com.wanderwildwood.koyomi.SNOOZE"
    const val ACTION_DISMISS = "com.wanderwildwood.koyomi.DISMISS"
    const val EXTRA_EVENT = "event_id"
    const val EXTRA_BEGIN = "begin"
    const val EXTRA_END = "end"

    const val SNOOZE_MINUTES = 10

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL,
            context.getString(R.string.alert_channel),
            NotificationManager.IMPORTANCE_HIGH,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Shows every due alert and sets this app's own alarm for the next one. */
    fun check(context: Context) {
        val store = CalendarStore(context)
        if (!store.canRead()) return
        val now = System.currentTimeMillis()
        val resolver = context.contentResolver
        val due = mutableListOf<Due>()
        runCatching {
            resolver.query(
                CalendarAlerts.CONTENT_URI,
                arrayOf(
                    CalendarAlerts._ID,
                    CalendarAlerts.EVENT_ID,
                    CalendarAlerts.BEGIN,
                    CalendarAlerts.END,
                    CalendarAlerts.TITLE,
                    CalendarAlerts.EVENT_LOCATION,
                    CalendarAlerts.ALL_DAY,
                ),
                "${CalendarAlerts.STATE} = ? AND ${CalendarAlerts.ALARM_TIME} <= ?",
                arrayOf(CalendarAlerts.STATE_SCHEDULED.toString(), now.toString()),
                "${CalendarAlerts.ALARM_TIME} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    due += Due(
                        id = c.getLong(0),
                        eventId = c.getLong(1),
                        begin = c.getLong(2),
                        end = c.getLong(3),
                        title = c.getString(4).orEmpty(),
                        location = c.getString(5)?.takeIf { it.isNotBlank() },
                        allDay = c.getInt(6) == 1,
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Could not read the alert table", it) }

        for (alert in due) {
            // An occurrence deleted or moved since the alert was made, or one already over,
            // has nothing left to remind anyone of.
            val stale = alert.end < now || !store.occurrenceExists(alert.eventId, alert.begin)
            setState(context, alert.id, if (stale) CalendarAlerts.STATE_DISMISSED else CalendarAlerts.STATE_FIRED)
            if (!stale) notify(context, alert)
        }
        scheduleNext(context, now)
    }

    private fun setState(context: Context, rowId: Long, state: Int) {
        runCatching {
            context.contentResolver.update(
                CalendarAlerts.CONTENT_URI,
                ContentValues().apply { put(CalendarAlerts.STATE, state) },
                "${CalendarAlerts._ID} = ?",
                arrayOf(rowId.toString()),
            )
        }.onFailure { Log.w(TAG, "Could not mark alert $rowId", it) }
    }

    /** Marks every fired alert for one occurrence dismissed, and takes its notification away. */
    fun dismiss(context: Context, eventId: Long, begin: Long) {
        runCatching {
            context.contentResolver.update(
                CalendarAlerts.CONTENT_URI,
                ContentValues().apply { put(CalendarAlerts.STATE, CalendarAlerts.STATE_DISMISSED) },
                "${CalendarAlerts.STATE} = ? AND ${CalendarAlerts.EVENT_ID} = ? AND ${CalendarAlerts.BEGIN} = ?",
                arrayOf(CalendarAlerts.STATE_FIRED.toString(), eventId.toString(), begin.toString()),
            )
        }.onFailure { Log.w(TAG, "Could not dismiss $eventId", it) }
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(eventId, begin))
    }

    /** A new alert row, [SNOOZE_MINUTES] from now, which [check] will show like any other. */
    fun snooze(context: Context, eventId: Long, begin: Long, end: Long) {
        dismiss(context, eventId, begin)
        val now = System.currentTimeMillis()
        val at = now + SNOOZE_MINUTES * 60_000L
        runCatching {
            context.contentResolver.insert(
                CalendarAlerts.CONTENT_URI,
                ContentValues().apply {
                    put(CalendarAlerts.EVENT_ID, eventId)
                    put(CalendarAlerts.BEGIN, begin)
                    put(CalendarAlerts.END, end)
                    put(CalendarAlerts.ALARM_TIME, at)
                    put(CalendarAlerts.CREATION_TIME, now)
                    put(CalendarAlerts.RECEIVED_TIME, 0)
                    put(CalendarAlerts.NOTIFY_TIME, 0)
                    put(CalendarAlerts.STATE, CalendarAlerts.STATE_SCHEDULED)
                    put(CalendarAlerts.MINUTES, 0)
                },
            )
        }.onFailure { Log.w(TAG, "Could not snooze $eventId", it) }
        setAlarm(context, at)
    }

    private fun scheduleNext(context: Context, now: Long) {
        var next: Long? = null
        runCatching {
            context.contentResolver.query(
                CalendarAlerts.CONTENT_URI,
                arrayOf(CalendarAlerts.ALARM_TIME),
                "${CalendarAlerts.STATE} = ? AND ${CalendarAlerts.ALARM_TIME} > ?",
                arrayOf(CalendarAlerts.STATE_SCHEDULED.toString(), now.toString()),
                "${CalendarAlerts.ALARM_TIME} ASC LIMIT 1",
            )?.use { c -> if (c.moveToFirst()) next = c.getLong(0) }
        }
        next?.let { setAlarm(context, it) }
    }

    private fun setAlarm(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, AlertReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun notificationId(eventId: Long, begin: Long): Int = (eventId * 31 + begin / 60_000).hashCode()

    private fun notify(context: Context, alert: Due) {
        val id = notificationId(alert.eventId, alert.begin)
        val zone = ZoneId.systemDefault()
        val start = CalendarStore.toLocal(alert.begin, alert.allDay, zone)
        val finish = CalendarStore.toLocal(alert.end, alert.allDay, zone)
        val `when` = Dates.span(context, start, finish, alert.allDay)
        val text = listOfNotNull(`when`, alert.location).joinToString("  ·  ")
        val title = alert.title.ifBlank { context.getString(R.string.untitled) }

        fun extras(i: Intent) = i.putExtra(EXTRA_EVENT, alert.eventId)
            .putExtra(EXTRA_BEGIN, alert.begin)
            .putExtra(EXTRA_END, alert.end)

        val open = PendingIntent.getActivity(
            context, id,
            extras(Intent(context, MainActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snooze = PendingIntent.getBroadcast(
            context, id,
            extras(Intent(context, AlertReceiver::class.java).setAction(ACTION_SNOOZE)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = PendingIntent.getBroadcast(
            context, id,
            extras(Intent(context, AlertReceiver::class.java).setAction(ACTION_DISMISS)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setDeleteIntent(dismiss)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.alert_snooze, SNOOZE_MINUTES), snooze)
            .addAction(0, context.getString(R.string.alert_dismiss), dismiss)

        // On a phone with no light and no colour, a notification alone is easy to miss, so a
        // reminder can bring the event up on the screen itself.
        if (Settings(context).wakeScreen) {
            val full = PendingIntent.getActivity(
                context, id,
                extras(Intent(context, AlertActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setFullScreenIntent(full, true)
        }

        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(id, builder.build())
        }.onFailure { Log.w(TAG, "Could not post the reminder for ${alert.eventId}", it) }
    }

    private data class Due(
        val id: Long,
        val eventId: Long,
        val begin: Long,
        val end: Long,
        val title: String,
        val location: String?,
        val allDay: Boolean,
    )
}

/** The provider's reminder broadcast, this app's own alarm, a boot, and the two actions. */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "alerts") {
            try {
                val eventId = intent.getLongExtra(Alerts.EXTRA_EVENT, -1)
                val begin = intent.getLongExtra(Alerts.EXTRA_BEGIN, -1)
                val end = intent.getLongExtra(Alerts.EXTRA_END, -1)
                when (intent.action) {
                    Alerts.ACTION_SNOOZE -> Alerts.snooze(app, eventId, begin, end)
                    Alerts.ACTION_DISMISS -> Alerts.dismiss(app, eventId, begin)
                    else -> Alerts.check(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
