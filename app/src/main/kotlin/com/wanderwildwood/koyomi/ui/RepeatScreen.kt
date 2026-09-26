package com.wanderwildwood.koyomi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.radio_button.RadioButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.koyomi.AppModel
import com.wanderwildwood.koyomi.R
import com.wanderwildwood.koyomi.repeat.RepeatRule
import com.wanderwildwood.koyomi.repeat.RepeatRule.End
import com.wanderwildwood.koyomi.repeat.RepeatRule.Freq
import com.wanderwildwood.koyomi.repeat.RepeatRule.Monthly
import com.wanderwildwood.koyomi.repeat.RepeatText
import java.time.format.TextStyle
import java.util.Locale

/**
 * How an event repeats: Etar's repeat picker, laid out as one list. The words at the top say
 * the rule back as it stands, so what is being set is never left to be inferred from the
 * controls.
 */
@Composable
fun RepeatScreen(model: AppModel) {
    val context = LocalContext.current
    val d = model.draft
    val start = d.startDate
    var repeats by remember { mutableStateOf(d.rrule != null) }
    var rule by remember {
        mutableStateOf(d.rrule?.let { RepeatRule.from(it, start, d.allDay) } ?: RepeatRule.fresh(Freq.WEEKLY, start))
    }
    var pickingUntil by remember { mutableStateOf(false) }

    fun done() {
        model.draft = model.draft.copy(
            rrule = if (repeats) rule.toRrule(start, d.allDay, model.settings.weekStart) else null,
        )
        model.pop()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.event_repeats)) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.cd_discard)) { model.pop() } },
                actions = {
                    Box(Modifier.height(48.dp).clickable { done() }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        TextMMD(text = stringResource(R.string.done), style = MaterialTheme.typography.bodyMedium)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumnMMD(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            item {
                Spacer(Modifier.height(12.dp))
                TextMMD(
                    text = if (repeats) RepeatText.of(context.resources, rule, start) else stringResource(R.string.repeat_never),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDividerMMD(thickness = 1.dp)
            }
            item { Radio(stringResource(R.string.repeat_never), !repeats) { repeats = false } }
            listOf(
                Freq.DAILY to R.string.repeat_pick_daily,
                Freq.WEEKLY to R.string.repeat_pick_weekly,
                Freq.MONTHLY to R.string.repeat_pick_monthly,
                Freq.YEARLY to R.string.repeat_pick_yearly,
            ).forEach { (f, label) ->
                item {
                    Radio(stringResource(label), repeats && rule.freq == f) {
                        repeats = true
                        if (rule.freq != f) rule = rule.copy(freq = f)
                    }
                }
            }

            if (repeats) {
                item {
                    Section(stringResource(R.string.repeat_how_often))
                    val unit = when (rule.freq) {
                        Freq.DAILY -> R.plurals.unit_days
                        Freq.WEEKLY -> R.plurals.unit_weeks
                        Freq.MONTHLY -> R.plurals.unit_months
                        Freq.YEARLY -> R.plurals.unit_years
                    }
                    Stepper(
                        text = pluralStringResource(unit, rule.interval, rule.interval),
                        onLess = { rule = rule.copy(interval = (rule.interval - 1).coerceAtLeast(1)) },
                        onMore = { rule = rule.copy(interval = (rule.interval + 1).coerceAtMost(99)) },
                    )
                }

                if (rule.freq == Freq.WEEKLY) {
                    item {
                        Section(stringResource(R.string.repeat_on_days))
                        val first = model.settings.weekStart
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            (0L..6L).map { first.plus(it) }.forEach { day ->
                                val on = day in rule.weekdays
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(if (on) Color.Black else Color.White, RoundedCornerShape(6.dp))
                                        .border(1.dp, Color.Black, RoundedCornerShape(6.dp))
                                        .clickable {
                                            // Never none: a weekly rule with no day is not a rule.
                                            val next = if (on) rule.weekdays - day else rule.weekdays + day
                                            if (next.isNotEmpty()) rule = rule.copy(weekdays = next)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    TextMMD(
                                        text = day.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (on) Color.White else Color.Black,
                                    )
                                }
                            }
                        }
                    }
                }

                if (rule.freq == Freq.MONTHLY) {
                    item {
                        Section(stringResource(R.string.repeat_which_day))
                        Radio(stringResource(R.string.repeat_on_day_long, start.dayOfMonth), rule.monthly == Monthly.BY_DATE) {
                            rule = rule.copy(monthly = Monthly.BY_DATE)
                        }
                        val nth = RepeatRule.nthOf(start)
                        // A fifth weekday does not come every month, so it is offered only as "last".
                        if (nth <= 4) {
                            Radio(
                                stringResource(R.string.repeat_on, RepeatText.nthWeekday(context.resources, nth, start.dayOfWeek)),
                                rule.monthly == Monthly.BY_WEEKDAY && rule.nth == nth,
                            ) { rule = rule.copy(monthly = Monthly.BY_WEEKDAY, nth = nth) }
                        }
                        if (RepeatRule.isLastOfMonth(start)) {
                            Radio(
                                stringResource(R.string.repeat_on, RepeatText.nthWeekday(context.resources, -1, start.dayOfWeek)),
                                rule.monthly == Monthly.BY_WEEKDAY && rule.nth == -1,
                            ) { rule = rule.copy(monthly = Monthly.BY_WEEKDAY, nth = -1) }
                        }
                    }
                }

                item {
                    Section(stringResource(R.string.repeat_ends))
                    Radio(stringResource(R.string.repeat_end_never), rule.end == End.NEVER) { rule = rule.copy(end = End.NEVER) }
                    Radio(
                        stringResource(R.string.repeat_end_on, Dates.long(rule.until ?: start)),
                        rule.end == End.DATE,
                    ) {
                        rule = rule.copy(end = End.DATE)
                        pickingUntil = true
                    }
                    Radio(stringResource(R.string.repeat_end_after), rule.end == End.COUNT) { rule = rule.copy(end = End.COUNT) }
                    if (rule.end == End.COUNT) {
                        Stepper(
                            text = pluralStringResource(R.plurals.unit_times, rule.count, rule.count),
                            onLess = { rule = rule.copy(count = (rule.count - 1).coerceAtLeast(1)) },
                            onMore = { rule = rule.copy(count = (rule.count + 1).coerceAtMost(999)) },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (pickingUntil) {
        DateDialog(
            initial = rule.until ?: start,
            onPick = { rule = rule.copy(until = if (it.isBefore(start)) start else it, end = End.DATE) },
            onDismiss = { pickingUntil = false },
        )
    }
}

@Composable
private fun Section(title: String) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp)) {
        TextMMD(text = title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Radio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButtonMMD(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        TextMMD(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A number set by presses, one at a time: − 3 weeks +. */
@Composable
private fun Stepper(text: String, onLess: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", onLess)
        TextMMD(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        StepButton("+", onMore)
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .border(1.dp, Color.Black, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        TextMMD(text = label, style = MaterialTheme.typography.titleLarge)
    }
}
