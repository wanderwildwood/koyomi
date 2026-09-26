package com.wanderwildwood.koyomi.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.koyomi.AppModel
import com.wanderwildwood.koyomi.R
import com.wanderwildwood.koyomi.Screen
import com.wanderwildwood.koyomi.data.Scope
import com.wanderwildwood.koyomi.repeat.RepeatRule
import com.wanderwildwood.koyomi.repeat.RepeatText
import kotlinx.coroutines.launch

/**
 * One screen for a new event and for a changed one. What cannot be changed is not offered:
 * the calendar of an event that already exists, and the repeat of a single occurrence.
 */
@Composable
fun EditScreen(model: AppModel, screen: Screen.Edit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val d = model.draft
    var picking by remember { mutableStateOf<Picking?>(null) }
    var saving by remember { mutableStateOf(false) }

    val isNew = screen.record == null
    val single = screen.scope == Scope.ONE
    val editableRule = d.rrule == null || RepeatRule.canEdit(d.rrule)

    fun save() {
        if (saving) return
        if (d.calendarId == null) {
            Toast.makeText(context, R.string.edit_no_calendar, Toast.LENGTH_LONG).show()
            return
        }
        saving = true
        scope.launch {
            if (model.save(screen)) {
                model.popAll()
            } else {
                saving = false
                Toast.makeText(context, R.string.edit_not_saved, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = {
                    TextMMD(
                        text = stringResource(
                            when {
                                isNew -> R.string.edit_new
                                single -> R.string.edit_one
                                else -> R.string.edit_title
                            },
                        ),
                    )
                },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.cd_discard)) { model.pop() } },
                actions = {
                    Box(Modifier.height(48.dp).clickable { save() }.padding(horizontal = 14.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        TextMMD(text = stringResource(R.string.save), style = MaterialTheme.typography.bodyMedium)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumnMMD(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            item {
                Spacer(Modifier.height(8.dp))
                TextFieldMMD(
                    value = d.title,
                    onValueChange = { model.draft = model.draft.copy(title = it) },
                    label = { TextMMD(text = stringResource(R.string.edit_what)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SwitchRow(stringResource(R.string.all_day), d.allDay) { on ->
                    // A reminder means something different on each side of this switch —
                    // minutes before a start, or a time of day before a date — so any that
                    // were set become the plain one for the new kind rather than a strange one.
                    val x = model.draft
                    val reminders = when {
                        x.reminders.isEmpty() -> emptyList()
                        on -> listOf(900)
                        else -> listOf(model.settings.defaultReminder.takeIf { it >= 0 } ?: 10)
                    }
                    model.draft = x.copy(allDay = on, reminders = reminders)
                }
            }
            item {
                When(
                    label = stringResource(R.string.edit_starts),
                    date = Dates.medium(d.startDate),
                    time = if (d.allDay) null else Dates.time(context, d.startTime),
                    onDate = { picking = Picking.START_DATE },
                    onTime = { picking = Picking.START_TIME },
                )
            }
            item {
                When(
                    label = stringResource(R.string.edit_ends),
                    date = Dates.medium(d.endDate),
                    time = if (d.allDay) null else Dates.time(context, d.endTime),
                    onDate = { picking = Picking.END_DATE },
                    onTime = { picking = Picking.END_TIME },
                )
            }
            if (!single) {
                item {
                    SettingRow(
                        title = stringResource(R.string.event_repeats),
                        value = RepeatText.of(context.resources, d.rrule, d.startDate, d.allDay) +
                            if (editableRule) "" else "\n" + stringResource(R.string.repeat_cannot_edit),
                        onClick = if (editableRule) ({ model.push(Screen.Repeat) }) else null,
                    )
                }
            }
            item {
                SettingRow(
                    title = stringResource(R.string.edit_reminders),
                    value = if (d.reminders.isEmpty()) {
                        stringResource(R.string.reminder_none)
                    } else {
                        d.reminders.joinToString("\n") { ReminderText.of(context, it, d.allDay) }
                    },
                    onClick = { picking = Picking.REMINDERS },
                )
            }
            item {
                TextFieldMMD(
                    value = d.location,
                    onValueChange = { model.draft = model.draft.copy(location = it) },
                    label = { TextMMD(text = stringResource(R.string.event_where)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                TextFieldMMD(
                    value = d.notes,
                    onValueChange = { model.draft = model.draft.copy(notes = it) },
                    label = { TextMMD(text = stringResource(R.string.event_notes)) },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            item {
                val calendar = model.calendars.firstOrNull { it.id == d.calendarId }
                SettingRow(
                    title = stringResource(R.string.event_calendar),
                    value = calendar?.name ?: stringResource(R.string.edit_no_calendar_short),
                    onClick = if (isNew) ({ picking = Picking.CALENDAR }) else null,
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    when (picking) {
        Picking.START_DATE -> DateDialog(d.startDate, onPick = { model.draft = moveStart(model.draft, it) }, onDismiss = { picking = null })
        Picking.END_DATE -> DateDialog(d.endDate, onPick = { date ->
            val x = model.draft
            model.draft = if (date.isBefore(x.startDate)) x.copy(startDate = date, endDate = date) else x.copy(endDate = date)
        }, onDismiss = { picking = null })
        Picking.START_TIME -> TimeDialog(d.startTime, onPick = { t ->
            // The event keeps its length when its start moves, as every calendar does.
            val x = model.draft
            val length = java.time.Duration.between(x.startDate.atTime(x.startTime), x.endDate.atTime(x.endTime))
            val start = x.startDate.atTime(t)
            val end = start.plus(length)
            model.draft = x.copy(startTime = t, endDate = end.toLocalDate(), endTime = end.toLocalTime())
        }, onDismiss = { picking = null })
        Picking.END_TIME -> TimeDialog(d.endTime, onPick = { t ->
            val x = model.draft
            var end = x.endDate.atTime(t)
            if (end.isBefore(x.startDate.atTime(x.startTime))) end = x.startDate.atTime(t).let { if (it.isBefore(x.startDate.atTime(x.startTime))) it.plusDays(1) else it }
            model.draft = x.copy(endDate = end.toLocalDate(), endTime = end.toLocalTime())
        }, onDismiss = { picking = null })
        Picking.REMINDERS -> RemindersDialog(d.allDay, d.reminders, onDone = { model.draft = model.draft.copy(reminders = it) }, onDismiss = { picking = null })
        Picking.CALENDAR -> EInkDialog(onDismiss = { picking = null }) {
            TextMMD(text = stringResource(R.string.event_calendar), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            val writable = model.writableCalendars()
            if (writable.isEmpty()) {
                TextMMD(text = stringResource(R.string.edit_no_calendar), style = MaterialTheme.typography.labelSmall)
            }
            writable.forEach { c ->
                ChoiceRow(c.name, bold = c.id == d.calendarId) {
                    model.draft = model.draft.copy(calendarId = c.id)
                    picking = null
                }
            }
        }
        null -> Unit
    }
}

/** A new start date carries the end with it, by the same number of days. */
private fun moveStart(d: com.wanderwildwood.koyomi.data.Draft, date: java.time.LocalDate): com.wanderwildwood.koyomi.data.Draft {
    val days = java.time.temporal.ChronoUnit.DAYS.between(d.startDate, d.endDate)
    return d.copy(startDate = date, endDate = date.plusDays(days))
}

private enum class Picking { START_DATE, START_TIME, END_DATE, END_TIME, REMINDERS, CALENDAR }

@Composable
private fun When(label: String, date: String, time: String?, onDate: () -> Unit, onTime: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        TextMMD(text = label, style = MaterialTheme.typography.labelSmall)
        Row {
            Box(Modifier.clickable(onClick = onDate).padding(vertical = 8.dp).padding(end = 24.dp)) {
                TextMMD(text = date, style = MaterialTheme.typography.bodyLarge)
            }
            if (time != null) {
                Box(Modifier.clickable(onClick = onTime).padding(vertical = 8.dp, horizontal = 8.dp)) {
                    TextMMD(text = time, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
