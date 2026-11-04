package com.wanderwildwood.koyomi.ui

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.koyomi.AppModel
import com.wanderwildwood.koyomi.R
import com.wanderwildwood.koyomi.Screen
import com.wanderwildwood.koyomi.data.Occurrence
import com.wanderwildwood.koyomi.data.View
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                else -> PagedColumn(Modifier.fillMaxSize()) {
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
    // Read here, not inside the list's items: a SharedPreferences read is not Compose state,
    // so an item that read it itself was never redrawn, and a switch looked as if it had not
    // moved until the screen was opened again. Read at this level, a new settingsVersion
    // hands every row its new value.
    @Suppress("UNUSED_VARIABLE") val v = model.settingsVersion
    val s = model.settings
    val defaultCalendar = s.defaultCalendar
    val defaultReminder = s.defaultReminder
    val openOn = s.openOn
    val weekStart = s.weekStart
    val weekNumbers = s.weekNumbers
    val turnWithPhone = s.turnWithPhone
    val wakeScreen = s.wakeScreen
    val lockScreen = s.lockScreen
    val lockScreenNext = s.lockScreenNext
    var aboutOpen by remember { mutableStateOf(false) }
    val linkFiles = rememberLinkFiles(model)
    var pickCalendar by remember { mutableStateOf(false) }
    var pickReminder by remember { mutableStateOf(false) }
    var allDayPick by remember { mutableStateOf(false) }
    var allDayFor by remember { mutableStateOf<com.wanderwildwood.koyomi.data.CalendarInfo?>(null) }

    // Re-read on every return from the phone's settings, where these are changed.
    var checks by remember { mutableStateOf(0) }
    val notificationsOn = remember(checks) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val exactOn = remember(checks) { com.wanderwildwood.koyomi.alerts.Alerts.exactAlarmsAllowed(context.getSystemService(android.app.AlarmManager::class.java)) }
    val fullScreenOn = remember(checks) {
        android.os.Build.VERSION.SDK_INT < 34 ||
            context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
    }
    val duraSpeedRisk = remember(checks, v) { com.wanderwildwood.koyomi.data.DuraSpeed.atRisk(context, s) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        checks++
        onPauseOrDispose { }
    }

    fun open(intent: Intent) = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    LaunchedEffect(Unit) { model.refreshCalendars() }

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
        val state = rememberLazyListState()
        PagedColumn(
            Modifier.padding(padding).fillMaxSize(),
            state = state,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 12.dp),
            onPullAtTop = { model.refreshSubscriptions(force = true) },
        ) {
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
            // On a Kompakt, DuraSpeed shuts the app down and its reminders with it. Its list
            // cannot be read, so the second row is how the person says Calendar is on it.
            if (duraSpeedRisk) {
                item {
                    SettingRow(stringResource(R.string.settings_duraspeed), stringResource(R.string.settings_duraspeed_open)) {
                        open(com.wanderwildwood.koyomi.data.DuraSpeed.appInfo())
                    }
                }
                item {
                    SettingRow(stringResource(R.string.settings_duraspeed_done), null) {
                        s.duraSpeedAllowed = true
                        model.settingsChanged()
                    }
                }
            }

            // The calendars first, their switches right here: which ones show is changed far
            // more often than anything below, and a screen of its own was a press for nothing.
            calendarSwitches(model, linkFiles)
            // Syncing is another app's work -- DAVx5, ICSx5, whatever keeps the calendars --
            // and this row only asks it to do that work now. A reader on a long sync interval
            // who has just changed something elsewhere wants to be sure before looking, and
            // should not have to open the syncing app to say so. Asked on the forum.
            item {
                var asked by remember { mutableStateOf(false) }
                LaunchedEffect(asked) { if (asked) { delay(6000); asked = false } }
                SettingRow(
                    stringResource(R.string.settings_sync_now),
                    stringResource(if (asked) R.string.settings_sync_asked else R.string.settings_sync_now_hint),
                ) {
                    com.wanderwildwood.koyomi.data.CalendarSync.requestNow()
                    asked = true
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
            item {
                val on = s.allDayOn().size
                SettingRow(
                    stringResource(R.string.settings_all_day),
                    if (on == 0) stringResource(R.string.settings_all_day_off)
                    else pluralStringResource(R.plurals.settings_all_day_on, on, on),
                ) {
                    val only = model.calendars.singleOrNull()
                    if (only != null) allDayFor = only else allDayPick = true
                }
            }
            item {
                val name = model.calendars.firstOrNull { it.id == defaultCalendar }?.name
                SettingRow(stringResource(R.string.settings_default_calendar), name ?: stringResource(R.string.settings_first_calendar)) { pickCalendar = true }
            }
            item {
                val m = defaultReminder
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
                SettingRow(stringResource(R.string.settings_open_on), stringResource(names.getValue(openOn))) {
                    s.openOn = View.entries[(s.openOn.ordinal + 1) % View.entries.size]
                    model.settingsChanged()
                }
            }
            item {
                SettingRow(
                    stringResource(R.string.settings_week_starts),
                    weekStart.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()),
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
                SwitchRow(stringResource(R.string.settings_week_numbers), weekNumbers) {
                    s.weekNumbers = it
                    model.settingsChanged()
                }
            }
            item {
                SwitchRow(stringResource(R.string.settings_turn), turnWithPhone) {
                    s.turnWithPhone = it
                    model.settingsChanged()
                }
            }
            item {
                SwitchRow(stringResource(R.string.settings_wake), wakeScreen) {
                    s.wakeScreen = it
                    model.settingsChanged()
                }
            }
            item {
                SwitchRow(stringResource(R.string.settings_lock_screen), lockScreen) {
                    s.lockScreen = it
                    model.settingsChanged()
                    com.wanderwildwood.koyomi.glance.GlanceProvider.changed(context)
                }
            }
            if (lockScreen) {
                item {
                    SwitchRow(stringResource(R.string.settings_lock_screen_next), lockScreenNext) {
                        s.lockScreenNext = it
                        model.settingsChanged()
                        com.wanderwildwood.koyomi.glance.GlanceProvider.changed(context)
                    }
                }
            }
            if (wakeScreen && !fullScreenOn) {
                item {
                    SettingRow(stringResource(R.string.settings_full_screen_off), stringResource(R.string.settings_fix)) {
                        open(Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", Uri.parse("package:${context.packageName}")))
                    }
                }
            }
            // This app only reaches the network for the calendar links it was given, never to ask
            // what is newest; the row opens the releases page, where the newest version is first.
            item {
                SettingRow(
                    stringResource(R.string.settings_update),
                    stringResource(R.string.settings_update_note, com.wanderwildwood.koyomi.BuildConfig.VERSION_NAME),
                ) {
                    openLink(context, "https://github.com/wanderwildwood/koyomi/releases/latest", context.getString(R.string.about_no_browser_address, "github.com/wanderwildwood/koyomi/releases/latest"))
                }
            }
        }
    }

    if (aboutOpen) AboutDialog(onDismiss = { aboutOpen = false })

    if (pickCalendar) {
        EInkDialog(onDismiss = { pickCalendar = false }) {
            TextMMD(text = stringResource(R.string.settings_default_calendar), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            model.writableCalendars().forEach { c ->
                ChoiceRow(c.name, bold = c.id == defaultCalendar) {
                    s.defaultCalendar = c.id
                    model.settingsChanged()
                    pickCalendar = false
                }
            }
        }
    }
    if (allDayPick) {
        EInkDialog(onDismiss = { allDayPick = false }) {
            TextMMD(text = stringResource(R.string.all_day_which), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            model.calendars.forEach { c ->
                ChoiceRow(c.name, bold = s.allDay(c.id).mode != com.wanderwildwood.koyomi.data.AllDayMode.OFF) {
                    allDayPick = false
                    allDayFor = c
                }
            }
        }
    }
    allDayFor?.let { c ->
        AllDayDialog(
            calendarName = c.name,
            initial = s.allDay(c.id),
            onSave = {
                s.setAllDay(c.id, it)
                model.settingsChanged()
                val app = context.applicationContext
                kotlin.concurrent.thread { com.wanderwildwood.koyomi.alerts.AllDayReminders.check(app) }
            },
            onDismiss = { allDayFor = null },
        )
    }
    if (pickReminder) {
        EInkDialog(onDismiss = { pickReminder = false }) {
            TextMMD(text = stringResource(R.string.settings_default_reminder), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            listOf(-1, 0, 5, 10, 15, 30, 60, 1440).forEach { m ->
                ChoiceRow(
                    if (m < 0) stringResource(R.string.reminder_none) else ReminderText.of(context, m, false),
                    bold = m == defaultReminder,
                ) {
                    s.defaultReminder = m
                    model.settingsChanged()
                    pickReminder = false
                }
            }
        }
    }
}

/**
 * Which calendars show, as the first section of Settings. Hiding one here hides it in every
 * calendar app on the phone, because it is the phone's own switch, and the line under the
 * heading says so.
 */
private fun LazyListScope.calendarSwitches(model: AppModel, links: LinkFiles) {
    item(key = "calendars") {
        Column(Modifier.padding(top = 6.dp, bottom = 2.dp)) {
            TextMMD(text = stringResource(R.string.settings_calendars), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            TextMMD(text = stringResource(R.string.calendars_everywhere), style = MaterialTheme.typography.labelSmall)
        }
    }
    // A local calendar has no account behind it, which its name cannot say, so it says so on
    // its own row. The one most people meet is "PC Sync", made by the phone's own calendar
    // store (MediaTek's), hidden, and easily taken for a way to sync.
    val followed = model.subscriptions.associateBy { it.calendarId }
    val (local, synced) = model.calendars.filter { it.id !in followed }.partition { it.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL }
    // With nothing synced, say where synced calendars come from. Only local calendars is the
    // usual case, not an empty phone: "PC Sync" is there from the start, and an app that
    // syncs into its own database (KashCal with iCloud) adds nothing here at all.
    if (synced.isEmpty()) {
        item(key = "davx5") {
            val context = LocalContext.current
            TextMMD(
                text = stringResource(if (local.isEmpty()) R.string.calendars_none else R.string.calendars_no_sync),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .clickable {
                        openLink(context, "https://www.davx5.com", context.getString(R.string.about_no_browser_address, "www.davx5.com"))
                    }
                    .padding(vertical = 8.dp),
            )
        }
    }
    // KompaktOS has no calendar on the phone, so without an account there was nowhere for an
    // event to go. Only offered while there is no calendar that can be written to at all.
    if (model.noCalendarToWrite) {
        item(key = "make-local") {
            SettingRow(stringResource(R.string.calendar_make_local), stringResource(R.string.calendar_make_local_note)) {
                model.makeLocalCalendar()
            }
        }
    }
    synced.groupBy { it.accountName }.forEach { (account, list) ->
        item(key = "a$account") {
            TextMMD(
                text = account,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        list.forEach { c ->
            item(key = "c${c.id}") {
                SwitchRow(c.name, c.visible, compact = true) { model.setVisible(c, it) }
            }
        }
    }
    local.forEach { c ->
        item(key = "c${c.id}") {
            SwitchRow(c.name, c.visible, note = stringResource(R.string.calendars_local), compact = true) { model.setVisible(c, it) }
        }
    }
    // Calendars followed by a link: read only, fetched again when the app opens and on a pull
    // down. The address is a secret and is never shown; the server's name is.
    if (model.subscriptions.isNotEmpty()) {
        item(key = "links") {
            TextMMD(
                text = stringResource(R.string.link_section),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    model.subscriptions.forEach { sub ->
        val c = model.calendars.firstOrNull { it.id == sub.calendarId }
        item(key = "l${sub.calendarId}") {
            val context = LocalContext.current
            val note = when {
                model.fetching -> stringResource(R.string.link_fetching)
                sub.error != null -> stringResource(R.string.link_failed, sub.error)
                sub.fetchedAt > 0 -> stringResource(
                    R.string.link_updated,
                    com.wanderwildwood.koyomi.data.CalendarLink.hostOf(sub.url),
                    android.text.format.DateUtils.formatDateTime(
                        context, sub.fetchedAt,
                        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_ALL,
                    ),
                )
                else -> com.wanderwildwood.koyomi.data.CalendarLink.hostOf(sub.url)
            }
            Column {
                SwitchRow(sub.name, c?.visible ?: true, note = note, compact = true) { if (c != null) model.setVisible(c, it) }
                ArmedRow(stringResource(R.string.link_remove), stringResource(R.string.link_remove_armed)) { model.unfollow(sub) }
            }
        }
    }
    item(key = "server") {
        SettingRow(stringResource(R.string.server_row), stringResource(R.string.server_row_note)) { model.push(Screen.Server) }
    }
    item(key = "add-link") {
        SettingRow(stringResource(R.string.link_add_row), stringResource(R.string.link_add_row_note)) { model.push(Screen.AddLink) }
    }
    // The links are left out of Android's backups (they are secrets), so a new phone gets them
    // from a file the person keeps. Restoring only adds, so it needs no second press.
    if (model.subscriptions.isNotEmpty()) {
        item(key = "save-links") {
            SettingRow(stringResource(R.string.links_save), stringResource(R.string.links_save_note)) { links.save() }
        }
    }
    item(key = "restore-links") {
        SettingRow(stringResource(R.string.links_restore), stringResource(R.string.links_restore_note)) { links.restore() }
    }
}

/** The two file pickers for the calendar links, made in a composable and used from the list. */
private class LinkFiles(val save: () -> Unit, val restore: () -> Unit)

@Composable
private fun rememberLinkFiles(model: AppModel): LinkFiles {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val saveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = model.saveLinks(uri)
            Toast.makeText(context, if (ok) R.string.links_saved else R.string.links_not_saved, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
    }
    val restoreLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = when (val r = model.restoreLinks(uri)) {
                is AppModel.LinksRestored.Done -> context.getString(R.string.links_restored, r.added, r.already)
                is AppModel.LinksRestored.Refused -> context.getString(
                    when (r.kind) {
                        com.wanderwildwood.koyomi.data.LinkList.Refused.Kind.NOT_A_BACKUP -> R.string.links_not_ours
                        com.wanderwildwood.koyomi.data.LinkList.Refused.Kind.NEWER -> R.string.links_newer
                        com.wanderwildwood.koyomi.data.LinkList.Refused.Kind.DAMAGED -> R.string.links_damaged
                    },
                )
                AppModel.LinksRestored.Unreadable -> context.getString(R.string.links_unreadable)
                AppModel.LinksRestored.NoPermission -> context.getString(R.string.link_err_no_permission)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    return remember(saveLauncher, restoreLauncher) {
        LinkFiles(
            save = { runCatching { saveLauncher.launch("datebook-links-${java.time.LocalDate.now()}.txt") } },
            restore = { runCatching { restoreLauncher.launch(arrayOf("text/plain", "application/octet-stream")) } },
        )
    }
}

// ----------------------------------------------------------------------------------------
// Permission

/**
 * Shown until calendar access is granted: what the app needs it for, one button, and About
 * (Privacy, Terms, Licences) so those can be read before anything is allowed. After a refusal it
 * says so and offers the app's own settings, because Android stops asking after two.
 */
@Composable
fun PermissionScreen(denied: Boolean, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    var aboutOpen by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.app_name)) },
                actions = { BarButton(Icons.Info, stringResource(R.string.cd_about)) { aboutOpen = true } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            TextMMD(text = stringResource(R.string.permission_why), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            TextMMD(text = stringResource(R.string.permission_davx5), style = MaterialTheme.typography.labelSmall)
            if (denied) {
                Spacer(Modifier.height(14.dp))
                TextMMD(text = stringResource(R.string.permission_denied), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(24.dp))
            ButtonMMD(onClick = onAllow, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                TextMMD(text = stringResource(R.string.permission_allow), style = MaterialTheme.typography.bodySmall)
            }
            if (denied) {
                Spacer(Modifier.height(12.dp))
                OutlinedButtonMMD(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    TextMMD(text = stringResource(R.string.permission_open_settings), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (aboutOpen) AboutDialog(onDismiss = { aboutOpen = false })
}
