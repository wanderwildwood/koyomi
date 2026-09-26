package com.wanderwildwood.koyomi.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.koyomi.AppModel
import com.wanderwildwood.koyomi.R
import com.wanderwildwood.koyomi.Screen
import com.wanderwildwood.koyomi.data.Occurrence
import com.wanderwildwood.koyomi.data.View
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields

/**
 * The four views under one top bar: a row naming them, the view itself, and a foot of
 * previous, today, next and add. A swipe across moves one page and stops.
 */
@Composable
fun ViewsScreen(model: AppModel) {
    @Suppress("UNUSED_VARIABLE") val v = model.settingsVersion
    val (from, to) = model.range()
    val title = when (model.view) {
        View.MONTH -> Dates.month(YearMonth.from(model.anchor))
        View.WEEK -> "${Dates.short(from)} – ${Dates.short(to)}"
        View.DAY -> Dates.dayTitle(model.anchor)
        View.AGENDA -> stringResource(R.string.agenda_from, Dates.medium(model.anchor))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    BarButton(Icons.Search, stringResource(R.string.cd_search)) { model.push(Screen.Search) }
                    BarButton(Icons.Settings, stringResource(R.string.cd_settings)) { model.push(Screen.Settings) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ViewTabs(model.view, model::show)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .swipePages(model.anchor to model.view, { model.step(false) }, { model.step(true) }),
            ) {
                when (model.view) {
                    View.MONTH -> MonthView(model)
                    View.WEEK -> Timeline(model, (0..6).map { from.plusDays(it.toLong()) })
                    View.DAY -> Timeline(model, listOf(model.anchor))
                    View.AGENDA -> AgendaView(model)
                }
            }
            HorizontalDividerMMD(thickness = 1.dp)
            Foot(model)
        }
    }
}

