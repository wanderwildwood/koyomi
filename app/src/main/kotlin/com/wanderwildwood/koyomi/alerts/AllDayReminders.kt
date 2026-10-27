package com.wanderwildwood.koyomi.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract.Reminders
import android.util.Log
import com.wanderwildwood.koyomi.data.AllDayKind
import com.wanderwildwood.koyomi.data.AllDayPlan
import com.wanderwildwood.koyomi.data.CalendarStore
import com.wanderwildwood.koyomi.data.Settings
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reminders for all-day events that this app keeps itself, for the calendars it is asked to.
 *
 * The phone's calendar store only alerts for an event's own reminders, and a subscribed
 * calendar (a town's bin days, a school's holidays) is read-only: no reminder can be added to
 * its events, and the feed seldom carries any. So nothing is written to the store here. The
 * next fortnight of all-day events in the chosen calendars is read, each one's "evening before"
 * and "morning of" is worked out ([AllDayPlan]), and one alarm is set for the soonest. It rings
 * through the same notification and screen as any reminder ([Alerts.notify]).
 *
 * What was shown, and what was snoozed, is kept in a small file of its own, keyed by event,
 * occurrence and kind, so a reminder rings once however many times this is run: on a boot, a
 * change of clock or zone, a sync, the app opening, or the alarm itself.
 */
object AllDayReminders {
    private const val TAG = "AllDayReminders"
    const val ACTION = "com.wanderwildwood.koyomi.ALL_DAY"
    private const val REQUEST = 1
    private const val HORIZON_DAYS = 14L

    /** A look-again alarm when nothing rings sooner, so a sync with no broadcast is still caught. */
    private const val REFRESH_MS = 6 * 60 * 60_000L

    private const val FILE = "all_day_state"
    private const val FIRED = "fired"
    private const val SNOOZED = "snoozed"
    private val lock = Any()

    private class Planned(
        val key: String,
        val eventId: Long,
        val begin: Long,
        val end: Long,
        val title: String,
        val location: String?,
        val kind: AllDayKind,
        val atMs: Long,
        val validUntilMs: Long,
    )

    /** Shows what is due and sets the alarm for what is next. Safe to run as often as you like. */
    fun check(context: Context) {
        synchronized(lock) {
            val app = context.applicationContext
            val now = System.currentTimeMillis()
            val state = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val fired = state.getStringSet(FIRED, emptySet()).orEmpty().toMutableSet()
            val snoozed = readSnoozes(state)
            val plan = plan(app, now, fired, snoozed)

            var changed = false
            for (p in plan) {
                if (p.atMs > now || now >= p.validUntilMs) continue
                // Marked before it is shown: if this dies halfway, a reminder is missed rather than doubled.
                fired += "${p.key}@${p.validUntilMs}"
                snoozed.remove(p.key)
                changed = true
                Alerts.notify(
                    app,
                    Alerts.Due(0, p.eventId, p.begin, p.end, p.title, p.location, true),
                    p.kind,
                )
            }
            // What can no longer be true is forgotten.
            changed = fired.removeAll { (it.substringAfterLast('@').toLongOrNull() ?: 0L) <= now } || changed
            if (changed) {
                state.edit().putStringSet(FIRED, fired).putString(SNOOZED, writeSnoozes(snoozed)).apply()
            }

            val next = plan.filter { it.atMs > now && now < it.validUntilMs }.minOfOrNull { it.atMs }
            setAlarm(app, next, now)
        }
    }

    /** Ten minutes on for one reminder that was shown, without touching the calendar store. */
    fun snooze(context: Context, eventId: Long, begin: Long, kind: AllDayKind) {
        synchronized(lock) {
            val app = context.applicationContext
            val state = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val snoozed = readSnoozes(state)
            snoozed[AllDayPlan.key(eventId, begin, kind)] = System.currentTimeMillis() + Alerts.SNOOZE_MINUTES * 60_000L
            state.edit().putString(SNOOZED, writeSnoozes(snoozed)).apply()
        }
        check(context)
    }

    private fun plan(
        context: Context,
        now: Long,
        fired: Set<String>,
        snoozed: Map<String, Long>,
    ): List<Planned> {
        val settings = Settings(context)
        val on = settings.allDayOn()
        val store = CalendarStore(context)
        if (on.isEmpty() || !store.canRead()) return emptyList()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val firedKeys = fired.map { it.substringBeforeLast('@') }.toSet()

        val out = linkedMapOf<String, Planned>()
        for (o in store.occurrences(today.minusDays(1), today.plusDays(HORIZON_DAYS))) {
            val setting = on[o.calendarId] ?: continue
            if (!o.allDay) continue
            val own by lazy {
                store.event(o.eventId)?.reminders
                    ?.filter { it.method != Reminders.METHOD_EMAIL && it.method != Reminders.METHOD_SMS }
                    ?.map { it.minutes }.orEmpty()
            }
            for (f in AllDayPlan.fires(o.firstDay, o.lastDay, setting, zone)) {
                val key = AllDayPlan.key(o.eventId, o.begin, f.kind)
                if (key in out) continue
                val snoozeAt = snoozed[key]
                val atMs: Long
                val until: Long
                if (snoozeAt != null) {
                    // A snooze outlives its own day: it holds to the end of the event's last day.
                    atMs = snoozeAt
                    until = o.lastDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                } else {
                    if (key in firedKeys) continue
                    if (f.validUntilMs <= now) continue
                    if (AllDayPlan.coveredByOwn(f, o.firstDay, own, zone)) continue
                    atMs = f.atMs
                    until = f.validUntilMs
                }
                out[key] = Planned(key, o.eventId, o.begin, o.end, o.title, o.location, f.kind, atMs, until)
            }
        }
        return out.values.toList()
    }

    private fun readSnoozes(state: android.content.SharedPreferences): MutableMap<String, Long> =
        (state.getString(SNOOZED, null) ?: "").split(';').mapNotNull {
            val i = it.lastIndexOf('=')
            if (i < 0) null else it.substring(0, i) to (it.substring(i + 1).toLongOrNull() ?: return@mapNotNull null)
        }.toMap().toMutableMap()

    private fun writeSnoozes(map: Map<String, Long>): String = map.entries.joinToString(";") { "${it.key}=${it.value}" }

    private fun pending(context: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context, REQUEST,
            Intent(context, AlertReceiver::class.java).setAction(ACTION),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Exact where the phone allows it, as the provider's own alerts are; none when nothing is due. */
    private fun setAlarm(context: Context, next: Long?, now: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        if (next == null) {
            pending(context, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
            return
        }
        val pi = pending(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val soon = next <= now + REFRESH_MS
        runCatching {
            when {
                !soon -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + REFRESH_MS, pi)
                am.canScheduleExactAlarms() -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
                else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            }
        }.onFailure { Log.w(TAG, "Could not set the all-day alarm", it) }
    }
}
