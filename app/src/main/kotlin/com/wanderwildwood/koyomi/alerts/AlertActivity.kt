package com.wanderwildwood.koyomi.alerts

import com.wanderwildwood.koyomi.ui.DarkSelection
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.koyomi.MainActivity
import com.wanderwildwood.koyomi.R
import com.wanderwildwood.koyomi.data.AllDayKind
import com.wanderwildwood.koyomi.data.CalendarStore
import com.wanderwildwood.koyomi.data.EventRecord
import com.wanderwildwood.koyomi.ui.Dates
import com.wanderwildwood.koyomi.ui.houseTypography
import com.wanderwildwood.koyomi.ui.monochrome
import java.time.ZoneId
import kotlin.concurrent.thread
import com.wanderwildwood.koyomi.ui.ArmedScope

/**
 * A reminder on the whole screen, over the lock screen if need be. A notification on a phone
 * with no light is easy to miss; this is not. It says what and when, and offers three things.
 *
 * One screen per reminder, stacked if two fall due together. It was `singleInstance` at
 * first, and a second reminder was then handed to the screen already showing the first, which
 * went on showing — and snoozing — the wrong event.
 *
 * Over a locked phone it says only "Reminder" and the time, not the event's name or place, so
 * whoever holds the phone does not read them; Dismiss and Again still work without unlocking.
 * Once the phone is unlocked the screen shows the event. Open the event asks for the unlock first.
 */
class AlertActivity : ComponentActivity() {
    private var locked by mutableStateOf(true)

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refreshLocked()
    }

    private fun refreshLocked() {
        locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
    }

    override fun onResume() {
        super.onResume()
        refreshLocked()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshLocked()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlocked) }
        super.onDestroy()
    }

    /** Opens the event, once the phone is unlocked if it is locked. */
    private fun openAfterUnlock(open: () -> Unit) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26 && keyguard != null && keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    refreshLocked()
                    open()
                }
            })
        } else {
            open()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshLocked()
        ContextCompat.registerReceiver(
            this, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        val eventId = intent.getLongExtra(Alerts.EXTRA_EVENT, -1)
        val begin = intent.getLongExtra(Alerts.EXTRA_BEGIN, -1)
        val end = intent.getLongExtra(Alerts.EXTRA_END, -1)
        val kind = AllDayKind.of(intent.getIntExtra(Alerts.EXTRA_KIND, 0))
        val key = intent.getStringExtra(Alerts.EXTRA_ALL_DAY_KEY)
        // The event is read off the main thread: the screen that wakes the phone must not wait
        // on a calendar store a sync may be busy with. It is drawn once the event is in.
        // A followed calendar fetched again since the reminder was posted has new row ids; the
        // reminder's key still finds its event.
        val store = CalendarStore(applicationContext)
        thread {
            val record = runCatching { store.event(eventId) }.getOrNull()
                ?: key?.let { k -> runCatching { store.findByAllDayKey(k, begin) }.getOrNull()?.let { runCatching { store.event(it) }.getOrNull() } }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (record == null) finish() else show(record, eventId, begin, end, kind, key)
            }
        }
    }

    private fun show(record: EventRecord, eventId: Long, begin: Long, end: Long, kind: AllDayKind?, key: String?) {
        val zone = ZoneId.systemDefault()
        val start = CalendarStore.toLocal(begin, record.allDay, zone)
        val finish = CalendarStore.toLocal(end, record.allDay, zone)

        fun inBackground(block: () -> Unit) {
            thread { block() }
            finish()
        }

        setContent {
            ThemeMMD(colorScheme = monochrome, typography = houseTypography) { DarkSelection {
                ArmedScope {
                    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                        Column(Modifier.systemBarsPadding().padding(24.dp)) {
                            // Below where the heads-up notification lands for its few seconds.
                            Spacer(Modifier.height(140.dp))
                            // Locked: no name or place in front of whoever holds the phone.
                            TextMMD(
                                text = if (locked) stringResource(R.string.alert_hidden)
                                else Alerts.titleFor(LocalContext.current, record.title, kind),
                                style = MaterialTheme.typography.headlineLarge,
                            )
                            Spacer(Modifier.height(12.dp))
                            TextMMD(
                                text = Dates.span(LocalContext.current, start, finish, record.allDay),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (locked) {
                                Spacer(Modifier.height(6.dp))
                                TextMMD(
                                    text = stringResource(R.string.alert_unlock_to_see),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            record.location?.takeIf { !locked }?.let {
                                Spacer(Modifier.height(6.dp))
                                TextMMD(text = it, style = MaterialTheme.typography.bodyLarge)
                            }
                            Spacer(Modifier.weight(1f))
                            ButtonMMD(
                                onClick = { inBackground { Alerts.dismiss(applicationContext, eventId, begin, kind, key) } },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) { TextMMD(text = stringResource(R.string.alert_dismiss), style = MaterialTheme.typography.bodyMedium) }
                            Spacer(Modifier.height(12.dp))
                            OutlinedButtonMMD(
                                onClick = { inBackground { Alerts.snooze(applicationContext, eventId, begin, end, kind, key) } },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) {
                                TextMMD(
                                    text = stringResource(R.string.alert_snooze, Alerts.SNOOZE_MINUTES),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedButtonMMD(
                                onClick = { openAfterUnlock {
                                    startActivity(
                                        Intent(this@AlertActivity, MainActivity::class.java)
                                            .putExtra(Alerts.EXTRA_EVENT, eventId)
                                            .putExtra(Alerts.EXTRA_BEGIN, begin)
                                            .putExtra(Alerts.EXTRA_END, end)
                                            .also { if (kind != null) it.putExtra(Alerts.EXTRA_KIND, kind.code) }
                                            .also { if (key != null) it.putExtra(Alerts.EXTRA_ALL_DAY_KEY, key) }
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                                    )
                                    inBackground { Alerts.dismiss(applicationContext, eventId, begin, kind, key) }
                                } },
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) { TextMMD(text = stringResource(R.string.alert_open), style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            } }
        }
    }
}
