package com.wanderwildwood.koyomi.ui

import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.checkbox.CheckboxMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.time.DatePickerFormatterMMD
import com.mudita.mmd.components.time.DatePickerMMD
import com.mudita.mmd.components.time.rememberDatePickerMMDState
import com.wanderwildwood.koyomi.R
import com.wanderwildwood.koyomi.data.AllDayReminder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Mudita's own date picker. It steps a month at a time rather than sliding, which is why it
 * is used here rather than a hand-drawn grid.
 *
 * It is shown on the whole screen, not in the house dialog: it is Material's picker
 * underneath and wants 360dp of width, the Kompakt is 366dp wide, and squeezed inside a
 * dialog's rim its last column clipped and its header named the month before the one shown.
 */
@Composable
fun DateDialog(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerMMDState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setDimAmount(0f)
                // Dimming by nothing still counts as dimming: while a dimming window is up,
                // Android turns the status-bar icons white, and on the white bar they vanish.
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(vertical = 16.dp)) {
                DatePickerMMD(
                    state = state,
                    dateFormatter = UtcDateFormatter,
                    title = null,
                    headline = null,
                    showModeToggle = false,
                )
                Spacer(Modifier.weight(1f))
                Box(Modifier.padding(horizontal = 20.dp)) {
                    DialogButtons(
                        onCancel = onDismiss,
                        onOk = {
                            state.selectedDateMillis?.let {
                                onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                            }
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/**
 * MMD's picker hands its formatter the first of the month as UTC milliseconds — its own
 * documentation says so — and its default formatter then reads them in the phone's zone.
 * Anywhere west of Greenwich that is the last evening of the month before, so on this side of
 * the Atlantic the header named August over September's days. This reads them in UTC.
 */
private object UtcDateFormatter : DatePickerFormatterMMD {
    override fun formatMonthYear(monthMillis: Long?, locale: java.util.Locale): String? =
        monthMillis?.let {
            Dates.month(java.time.YearMonth.from(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC)))
        }

    override fun formatDate(dateMillis: Long?, locale: java.util.Locale, forContentDescription: Boolean): String? =
        dateMillis?.let { Dates.long(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
}

@Composable
fun DialogButtons(onCancel: () -> Unit, onOk: () -> Unit, okLabel: String = stringResource(R.string.ok)) {
    Row(Modifier.fillMaxWidth()) {
        OutlinedButtonMMD(onClick = onCancel, modifier = Modifier.weight(1f).height(48.dp)) {
            TextMMD(text = stringResource(R.string.cancel), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        ButtonMMD(onClick = onOk, modifier = Modifier.weight(1f).height(48.dp)) {
            TextMMD(text = okLabel, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The reminders of an event. One with a time chooses among a few, ticked in place; an all-day
 * one has each of its reminders set as a day and a time of day ([AllDayRemindersDialog]).
 * [allDayNew] is what a reminder added to an all-day event starts as.
 */
@Composable
fun RemindersDialog(
    allDay: Boolean,
    chosen: List<Int>,
    onDone: (List<Int>) -> Unit,
    onDismiss: () -> Unit,
    allDayNew: Int = AllDayReminder.DEFAULT,
) {
    if (allDay) {
        AllDayRemindersDialog(chosen, allDayNew, onDone, onDismiss)
        return
    }
    val context = LocalContext.current
    val options = (TIMED_OPTIONS + chosen).distinct().sorted()
    var picked by remember { mutableStateOf(chosen.toSet()) }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.edit_reminders), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        // The choices give way to the buttons: nine rows plus title and buttons are taller than
        // the Kompakt's window, and the buttons were clipped at its bottom edge.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        options.forEach { m ->
            val on = m in picked
            val toggle = { picked = if (on) picked - m else picked + m }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = toggle)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckboxMMD(checked = on, onCheckedChange = { toggle() })
                Spacer(Modifier.width(8.dp))
                TextMMD(text = ReminderText.of(context, m, false), style = MaterialTheme.typography.bodyMedium)
            }
        }
        }
        Spacer(Modifier.height(12.dp))
        DialogButtons(onCancel = onDismiss, onOk = { onDone(picked.sorted()); onDismiss() })
    }
}

/**
 * The reminders of an all-day event, one to a row. Pressing one opens it to change its day and
 * its time, or to take it away; the last row adds another. Nothing is changed until OK, and a
 * reminder that is not opened keeps the very minutes it came with.
 */
@Composable
private fun AllDayRemindersDialog(chosen: List<Int>, new: Int, onDone: (List<Int>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var list by remember { mutableStateOf(chosen.distinct().sorted()) }
    // The place in the list of the reminder being changed; ADDING for one not in it yet.
    var editing by remember { mutableStateOf<Int?>(null) }

    val at = editing
    if (at != null) {
        val current = list.getOrNull(at)
        AllDayReminderDialog(
            title = stringResource(R.string.reminder_one),
            // A new one starts as new events' own, or the nearest plain one not yet in the list.
            initial = current ?: (listOf(new) + ALL_DAY_PLAIN).firstOrNull { it !in list } ?: new,
            noneLabel = if (current == null) null else stringResource(R.string.reminder_none),
            onDone = { minutes ->
                val rest = if (current == null) list else list - current
                list = (if (minutes == null) rest else rest + minutes).distinct().sorted()
            },
            onDismiss = { editing = null },
        )
        return
    }

    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.edit_reminders), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            list.forEachIndexed { i, m ->
                ChoiceRow(ReminderText.of(context, m, true)) { editing = i }
            }
            ChoiceRow(stringResource(R.string.reminder_add), bold = true) { editing = ADDING }
        }
        Spacer(Modifier.height(12.dp))
        DialogButtons(onCancel = onDismiss, onOk = { onDone(list); onDismiss() })
    }
}

private const val ADDING = -1

/** 9:00 on the day, 18:00 and 9:00 the day before, 9:00 a week before. */
private val ALL_DAY_PLAIN = listOf(-540, 900, 360, 9540)

/**
 * One reminder of an all-day event, or what new all-day events are given: which day, and at
 * what time that day. The time is asked for in a dialog of its own, the one every other time in
 * the app is set in, so it follows the phone's 12 or 24-hour clock.
 *
 * [initial] is the store's minutes ([AllDayReminder]), null for no reminder. [noneLabel] names
 * the choice of no reminder; with none given that choice is not offered. A reminder on a day
 * that is not among the usual ones (three days before, say, from a synced event) has its own
 * day offered beside them. OK with nothing touched hands back [initial] as it was.
 */
@Composable
fun AllDayReminderDialog(
    title: String,
    initial: Int?,
    noneLabel: String?,
    onDone: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val first = remember { AllDayReminder.split(initial ?: AllDayReminder.DEFAULT) }
    var none by rememberSaveable { mutableStateOf(initial == null && noneLabel != null) }
    var days by rememberSaveable { mutableStateOf(first.daysBefore) }
    var minute by rememberSaveable { mutableStateOf(first.minuteOfDay) }
    var settingTime by rememberSaveable { mutableStateOf(false) }

    if (settingTime) {
        TimeDialog(
            initial = LocalTime.of(minute / 60, minute % 60),
            onPick = { minute = it.hour * 60 + it.minute },
            onDismiss = { settingTime = false },
        )
        return
    }

    val dayChoices = (AllDayReminder.DAY_CHOICES + first.daysBefore).distinct().sorted()
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            if (noneLabel != null) ChoiceRow(noneLabel, bold = none) { none = true }
            dayChoices.forEach { d ->
                ChoiceRow(ReminderText.day(context, d), bold = !none && d == days) {
                    none = false
                    days = d
                }
            }
            if (!none) {
                SettingRow(
                    stringResource(R.string.reminder_time),
                    Dates.time(context, LocalTime.of(minute / 60, minute % 60)),
                ) { settingTime = true }
            }
        }
        Spacer(Modifier.height(12.dp))
        DialogButtons(
            onCancel = onDismiss,
            onOk = {
                onDone(if (none) null else AllDayReminder.minutes(days, minute))
                onDismiss()
            },
        )
    }
}

private val TIMED_OPTIONS = listOf(0, 5, 10, 15, 30, 60, 120, 1440, 10080)

/** A reminder's minutes in words. */
object ReminderText {
    fun of(context: Context, minutes: Int, allDay: Boolean): String {
        val res = context.resources
        if (allDay) {
            // Minutes before the midnight that starts the day, read as a day and a time of day.
            val (days, minute) = AllDayReminder.split(minutes)
            val at = Dates.time(context, LocalTime.of(minute / 60, minute % 60))
            return when {
                days == 0 -> res.getString(R.string.reminder_on_day, at)
                days == 1 -> res.getString(R.string.reminder_day_before, at)
                days < 0 -> res.getQuantityString(R.plurals.reminder_days_after_at, -days, -days, at)
                days % 7 == 0 -> res.getQuantityString(R.plurals.reminder_weeks_before_at, days / 7, days / 7, at)
                else -> res.getQuantityString(R.plurals.reminder_days_before_at, days, days, at)
            }
        }
        return when {
            minutes <= 0 -> res.getString(R.string.reminder_at_start)
            minutes % 10080 == 0 -> res.getQuantityString(R.plurals.reminder_weeks, minutes / 10080, minutes / 10080)
            minutes % 1440 == 0 -> res.getQuantityString(R.plurals.reminder_days, minutes / 1440, minutes / 1440)
            minutes % 60 == 0 -> res.getQuantityString(R.plurals.reminder_hours, minutes / 60, minutes / 60)
            else -> res.getQuantityString(R.plurals.reminder_minutes, minutes, minutes)
        }
    }

    /** The day of an all-day event's reminder on its own: 0 is the day, 1 the day before. */
    fun day(context: Context, daysBefore: Int): String {
        val res = context.resources
        return when {
            daysBefore == 0 -> res.getString(R.string.reminder_day_itself)
            daysBefore == 1 -> res.getString(R.string.reminder_day_before_plain)
            daysBefore < 0 -> res.getQuantityString(R.plurals.reminder_days_after, -daysBefore, -daysBefore)
            daysBefore % 7 == 0 -> res.getQuantityString(R.plurals.reminder_weeks, daysBefore / 7, daysBefore / 7)
            else -> res.getQuantityString(R.plurals.reminder_days, daysBefore, daysBefore)
        }
    }
}
