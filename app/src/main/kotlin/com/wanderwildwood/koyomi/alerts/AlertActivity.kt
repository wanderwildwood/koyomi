package com.wanderwildwood.koyomi.alerts

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.koyomi.MainActivity
import com.wanderwildwood.koyomi.R
import com.wanderwildwood.koyomi.data.CalendarStore
import com.wanderwildwood.koyomi.ui.Dates
import com.wanderwildwood.koyomi.ui.monochrome
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * A reminder on the whole screen, over the lock screen if need be. A notification on a phone
 * with no light is easy to miss; this is not. It says what and when, and offers three things.
 *
 * One screen per reminder, stacked if two fall due together. It was `singleInstance` at
 * first, and a second reminder was then handed to the screen already showing the first, which
 * went on showing — and snoozing — the wrong event.
 */
class AlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val eventId = intent.getLongExtra(Alerts.EXTRA_EVENT, -1)
        val begin = intent.getLongExtra(Alerts.EXTRA_BEGIN, -1)
        val end = intent.getLongExtra(Alerts.EXTRA_END, -1)
        val record = CalendarStore(this).event(eventId)
        if (record == null) {
            finish()
            return
        }
        val zone = ZoneId.systemDefault()
        val start = CalendarStore.toLocal(begin, record.allDay, zone)
        val finish = CalendarStore.toLocal(end, record.allDay, zone)

        fun inBackground(block: () -> Unit) {
            thread { block() }
            finish()
        }

        setContent {
            ThemeMMD(colorScheme = monochrome) {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.systemBarsPadding().padding(24.dp)) {
                        // Below where the heads-up notification lands for its few seconds.
                        Spacer(Modifier.height(140.dp))
                        TextMMD(
                            text = record.title.ifBlank { stringResource(R.string.untitled) },
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        TextMMD(
                            text = Dates.span(LocalContext.current, start, finish, record.allDay),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        record.location?.let {
                            Spacer(Modifier.height(6.dp))
                            TextMMD(text = it, style = MaterialTheme.typography.bodyLarge)
                        }
                        Spacer(Modifier.weight(1f))
                        ButtonMMD(
                            onClick = { inBackground { Alerts.dismiss(applicationContext, eventId, begin) } },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { TextMMD(text = stringResource(R.string.alert_dismiss), style = MaterialTheme.typography.bodyMedium) }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButtonMMD(
                            onClick = { inBackground { Alerts.snooze(applicationContext, eventId, begin, end) } },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            TextMMD(
                                text = stringResource(R.string.alert_snooze, Alerts.SNOOZE_MINUTES),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButtonMMD(
                            onClick = {
                                startActivity(
                                    Intent(this@AlertActivity, MainActivity::class.java)
                                        .putExtra(Alerts.EXTRA_EVENT, eventId)
                                        .putExtra(Alerts.EXTRA_BEGIN, begin)
                                        .putExtra(Alerts.EXTRA_END, end)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                                )
                                inBackground { Alerts.dismiss(applicationContext, eventId, begin) }
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) { TextMMD(text = stringResource(R.string.alert_open), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}
