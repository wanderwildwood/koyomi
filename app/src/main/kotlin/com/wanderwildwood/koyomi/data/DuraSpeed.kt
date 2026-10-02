package com.wanderwildwood.koyomi.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings

/**
 * DuraSpeed, MediaTek's background manager on the Kompakt, force-stops installed apps a few
 * minutes after the screen goes dark, and a force-stop cancels every alarm the app has set: the
 * reminders do not go off. Apps switched on in its list are left alone, but that list cannot be
 * read by another app, and Settings has no way into it. Its App info page can be opened, and
 * has an Open button, so that is where the button goes; whether it was done is the person's word.
 *
 * A system force-stop while DuraSpeed is on takes that word back: DuraSpeed does not stop apps
 * on its list. Android records the stop as "stop <package> due to from pid N"; a Force stop by
 * hand reads the same and is the one case this gets wrong.
 */
object DuraSpeed {

    private const val PACKAGE = "com.mediatek.duraspeed"

    fun isKompakt(): Boolean = Build.MANUFACTURER.equals("Mudita", ignoreCase = true)

    /** DuraSpeed's own switch; null where the phone does not say. */
    fun isOn(context: Context): Boolean? = runCatching {
        val cr = context.contentResolver
        (AndroidSettings.Global.getString(cr, "setting.duraspeed.enabled")
            ?: AndroidSettings.System.getString(cr, "setting.duraspeed.enabled"))?.let { it != "0" }
    }.getOrNull()

    /** Whether reminders may be cancelled: the Settings row shows while this is so. */
    fun atRisk(context: Context, settings: Settings): Boolean {
        if (!isKompakt()) return false
        val on = isOn(context) != false
        val am = context.getSystemService(ActivityManager::class.java)
        val stop = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 0) }
            .getOrDefault(emptyList())
            .filter {
                it.reason == ApplicationExitInfo.REASON_USER_REQUESTED &&
                    it.description?.contains("due to from pid") == true
            }
            .maxOfOrNull { it.timestamp }
        if (stop != null && stop > settings.duraSpeedStopSeen) {
            settings.duraSpeedStopSeen = stop
            // Only a stop while DuraSpeed is on is DuraSpeed's.
            if (on) settings.duraSpeedAllowed = false
        }
        return on && !settings.duraSpeedAllowed
    }

    fun appInfo(): Intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:$PACKAGE"))
}
