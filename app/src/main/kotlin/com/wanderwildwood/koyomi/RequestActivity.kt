package com.wanderwildwood.koyomi

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.koyomi.data.Prefill
import com.wanderwildwood.koyomi.ui.monochrome

/**
 * Where other apps' "add to calendar" lands, and their "show this event" and "open this .ics".
 *
 * It is its own activity, not the main one, so it opens inside the app that asked — as Etar's
 * EditEventActivity and ImportActivity do — and pressing save or back returns there rather
 * than leaving the reader in this calendar's month. Nothing is written until save or Add is
 * pressed.
 */
class RequestActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = request(intent)
        if (request == null) {
            finish()
            return
        }
        setContent {
            ThemeMMD(colorScheme = monochrome) {
                if (model.canRead) LaunchedEffect(Unit) { model.open(request) }
                Koyomi(model, finish = ::finish)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.permissionsChanged()
    }

    companion object {
        /**
         * The intent read the way Etar reads it: INSERT, or EDIT with no event, is a new event
         * from the extras; VIEW or EDIT of events/<id> is that event; VIEW of anything else is
         * a file.
         */
        fun request(intent: Intent?): Request? {
            intent ?: return null
            val data = intent.data
            val eventId = data?.takeIf { it.authority == CalendarContract.AUTHORITY }?.let(::eventId)
            return when (intent.action) {
                Intent.ACTION_INSERT, Intent.ACTION_EDIT ->
                    if (eventId != null && intent.action == Intent.ACTION_EDIT) {
                        Request.Show(eventId, begin(intent), end(intent), edit = true)
                    } else {
                        Request.New(prefill(intent))
                    }
                Intent.ACTION_VIEW -> when {
                    eventId != null -> Request.Show(eventId, begin(intent), end(intent), edit = false)
                    data != null && data.scheme in FILE_SCHEMES -> Request.Import(data)
                    else -> null
                }
                else -> null
            }
        }

        private val FILE_SCHEMES = setOf(ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_FILE)

        /** content://com.android.calendar/events/<id> */
        private fun eventId(uri: Uri): Long? {
            val segments = uri.pathSegments
            if (segments.size != 2 || segments[0] != "events") return null
            return segments[1].toLongOrNull()
        }

        private fun begin(intent: Intent) = intent.long(CalendarContract.EXTRA_EVENT_BEGIN_TIME)
        private fun end(intent: Intent) = intent.long(CalendarContract.EXTRA_EVENT_END_TIME)

        /** A long, or an int, or a number in a string: apps send all three. */
        private fun Intent.long(key: String): Long? = when (val v = extras?.get(key)) {
            is Long -> v
            is Int -> v.toLong()
            is String -> v.trim().toLongOrNull()
            else -> null
        }?.takeIf { it > 0 }

        private fun Intent.text(key: String): String? = extras?.get(key)?.toString()

        private fun prefill(intent: Intent) = Prefill(
            title = intent.text(Events.TITLE),
            location = intent.text(Events.EVENT_LOCATION),
            description = intent.text(Events.DESCRIPTION),
            rrule = intent.text(Events.RRULE),
            begin = begin(intent),
            end = end(intent),
            allDay = when (val v = intent.extras?.get(CalendarContract.EXTRA_EVENT_ALL_DAY)) {
                is Boolean -> v
                is Int -> v != 0
                is String -> v.equals("true", ignoreCase = true) || v == "1"
                else -> false
            },
            calendarId = intent.long(Events.CALENDAR_ID),
            appPackage = intent.text(Events.CUSTOM_APP_PACKAGE),
            appUri = intent.text(Events.CUSTOM_APP_URI),
        )
    }
}
