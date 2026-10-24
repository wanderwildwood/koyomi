package com.wanderwildwood.koyomi.ui

import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.koyomi.data.Buzz
import com.wanderwildwood.koyomi.data.Settings
import com.mudita.mmd.components.time.TimeInputMMD
import com.mudita.mmd.components.time.TimeInputStateMMD
import com.mudita.mmd.components.time.TimePickerSelectionMode
import java.time.LocalTime

/**
 * The hour on a 12-hour clock face and back. Kept apart from the screen so it can be tested:
 * twelve is the awkward hour, since 12 AM is the first hour of the day and 12 PM the thirteenth.
 */
object Clock12 {
    /** 0–23 to the hour a 12-hour clock shows, 1–12. */
    fun faceHour(hour24: Int): Int {
        require(hour24 in 0..23)
        return (hour24 % 12).let { if (it == 0) 12 else it }
    }

    fun isPm(hour24: Int): Boolean = hour24 >= 12

    /** A clock-face hour, 1–12, with its half of the day, to 0–23. */
    fun toHour24(face: Int, pm: Boolean): Int {
        require(face in 1..12)
        return face % 12 + if (pm) 12 else 0
    }

    /**
     * What a typed hour means on the 12-hour clock: 1–12 in the half of the day showing; 0 is
     * that half's twelve (it is also the first digit of "07"); 13–23 is plainly the 24-hour
     * clock, so it is taken as it stands and the half moves to PM with it.
     */
    fun typed(value: Int, pm: Boolean): Int = when (value) {
        0 -> if (pm) 12 else 0
        in 1..12 -> toHour24(value, pm)
        in 13..23 -> value
        else -> throw IllegalArgumentException("hour $value")
    }

    /** The half of the day moved, the clock face kept: 9 AM becomes 9 PM, 12 AM becomes 12 PM. */
    fun withHalf(hour24: Int, pm: Boolean): Int = if (isPm(hour24) == pm) hour24 else (hour24 + 12) % 24
}

/**
 * What [TimeInputMMD] reads and writes, kept here rather than in MMD's own state.
 *
 * MMD 1.0.2's 12-hour mode sets a typed hour as it stands, so "6" with PM showing came out as
 * six in the morning and its toggle quietly flipped to AM. So MMD is always told this is the
 * 24-hour clock — which leaves out its own toggle and lets it take the two digits as typed —
 * and on a 12-hour phone this state hands it the clock-face hour and reads what is typed
 * against the half of the day chosen beside it ([HalfOfDay]). [hour24] is the time itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
class ClockState(hour24: Int, minute: Int, val twelveHour: Boolean) : TimeInputStateMMD {
    var hour24 by mutableIntStateOf(hour24)
        private set

    private var minuteValue by mutableIntStateOf(minute)

    /** Told at each digit typed into the hour or the minute; the dialog points it at the buzz. */
    var onKey: () -> Unit = {}

    override var minute: Int
        get() = minuteValue
        set(value) { onKey(); minuteValue = value }

    override var is24hour: Boolean
        get() = true
        set(_) {}

    override var selection by mutableStateOf(TimePickerSelectionMode.Hour)

    override var isAfternoon: Boolean
        get() = Clock12.isPm(hour24)
        set(pm) { hour24 = Clock12.withHalf(hour24, pm) }

    override var hour: Int
        get() = if (twelveHour) Clock12.faceHour(hour24) else hour24
        set(value) {
            onKey()
            hour24 = if (twelveHour) Clock12.typed(value, isAfternoon) else value
        }

    companion object {
        fun saver(twelveHour: Boolean) = Saver<ClockState, List<Int>>(
            save = { listOf(it.hour24, it.minute) },
            restore = { ClockState(it[0], it[1], twelveHour) },
        )
    }
}

/**
 * The one way a time is set anywhere in the app: Mudita's time input, the hour and the minute
 * typed as the Kompakt's own clock sets them, on whichever clock the phone uses. On the 12-hour
 * clock an AM/PM choice sits under the two numbers.
 *
 * Pressing OK on a time left as it was hands back [initial] itself, seconds and all, so opening
 * an event and saving it changes nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val twelveHour = !DateFormat.is24HourFormat(LocalContext.current)
    val state = rememberSaveable(saver = ClockState.saver(twelveHour)) {
        ClockState(initial.hour, initial.minute, twelveHour)
    }
    val buzz = rememberKeyBuzz()
    state.onKey = buzz
    EInkDialog(onDismiss = onDismiss) {
        TimeInputMMD(state = state, modifier = Modifier.align(Alignment.CenterHorizontally))
        if (twelveHour) {
            HalfOfDay(pm = state.isAfternoon, onPick = { buzz(); state.isAfternoon = it })
            Spacer(Modifier.height(16.dp))
        } else {
            Spacer(Modifier.height(8.dp))
        }
        DialogButtons(
            onCancel = onDismiss,
            onOk = {
                val picked = LocalTime.of(state.hour24, state.minute)
                onPick(if (picked.hour == initial.hour && picked.minute == initial.minute) initial else picked)
                onDismiss()
            },
        )
    }
}

/**
 * AM or PM, two halves of one rimmed bar: the chosen half a black stone with its word in white,
 * as [StoneTabs] marks a tab, the other plain. The words are the phone's own for its language.
 */
@Composable
private fun HalfOfDay(pm: Boolean, onPick: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .border(2.dp, Color.Black, shape)
            .selectableGroup(),
    ) {
        listOf(false, true).forEach { half ->
            val chosen = half == pm
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (chosen) Modifier.background(Color.Black) else Modifier)
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { onPick(half) }),
                contentAlignment = Alignment.Center,
            ) {
                TextMMD(
                    text = Dates.halfOfDay(half),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (chosen) Color.White else Color.Black,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Asks the phone for its keyboard tap at a key press when Settings has the buzz on: through the
 * view rather than the vibrator, so the phone's own touch feedback setting is respected and no
 * permission is needed. The setting is read at each press, so turning it on needs no restart.
 */
@Composable
fun rememberKeyBuzz(): () -> Unit {
    val context = LocalContext.current
    val view = LocalView.current
    val settings = androidx.compose.runtime.remember { Settings(context) }
    return { Buzz.press(settings.vibrate) { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } }
}
