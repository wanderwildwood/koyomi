package com.wanderwildwood.koyomi.ui

import android.content.Context
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.mudita.mmd.components.time.TimeInputMMD
import com.mudita.mmd.components.time.rememberDatePickerMMDState
import com.mudita.mmd.components.time.rememberTimeInputMMDState
import com.wanderwildwood.koyomi.R
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
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
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

/**
 * Mudita's time input: the hour and the minute typed, the way the Kompakt's own clock sets
 * them. Always on the 24-hour clock, whatever the phone uses: in 12-hour mode MMD 1.0.2 sets
 * a typed hour as it stands, so "6" with PM showing came out as 6 in the morning and the
 * toggle quietly flipped to AM. Material's own input adds the twelve; MMD's drops it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimeInputMMDState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    EInkDialog(onDismiss = onDismiss) {
        TimeInputMMD(state = state, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
        DialogButtons(
            onCancel = onDismiss,
            onOk = {
                onPick(LocalTime.of(state.hour, state.minute))
                onDismiss()
            },
        )
    }
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

/** The reminders an event can choose between, ticked in place. */
@Composable
fun RemindersDialog(allDay: Boolean, chosen: List<Int>, onDone: (List<Int>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val options = (if (allDay) ALL_DAY_OPTIONS else TIMED_OPTIONS).let { base ->
        (base + chosen).distinct().sorted()
    }
    var picked by remember { mutableStateOf(chosen.toSet()) }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.edit_reminders), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        options.forEach { m ->
            val on = m in picked
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { picked = if (on) picked - m else picked + m }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckboxMMD(checked = on, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                TextMMD(text = ReminderText.of(context, m, allDay), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        DialogButtons(onCancel = onDismiss, onOk = { onDone(picked.sorted()); onDismiss() })
    }
}

private val TIMED_OPTIONS = listOf(0, 5, 10, 15, 30, 60, 120, 1440, 10080)

/** For an all-day event: 9:00 on the day, 18:00 and 9:00 the day before, 9:00 a week before. */
private val ALL_DAY_OPTIONS = listOf(-540, 360, 900, 9540)

/** A reminder's minutes in words. */
object ReminderText {
    fun of(context: Context, minutes: Int, allDay: Boolean): String {
        val res = context.resources
        if (allDay) {
            // Minutes before the midnight that starts the day, so a negative value is a time
            // on the day itself.
            val days = if (minutes <= 0) 0 else (minutes + 1439) / 1440
            val timeOfDay = days * 1440 - minutes
            val at = Dates.time(context, LocalTime.of((timeOfDay / 60) % 24, timeOfDay % 60))
            return when (days) {
                0 -> res.getString(R.string.reminder_on_day, at)
                1 -> res.getString(R.string.reminder_day_before, at)
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
}