@Composable
private fun ViewTabs(current: View, onPick: (View) -> Unit) {
    val names = listOf(
        View.MONTH to R.string.view_month,
        View.WEEK to R.string.view_week,
        View.DAY to R.string.view_day,
        View.AGENDA to R.string.view_agenda,
    )
    Row(Modifier.fillMaxWidth().height(44.dp)) {
        names.forEach { (v, label) ->
            val chosen = v == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onPick(v) }
                    .drawBehind {
                        // State by the border, not by colour: a rule under the chosen view.
                        val w = if (chosen) 3.dp.toPx() else 1.dp.toPx()
                        drawRect(Color.Black, topLeft = Offset(0f, size.height - w), size = size.copy(height = w))
                    },
                contentAlignment = Alignment.Center,
            ) {
                TextMMD(
                    text = stringResource(label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun Foot(model: AppModel) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(Icons.ChevronLeft, stringResource(R.string.cd_previous)) { model.step(false) }
        Box(
            Modifier.height(48.dp).clickable { model.today() }.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            TextMMD(text = stringResource(R.string.today), style = MaterialTheme.typography.bodyMedium)
        }
        BarButton(Icons.ChevronRight, stringResource(R.string.cd_next)) { model.step(true) }
        Spacer(Modifier.weight(1f))
        BarButton(Icons.Add, stringResource(R.string.cd_add)) { model.newEvent() }
    }
}

// ----------------------------------------------------------------------------------------
// Month

@Composable
private fun MonthView(model: AppModel) {
    val (first, _) = model.range()
    val month = YearMonth.from(model.anchor)
    val showWeeks = model.settings.weekNumbers
    val today = LocalDate.now()
    // Only the weeks the month needs: most months fit in five, and a sixth row costs every
    // day a line. The days either side that share those weeks are shown with their events,
    // as Etar does, so the end of one month still says what the start of the next holds.
    val weeks = ((ChronoUnit.DAYS.between(first, month.atDay(1)) + month.lengthOfMonth() + 6) / 7).toInt()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
            if (showWeeks) Spacer(Modifier.width(WEEK_COLUMN))
            for (i in 0..6) {
                TextMMD(
                    text = Dates.weekday(first.plusDays(i.toLong())),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        for (week in 0 until weeks) {
            val weekStart = first.plusWeeks(week.toLong())
            DashedRule()
            Row(Modifier.fillMaxWidth().weight(1f)) {
                if (showWeeks) {
                    Box(Modifier.width(WEEK_COLUMN).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                        TextMMD(
                            text = weekStart.plusDays(3).get(WeekFields.ISO.weekOfWeekBasedYear()).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                MonthWeek(
                    weekStart = weekStart,
                    month = month,
                    today = today,
                    occurrences = model.occurrences,
                    onOpen = { model.openDay(it) },
                    onAdd = { model.newEvent(it) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

private val WEEK_COLUMN = 28.dp

/** One line of a day cell: 14sp type, the house floor, and room for a bar around it. */
private val LINE = 18.dp

/** The day's number at the top of its cell. */
private val NUMBER_ROW = 24.dp

/** An all-day or multi-day event laid across a week: first and last column, and its lane. */
internal data class Bar(val occurrence: Occurrence, val from: Int, val to: Int, val lane: Int)

/**
 * Lays a week's all-day and multi-day events out as bars, the way Etar's month does: each in
 * the first lane free across every day it covers, earliest first and longest first, so a
 * bar never changes lane partway along and two never overlap.
 */
internal fun layBars(spanning: List<Occurrence>, weekStart: LocalDate): List<Bar> {
    val weekEnd = weekStart.plusDays(6)
    val laneEnds = mutableListOf<Int>() // the last column each lane is taken to
    return spanning
        .sortedWith(compareBy({ maxOf(it.firstDay, weekStart) }, { -ChronoUnit.DAYS.between(it.firstDay, it.lastDay) }, { it.title }))
        .map { o ->
            val from = ChronoUnit.DAYS.between(weekStart, maxOf(o.firstDay, weekStart)).toInt()
            val to = ChronoUnit.DAYS.between(weekStart, minOf(o.lastDay, weekEnd)).toInt()
            var lane = laneEnds.indexOfFirst { it < from }
            if (lane == -1) {
                laneEnds += to
                lane = laneEnds.lastIndex
            } else {
                laneEnds[lane] = to
            }
            Bar(o, from, to, lane)
        }
}

/**
 * A week of the month: each day's number, the week's all-day and multi-day events as black
 * bars running across the days they cover, and under them each day's own events by name,
 * one clipped line each, with "+N" for whatever does not fit.
 */
@Composable
private fun MonthWeek(
    weekStart: LocalDate,
    month: YearMonth,
    today: LocalDate,
    occurrences: List<Occurrence>,
    onOpen: (LocalDate) -> Unit,
    onAdd: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    val days = (0..6).map { weekStart.plusDays(it.toLong()) }
    val touching = remember(occurrences, weekStart) { occurrences.filter { o -> days.any { o.touches(it) } } }
    val bars = remember(touching, weekStart) {
        layBars(touching.filter { it.allDay || it.firstDay != it.lastDay }, weekStart)
    }
    val lineStyle = MaterialTheme.typography.labelSmall.copy(lineHeight = 15.sp)

    BoxWithConstraints(modifier) {
        val cell = maxWidth / 7
        val room = ((maxHeight - NUMBER_ROW) / LINE).toInt().coerceAtLeast(0)
        val laneCount = (bars.maxOfOrNull { it.lane } ?: -1) + 1
        // Bars keep their lane all the way across. If the week has more lanes than a day has
        // lines, the last line is kept back for "+N".
        val barLimit = if (laneCount > room) (room - 1).coerceAtLeast(0) else laneCount

        Row(Modifier.fillMaxSize()) {
            days.forEachIndexed { col, date ->
                val inMonth = YearMonth.from(date) == month
                val timed = touching.filter { !it.allDay && it.firstDay == it.lastDay && it.firstDay == date }
                    .sortedBy { it.start }
                val barLanes = bars.filter { col in it.from..it.to }.map { it.lane }
                val hiddenBars = barLanes.count { it >= barLimit }
                // A day's own events take whatever lines its bars leave free, above, between
                // or below them, as Etar packs them — not only the lines under the lowest bar.
                val free = (0 until room).filter { slot -> slot !in barLanes || slot >= barLimit }
                val overflow = timed.size + hiddenBars > free.size
                val shown = if (overflow) (free.size - 1).coerceAtLeast(0) else timed.size
                val hidden = timed.size - shown + hiddenBars
                Box(
                    modifier = Modifier
                        .width(cell)
                        .fillMaxHeight()
                        .drawBehind {
                            // A hairline between days, so a clipped title reads as ending at
                            // its own day's edge.
                            if (col < 6) {
                                drawLine(Color.Black, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = 0.5.dp.toPx())
                            }
                        }
                        .pointerInput(date) {
                            detectTapGestures(onTap = { onOpen(date) }, onLongPress = { onAdd(date) })
                        }
                        .padding(horizontal = 2.dp),
                ) {
                    Box(Modifier.height(NUMBER_ROW), contentAlignment = Alignment.CenterStart) {
                        Box(
                            modifier = Modifier
                                .background(if (date == today) Color.Black else Color.White, RoundedCornerShape(5.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            TextMMD(
                                text = date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                // The month's own days in bold; the neighbours' plain, since
                                // there is no grey to set them back with.
                                fontWeight = if (inMonth || date == today) FontWeight.Bold else FontWeight.Normal,
                                color = if (date == today) Color.White else Color.Black,
                            )
                        }
                    }
                    timed.take(shown).forEachIndexed { i, o ->
                        TextMMD(
                            text = o.title.ifBlank { stringResource(R.string.untitled) },
                            style = lineStyle,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.offset(y = NUMBER_ROW + LINE * free[i]).height(LINE),
                        )
                    }
                    if (hidden > 0 && free.isNotEmpty()) {
                        TextMMD(
                            text = "+$hidden",
                            style = lineStyle,
                            modifier = Modifier.offset(y = NUMBER_ROW + LINE * free[shown]).height(LINE),
                        )
                    }
                }
            }
        }

        // The bars, over the cells. Taps fall through them to the day beneath.
        bars.filter { it.lane < barLimit }.forEach { bar ->
            Box(
                modifier = Modifier
                    .offset(x = cell * bar.from + 1.dp, y = NUMBER_ROW + LINE * bar.lane + 1.dp)
                    .width(cell * (bar.to - bar.from + 1) - 2.dp)
                    .height(LINE - 2.dp)
                    .background(Color.Black, RoundedCornerShape(3.dp))
                    .padding(horizontal = 3.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                TextMMD(
                    text = bar.occurrence.title.ifBlank { stringResource(R.string.untitled) },
                    style = lineStyle,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

@Composable
private fun DashedRule() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .drawBehind {
                drawLine(
                    Color.Black,
                    Offset(0f, 0f),
                    Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)),
                )
            },
    )
}

// ----------------------------------------------------------------------------------------
// Week and day

/**
 * A timeline of one day or seven. Twelve hours at a time — midnight to noon, eight to eight,
 * noon to midnight — changed by a swipe up or down, because an hour tall enough to read an
 * event in does not leave room for twenty-four of them.
 */
@Composable
private fun Timeline(model: AppModel, days: List<LocalDate>) {
    val context = LocalContext.current
    val week = days.size > 1
    val today = LocalDate.now()
    val startHour = listOf(0, 8, 12)[model.hours]
    val rangeStart = LocalTime.of(startHour, 0)

    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = LocalDateTime.now()
        }
    }

    val open = { o: Occurrence -> model.push(Screen.Event(o.eventId, o.begin, o.end)) }

    Column(Modifier.fillMaxSize()) {
        if (week) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Spacer(Modifier.width(HOUR_COLUMN))
                days.forEach { d ->
                    Column(
                        modifier = Modifier.weight(1f).clickable { model.openDay(d) }.padding(vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        TextMMD(text = Dates.weekdayNarrow(d), style = MaterialTheme.typography.labelSmall)
                        Box(
                            Modifier.size(28.dp).background(if (d == today) Color.Black else Color.White, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            TextMMD(
                                text = d.dayOfMonth.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (d == today) Color.White else Color.Black,
                            )
                        }
                    }
                }
            }
        }

        AllDayStrip(model.occurrences.filter { it.allDay || it.firstDay != it.lastDay }, days, week, open)
        HorizontalDividerMMD(thickness = 1.dp)

        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .swipeVertical(model.hours, { model.stepHours(false) }, { model.stepHours(true) }),
        ) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val hourHeight = maxHeight / 12
                // Hour lines and labels.
                for (i in 0 until 12) {
                    Row(Modifier.offset(y = hourHeight * i).fillMaxWidth().height(hourHeight)) {
                        TextMMD(
                            text = Dates.hour(context, startHour + i),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(HOUR_COLUMN).padding(start = 4.dp),
                        )
                        DashedRule()
                    }
                }
                Row(Modifier.fillMaxSize().padding(start = HOUR_COLUMN)) {
                    days.forEach { d ->
                        DayColumn(
                            date = d,
                            occurrences = model.occurrences.filter { !it.allDay && it.firstDay == it.lastDay && it.firstDay == d },
                            rangeStart = rangeStart,
                            hourHeight = hourHeight,
                            now = now,
                            showTimes = !week,
                            onOpen = open,
                            onAdd = { t -> model.newEvent(d, t) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
            HoursRail(model.hours, model::showHours)
        }
    }
}

private val HOUR_COLUMN = 44.dp

@Composable
private fun AllDayStrip(all: List<Occurrence>, days: List<LocalDate>, week: Boolean, onOpen: (Occurrence) -> Unit) {
    val touching = all.filter { o -> days.any { o.touches(it) } }
    if (touching.isEmpty()) return
    if (!week) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            touching.take(3).forEach { o -> Chip(o.title, Modifier.fillMaxWidth().padding(vertical = 2.dp)) { onOpen(o) } }
            if (touching.size > 3) {
                TextMMD(
                    text = stringResource(R.string.more_all_day, touching.size - 3),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Spacer(Modifier.width(HOUR_COLUMN))
        days.forEach { d ->
            val here = touching.filter { it.touches(d) }
            Column(Modifier.weight(1f).padding(horizontal = 1.dp)) {
                here.take(2).forEach { o -> Chip(o.title, Modifier.fillMaxWidth().padding(vertical = 1.dp)) { onOpen(o) } }
                if (here.size > 2) {
                    TextMMD(text = "+${here.size - 2}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun Chip(title: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .border(BorderStroke(1.dp, Color.Black), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        TextMMD(
            text = title.ifBlank { stringResource(R.string.untitled) },
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun DayColumn(
    date: LocalDate,
    occurrences: List<Occurrence>,
    rangeStart: LocalTime,
    hourHeight: Dp,
    now: LocalDateTime,
    showTimes: Boolean,
    onOpen: (Occurrence) -> Unit,
    onAdd: (LocalTime) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val startMin = rangeStart.toSecondOfDay() / 60
    val endMin = startMin + 12 * 60
    val laid = remember(occurrences) { layOut(occurrences) }

    BoxWithConstraints(
        modifier = modifier
            .drawBehind {
                drawLine(Color.Black, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 0.5.dp.toPx())
            }
            .pointerInput(date, rangeStart) {
                detectTapGestures(onLongPress = { at ->
                    val minutes = startMin + (at.y / hourHeight.toPx() * 60).toInt()
                    val snapped = (minutes / 30 * 30).coerceIn(0, 23 * 60 + 30)
                    onAdd(LocalTime.of(snapped / 60, snapped % 60))
                })
            },
    ) {
        val width = maxWidth
        laid.forEach { (o, column, columns) ->
            val s = minutesOf(o.start, date)
            val e = minutesOf(o.finish, date).let { if (it <= s) s + 30 else it }
            if (e <= startMin || s >= endMin) return@forEach
            val top = hourHeight * ((maxOf(s, startMin) - startMin) / 60f)
            val height = hourHeight * ((minOf(e, endMin) - maxOf(s, startMin)) / 60f)
            val w = width / columns
            Box(
                modifier = Modifier
                    .offset(x = w * column, y = top)
                    .width(w)
                    .height(maxOf(height, 22.dp))
                    .padding(1.dp)
                    .background(Color.White, RoundedCornerShape(4.dp))
                    .border(1.dp, Color.Black, RoundedCornerShape(4.dp))
                    .clickable { onOpen(o) }
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            ) {
                Column {
                    // Too narrow for a word, a title is one clipped line: wrapped, it came out a
                    // letter to a line and read as nothing.
                    val narrow = w < 44.dp
                    TextMMD(
                        text = o.title.ifBlank { stringResource(R.string.untitled) },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = if (showTimes || narrow) 1 else 3,
                        softWrap = !narrow,
                        overflow = TextOverflow.Clip,
                    )
                    if (showTimes && height > 40.dp) {
                        TextMMD(
                            text = "${Dates.time(context, o.start.toLocalTime())} – ${Dates.time(context, o.finish.toLocalTime())}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                    if (showTimes && height > 62.dp && o.location != null) {
                        TextMMD(text = o.location, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
        if (date == now.toLocalDate()) {
            val m = now.toLocalTime().toSecondOfDay() / 60
            if (m in startMin until endMin) {
                Box(
                    Modifier
                        .offset(y = hourHeight * ((m - startMin) / 60f) - 1.dp)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color.Black),
                )
            }
        }
    }
}

/** Minutes after this date's midnight; before it is 0, after it is 24 hours. */
private fun minutesOf(t: LocalDateTime, date: LocalDate): Int = when {
    t.toLocalDate().isBefore(date) -> 0
    t.toLocalDate().isAfter(date) -> 24 * 60
    else -> t.toLocalTime().toSecondOfDay() / 60
}

/**
 * Side by side where events overlap: each cluster of overlapping events is split into as many
 * columns as it needs, and an event takes the first column free at its start.
 */
internal fun layOut(events: List<Occurrence>): List<Triple<Occurrence, Int, Int>> {
    val sorted = events.sortedWith(compareBy({ it.start }, { -ChronoUnit.MINUTES.between(it.start, it.finish) }))
    val out = mutableListOf<Triple<Occurrence, Int, Int>>()
    var cluster = mutableListOf<Pair<Occurrence, Int>>()
    var clusterEnd = LocalDateTime.MIN
    val columnEnds = mutableListOf<LocalDateTime>()

    fun flush() {
        val n = (cluster.maxOfOrNull { it.second } ?: -1) + 1
        cluster.forEach { (o, c) -> out += Triple(o, c, n) }
        cluster = mutableListOf()
        columnEnds.clear()
    }

    for (o in sorted) {
        val end = if (o.finish.isAfter(o.start)) o.finish else o.start.plusMinutes(30)
        if (cluster.isNotEmpty() && !o.start.isBefore(clusterEnd)) flush()
        var col = columnEnds.indexOfFirst { !o.start.isBefore(it) }
        if (col == -1) {
            columnEnds += end
            col = columnEnds.lastIndex
        } else {
            columnEnds[col] = end
        }
        cluster += o to col
        clusterEnd = if (cluster.size == 1) end else maxOf(clusterEnd, end)
    }
    flush()
    return out
}

/** Three marks down the edge, one per third of the day; the filled one is showing. */
@Composable
private fun HoursRail(page: Int, onPick: (Int) -> Unit) {
    Column(
        modifier = Modifier.width(20.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (i in 0..2) {
            Box(
                Modifier
                    .padding(vertical = 4.dp)
                    .size(width = 8.dp, height = 56.dp)
                    .background(if (i == page) Color.Black else Color.White, RoundedCornerShape(4.dp))
                    .border(1.dp, Color.Black, RoundedCornerShape(4.dp))
                    .clickable { onPick(i) },
            )
        }
    }
}

// ----------------------------------------------------------------------------------------
// Agenda

@Composable
private fun AgendaView(model: AppModel) {
    val context = LocalContext.current
    val byDay = remember(model.occurrences, model.anchor) {
        val (from, to) = model.range()
        val map = sortedMapOf<LocalDate, MutableList<Occurrence>>()
        for (o in model.occurrences) {
            var d = maxOf(o.firstDay, from)
            val last = minOf(o.lastDay, to)
            while (!d.isAfter(last)) {
                map.getOrPut(d) { mutableListOf() } += o
                d = d.plusDays(1)
            }
        }
        map
    }
    if (byDay.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp)) {
            TextMMD(text = stringResource(R.string.agenda_empty), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumnMMD(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        byDay.forEach { (day, list) ->
            item(key = "h$day") {
                Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp)) {
                    TextMMD(
                        text = Dates.dayTitle(day),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            list.sortedWith(compareBy({ !it.allDay }, { it.start })).forEach { o ->
                item(key = "o$day${o.eventId}${o.begin}") {
                    AgendaRow(
                        time = when {
                            o.allDay || o.firstDay != o.lastDay && o.firstDay != day -> stringResource(R.string.all_day)
                            else -> Dates.time(context, o.start.toLocalTime())
                        },
                        occurrence = o,
                        onClick = { model.push(Screen.Event(o.eventId, o.begin, o.end)) },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun AgendaRow(time: String, occurrence: Occurrence, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
    ) {
        TextMMD(
            text = time,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(84.dp).padding(top = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            TextMMD(
                text = occurrence.title.ifBlank { stringResource(R.string.untitled) },
                style = MaterialTheme.typography.bodyMedium,
            )
            occurrence.location?.let {
                TextMMD(text = it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
