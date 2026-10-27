package com.wanderwildwood.koyomi.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.koyomi.R
import com.wanderwildwood.koyomi.data.AllDayKind
import com.wanderwildwood.koyomi.data.AllDayMode
import com.wanderwildwood.koyomi.data.AllDaySetting
import java.time.LocalTime

/**
 * What one calendar does about its all-day events: nothing, remind the evening before, the
 * morning of, or both, and at what time. The times are asked for in a dialog of their own, the
 * one every other time in the app is set in.
 */
@Composable
fun AllDayDialog(calendarName: String, initial: AllDaySetting, onSave: (AllDaySetting) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(initial.mode) }
    var evening by rememberSaveable { mutableStateOf(initial.eveningMinute) }
    var morning by rememberSaveable { mutableStateOf(initial.morningMinute) }
    var setting by rememberSaveable { mutableStateOf<AllDayKind?>(null) }

    val kind = setting
    if (kind != null) {
        val at = if (kind == AllDayKind.EVENING) evening else morning
        TimeDialog(
            initial = LocalTime.of(at / 60, at % 60),
            onPick = {
                val minute = it.hour * 60 + it.minute
                if (kind == AllDayKind.EVENING) evening = minute else morning = minute
            },
            onDismiss = { setting = null },
        )
        return
    }

    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = stringResource(R.string.settings_all_day), style = MaterialTheme.typography.bodyLarge)
        TextMMD(text = calendarName, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(6.dp))
        listOf(
            AllDayMode.OFF to R.string.all_day_off,
            AllDayMode.EVENING to R.string.all_day_evening,
            AllDayMode.MORNING to R.string.all_day_morning,
            AllDayMode.BOTH to R.string.all_day_both,
        ).forEach { (m, label) ->
            ChoiceRow(stringResource(label), bold = m == mode) { mode = m }
        }
        if (mode == AllDayMode.EVENING || mode == AllDayMode.BOTH) {
            SettingRow(
                stringResource(R.string.all_day_evening_at, Dates.time(context, LocalTime.of(evening / 60, evening % 60))),
                null,
            ) { setting = AllDayKind.EVENING }
        }
        if (mode == AllDayMode.MORNING || mode == AllDayMode.BOTH) {
            SettingRow(
                stringResource(R.string.all_day_morning_at, Dates.time(context, LocalTime.of(morning / 60, morning % 60))),
                null,
            ) { setting = AllDayKind.MORNING }
        }
        if (mode != AllDayMode.OFF) {
            TextMMD(text = stringResource(R.string.all_day_note), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(12.dp))
        DialogButtons(
            onCancel = onDismiss,
            onOk = { onSave(AllDaySetting(mode, evening, morning)); onDismiss() },
        )
    }
}
