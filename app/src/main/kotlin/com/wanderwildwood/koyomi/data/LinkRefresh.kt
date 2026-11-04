package com.wanderwildwood.koyomi.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.wanderwildwood.koyomi.R
import com.wanderwildwood.koyomi.alerts.Alerts
import java.time.ZoneId

/**
 * Followed calendar links (Proton's, Google's secret address…) are fetched again when the app
 * opens, and also in the background every few hours by [LinkRefreshJob], so a followed calendar
 * stays current without opening Datebook. A fetch that fails keeps the last copy.
 */
object LinkRefresh {

    private const val JOB_ID = 0x6b6f7901
    private const val EVERY_MS = 3 * 60 * 60 * 1000L

    /** Fetches [sub] once and keeps what came back in the calendar store. */
    fun refreshOne(context: Context, sub: Subscription): Refreshed =
        SubscriptionSync.refresh(
            sub, HttpFetcher(), SubscriptionTarget(CalendarStore(context)::replaceSubscriptionEvents),
            ZoneId.systemDefault(), System.currentTimeMillis(), { failureText(context, it) },
            notACalendar = context.getString(R.string.link_err_not_calendar),
            notSaved = context.getString(R.string.link_err_not_saved),
        )

    /** Fetches every followed link, one after another. True when any calendar changed. */
    fun refreshAll(context: Context): Boolean {
        val links = SubscriptionStore(context)
        var changed = false
        for (sub in links.all()) {
            when (val out = refreshOne(context, sub)) {
                is Refreshed.Done -> { links.put(out.subscription); changed = true }
                is Refreshed.Kept -> links.put(out.subscription)
            }
        }
        return changed
    }

    fun failureText(context: Context, f: Fetched.Failed): String = when (f.kind) {
        Fetched.Kind.SERVER -> f.message
        Fetched.Kind.NO_HOST -> context.getString(R.string.link_err_no_host)
        Fetched.Kind.TIMEOUT -> context.getString(R.string.link_err_timeout)
        Fetched.Kind.CERTIFICATE -> context.getString(R.string.link_err_certificate)
        Fetched.Kind.UNREACHABLE -> context.getString(R.string.link_err_unreachable)
        Fetched.Kind.TOO_BIG -> context.getString(R.string.link_err_too_big)
        Fetched.Kind.OPEN -> context.getString(R.string.link_err_open)
    }

    /**
     * Keeps the background fetch scheduled while any link is followed, and stops it when none
     * is. No network condition is set (that needs a permission this app does without): a fetch
     * with no network fails quietly and the next one tries again.
     */
    fun schedule(context: Context) {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        if (SubscriptionStore(context).all().isEmpty()) { jobs.cancel(JOB_ID); return }
        if (jobs.getPendingJob(JOB_ID) != null) return
        jobs.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, LinkRefreshJob::class.java))
                .setPeriodic(EVERY_MS)
                .setPersisted(true)
                .build()
        )
    }
}

class LinkRefreshJob : JobService() {
    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        if (!CalendarStore(app).canWrite()) return false
        worker = Thread {
            runCatching { if (LinkRefresh.refreshAll(app)) Alerts.check(app) }
            jobFinished(params, false)
        }.apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return false
    }
}
