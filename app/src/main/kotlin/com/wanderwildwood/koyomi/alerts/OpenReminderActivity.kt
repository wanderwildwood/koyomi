package com.wanderwildwood.koyomi.alerts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.wanderwildwood.koyomi.MainActivity
import com.wanderwildwood.koyomi.data.AllDayKind
import kotlin.concurrent.thread

/**
 * A pressed reminder: puts the reminder away and opens its event. Not exported, so only the
 * app's own notifications reach it. The main screen is open to every app, and a reminder put
 * away from there could be put away by any of them; it now only opens the event it is given.
 */
class OpenReminderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val eventId = intent.getLongExtra(Alerts.EXTRA_EVENT, -1)
        val begin = intent.getLongExtra(Alerts.EXTRA_BEGIN, -1)
        val kind = AllDayKind.of(intent.getIntExtra(Alerts.EXTRA_KIND, 0))
        val key = intent.getStringExtra(Alerts.EXTRA_ALL_DAY_KEY)
        if (eventId >= 0) {
            val app = applicationContext
            thread(name = "alerts") { Alerts.dismiss(app, eventId, begin, kind, key) }
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtras(intent)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        finish()
    }
}
