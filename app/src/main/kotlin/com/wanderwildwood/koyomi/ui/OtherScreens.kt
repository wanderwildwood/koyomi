package com.wanderwildwood.koyomi.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.koyomi.AppModel
import com.wanderwildwood.koyomi.R
import com.wanderwildwood.koyomi.Screen
import com.wanderwildwood.koyomi.data.Occurrence
import com.wanderwildwood.koyomi.data.View
import kotlinx.coroutines.delay

// ----------------------------------------------------------------------------------------
// Search

@Composable
fun SearchScreen(model: AppModel) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Occurrence>?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        delay(400)
        results = model.search(query)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.search_title)) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.cd_close)) { model.pop() } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            TextFieldMMD(
                value = query,
                onValueChange = { query = it },
                label = { TextMMD(text = stringResource(R.string.search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus),
            )
            val found = results
            when {
                found == null -> Unit
                found.isEmpty() -> TextMMD(
                    text = stringResource(R.string.search_none),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 20.dp),
                )
                else -> LazyColumnMMD(Modifier.fillMaxSize()) {
                    found.forEach { o ->
                        item(key = "${o.eventId}/${o.begin}") {
                            AgendaRow(
                                time = Dates.medium(o.firstDay),
                                occurrence = o,
                                onClick = { model.push(Screen.Event(o.eventId, o.begin, o.end)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------------------
// Settings

@Composable
fun SettingsScreen(model: AppModel) {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val v = model.settingsVersion
    val s = model.settings
    var aboutOpen by remember { mutableStateOf(false) }
    var pickCalendar by remember { mutableStateOf(false) }
    var pickReminder by remember { mutableStateOf(false) }

    // Re-read on every return from the phone's settings, where these are changed.
    var checks by remember { mutableStateOf(0) }
    val notificationsOn = remember(checks) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val exactOn = remember(checks) { context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms() }
    val fullScreenOn = remember(checks) {
        android.os.Build.VERSION.SDK_INT < 34 ||
            context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose { }
    }

    fun open(intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.settings_title)) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.cd_close)) { model.pop() } },
                actions = { BarButton(Icons.Info, stringResource(R.string.cd_about)) { aboutOpen = true } },
            )
        },
    ) { padding ->
        LazyColumnMMD(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            item { Spacer(Modifier.height(8.dp)) }

            // Only what is wrong is shown: a row for a switch that is already on would be
            // furniture, and furniture is not read.
            if (!notificationsOn) {
                item {
                    SettingRow(stringResource(R.string.settings_notifications_off), stringResource(R.string.settings_fix)) {
                        open(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                }
            }
            if (!exactOn) {
                item {
                    SettingRow(stringResource(R.string.settings_alarms_off), stringResource(R.string.settings_fix)) {
                        open(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                    }
                }
            }

            item { SettingRow(stringResource(R.string.settings_calendars), null) { model.push(Screen.Calendars) } }
            item {
                val name = model.calendars.firstOrNull { it.id == s.defaultCalendar }?.name
                SettingRow(stringResource(R.string.settings_default_calendar), name ?: stringResource(R.string.settings_first_calendar)) { pickCalendar = true }
            }
            item {
                val m = s.defaultReminder
                SettingRow(
                    stringResource(R.string.settings_default_reminder),
                    if (m < 0) stringResource(R.string.reminder_none) else ReminderText.of(context, m, false),
                ) { pickReminder = true }
            }
            item {
                val names = mapOf(
                    View.MONTH to R.string.view_month,
                    View.WEEK to R.string.view_week,
                    View.DAY to R.string.view_day,
                    View.AGENDA to R.string.view_agenda,
                )
                SettingRow(stringResource(R.string.settings_open_on), stringResource(names.getValue(s.openOn))) {
                    s.openOn = View.entries[(s.openOn.ordinal + 1) % View.entries.size]
                    model.settingsChanged()
                }
            }
            item {
                SettingRow(
                    stringResource(R.string.settings_week_starts),
                    s.weekStart.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()),
                ) {
                    s.weekStart = when (s.weekStart) {
                        java.time.DayOfWeek.MONDAY -> java.time.DayOfWeek.SUNDAY
                        java.time.DayOfWeek.SUNDAY -> java.time.DayOfWeek.SATURDAY
                        else -> java.time.DayOfWeek.MONDAY
                    }
                    model.settingsChanged()
                }
            }
            item {
                SwitchRow(stringResource(R.string.settings_week_numbers), s.weekNumbers) {
                    s.weekNumbers = it
                    model.settingsChanged()
                }
            }
            item {
                SwitchRow(stringResource(R.string.settings_wake), s.wakeScreen) {
                    s.wakeScreen = it
                    model.settingsChanged()
                }
            }
            if (s.wakeScreen && !fullScreenOn) {
                item {
                    SettingRow(stringResource(R.string.settings_full_screen_off), stringResource(R.string.settings_fix)) {
                        open(Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", Uri.parse("package:${context.packageName}")))
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (aboutOpen) AboutDialog(onDismiss = { aboutOpen = false })

    if (pickCalendar) {
        EInkDialog(onDismiss = { pickCalendar = false }) {
            TextMMD(text = stringResource(R.string.settings_default_calendar), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            model.writableCalendars().forEach { c ->
                ChoiceRow(c.name, bold = c.id == s.defaultCalendar) {
                    s.defaultCalendar = c.id
                    model.settingsChanged()
                    pickCalendar = false
                }
            }
        }
    }
    if (pickReminder) {
        EInkDialog(onDismiss = { pickReminder = false }) {
            TextMMD(text = stringResource(R.string.settings_default_reminder), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            listOf(-1, 0, 5, 10, 15, 30, 60, 1440).forEach { m ->
                ChoiceRow(
                    if (m < 0) stringResource(R.string.reminder_none) else ReminderText.of(context, m, false),
                    bold = m == s.defaultReminder,
                ) {
                    s.defaultReminder = m
                    model.settingsChanged()
                    pickReminder = false
                }
            }
        }
    }
}

/** Which calendars show. Hiding one here hides it in every calendar app on the phone. */
@Composable
fun CalendarsScreen(model: AppModel) {
    LaunchedEffect(Unit) { model.refreshCalendars() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.settings_calendars)) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.cd_close)) { model.pop() } },
            )
        },
    ) { padding ->
        LazyColumnMMD(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            if (model.calendars.isEmpty()) {
                item {
                    TextMMD(
                        text = stringResource(R.string.calendars_none),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            model.calendars.groupBy { it.accountName }.forEach { (account, list) ->
                item(key = "a$account") {
                    TextMMD(
                        text = account,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 18.dp, bottom = 2.dp),
                    )
                }
                list.forEach { c ->
                    item(key = "c${c.id}") {
                        SwitchRow(c.name, c.visible) { model.setVisible(c, it) }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ----------------------------------------------------------------------------------------
// Permission

/** Shown until calendar access is granted: what the app needs it for, and one button. */
@Composable
fun PermissionScreen(onAllow: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { TopAppBarMMD(title = { TextMMD(text = stringResource(R.string.app_name)) }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            TextMMD(text = stringResource(R.string.permission_why), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            TextMMD(text = stringResource(R.string.permission_davx5), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(24.dp))
            ButtonMMD(onClick = onAllow, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                TextMMD(text = stringResource(R.string.permission_allow), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
