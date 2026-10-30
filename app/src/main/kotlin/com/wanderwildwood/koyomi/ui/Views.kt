package com.wanderwildwood.koyomi.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import android.content.res.Configuration
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
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
    val sideways = onItsSide()
    val title = when (model.view) {
        View.MONTH -> Dates.month(YearMonth.from(model.anchor))
        View.WEEK -> "${Dates.short(from)} – ${Dates.short(to)}"
        View.DAY -> if (sideways) Dates.medium(model.anchor) else Dates.dayTitle(model.anchor)
        View.AGENDA -> stringResource(R.string.agenda_from, Dates.medium(model.anchor))
    }

    // A swipe down at the top of any view asks DAVx5 and its kind to sync, as Settings' row does;
    // the line above the view says so in words for a few seconds, and what arrives shows when
    // the calendar store changes.
    var asked by remember { mutableStateOf(0) }
    LaunchedEffect(asked) { if (asked > 0) { delay(3000); asked = 0 } }
    val pull = {
        com.wanderwildwood.koyomi.data.CalendarSync.requestNow()
        asked++
        Unit
    }

    val body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        if (asked > 0) {
            TextMMD(
                text = stringResource(R.string.agenda_sync_asked),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (sideways) 2.dp else 6.dp),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .swipePages(model.anchor to model.view, { model.step(false) }, { model.step(true) }),
        ) {
            when (model.view) {
                View.MONTH -> MonthView(model, pull)
                View.WEEK -> Timeline(model, (0..6).map { from.plusDays(it.toLong()) }, pull)
                View.DAY -> Timeline(model, listOf(model.anchor), pull)
                View.AGENDA -> AgendaView(model, pull)
            }
        }
    }

    if (sideways) {
        // On its side the screen is short, so the title, the four views and the two buttons
        // share one line and the way through the calendar moves to a column at the right:
        // everything the portrait screen spends two bars and a foot on is a single 48 dp line.
        Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    Modifier.fillMaxWidth().height(SIDEWAYS_HEADER).padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextMMD(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    CompactTabs(model.view, model::show)
                    BarButton(Icons.Search, stringResource(R.string.cd_search)) { model.push(Screen.Search) }
                    BarButton(Icons.Settings, stringResource(R.string.cd_settings)) { model.push(Screen.Settings) }
                }
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight()) { body() }
                    Column(
                        Modifier.width(64.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        BarButton(Icons.ChevronLeft, stringResource(R.string.cd_previous)) { model.step(false) }
                        TodayButton(model, Modifier.size(width = 64.dp, height = 48.dp))
                        BarButton(Icons.ChevronRight, stringResource(R.string.cd_next)) { model.step(true) }
                        BarButton(Icons.Add, stringResource(R.string.cd_add)) { model.newEvent() }
                    }
                }
            }
        }
        return
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
            body()
            HorizontalDividerMMD(thickness = 1.dp)
            Foot(model)
        }
    }
}

/** True while the screen is wider than it is tall. */
@Composable
internal fun onItsSide(): Boolean = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

private val SIDEWAYS_HEADER = 48.dp

@Composable
private fun TodayButton(model: AppModel, modifier: Modifier) {
    Box(modifier.clickable { model.today() }, contentAlignment = Alignment.Center) {
        TextMMD(text = stringResource(R.string.today), style = MaterialTheme.typography.bodyMedium, maxLines = 1, softWrap = false)
    }
}

/**
 * The same four stones as [StoneTabs] at the same size of type, on a 48 dp line with no rule
 * beneath, for the header of the screen on its side.
 */
@Composable
private fun CompactTabs(current: View, onPick: (View) -> Unit) {
    val views = listOf(View.MONTH, View.WEEK, View.DAY, View.AGENDA)
    val labels = listOf(R.string.view_month, R.string.view_week, R.string.view_day, R.string.view_agenda)
        .map { stringResource(it) }
    val style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    Row(
        Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            val chosen = views[i] == current
            Box(
                Modifier
                    .height(40.dp)
                    .then(if (chosen) Modifier.background(Color.Black, RoundedCornerShape(4.dp)) else Modifier)
                    .selectable(selected = chosen, role = Role.Tab, onClick = { onPick(views[i]) })
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextMMD(text = label, style = style, color = if (chosen) Color.White else Color.Black, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun ViewTabs(current: View, onPick: (View) -> Unit) {
    val views = listOf(View.MONTH, View.WEEK, View.DAY, View.AGENDA)
    val labels = listOf(R.string.view_month, R.string.view_week, R.string.view_day, R.string.view_agenda)
        .map { stringResource(it) }
    StoneTabs(labels, views.indexOf(current), onSelect = { onPick(views[it]) })
}

@Composable
private fun Foot(model: AppModel) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(Icons.ChevronLeft, stringResource(R.string.cd_previous)) { model.step(false) }
        TodayButton(model, Modifier.height(48.dp).padding(horizontal = 12.dp))
        BarButton(Icons.ChevronRight, stringResource(R.string.cd_next)) { model.step(true) }
        Spacer(Modifier.weight(1f))
        BarButton(Icons.Add, stringResource(R.string.cd_add)) { model.newEvent() }
    }
}

// ----------------------------------------------------------------------------------------
// Month

@Composable
private fun MonthView(model: AppModel, onPull: () -> Unit) {
    val (first, _) = model.range()
    val month = YearMonth.from(model.anchor)
    val showWeeks = model.settings.weekNumbers
    val today = LocalDate.now()
    // Only the weeks the month needs: most months fit in five, and a sixth row costs every
    // day a line. The days either side that share those weeks are shown with their events,
    // as Etar does, so the end of one month still says what the start of the next holds.
    val weeks = ((ChronoUnit.DAYS.between(first, month.atDay(1)) + month.lengthOfMonth() + 6) / 7).toInt()

    Column(Modifier.fillMaxSize().swipeVertical(Unit, onUp = onPull, onDown = {})) {
        val sideways = onItsSide()
        Row(Modifier.fillMaxWidth().padding(top = if (sideways) 0.dp else 6.dp, bottom = if (sideways) 0.dp else 4.dp)) {
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

/**
 * One line of a day cell. The month is the one place in this app below the house floor of
 * 14sp: at 14 a Kompakt's month cell holds two lines and a busy day was "+6"; at 12, as Etar
 * sets it, it holds four, which is what makes a month readable at a glance. He asked for
 * Etar's density, 2026-09-26.
 */
private val LINE = 13.dp

/** How many event lines a cell [height] dp tall holds under its [number] row of [line] dp lines. */
internal fun monthRoom(height: Float, number: Float, line: Float): Int = ((height - number) / line).toInt().coerceAtLeast(0)

/** The day's number at the top of its cell. */
private val NUMBER_ROW = 17.dp
private val NUMBER_ROW_SIDEWAYS = 15.dp

@Composable
private fun monthStyle() = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 13.sp)

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
    // A little shorter on its side, where a whole line more is worth the two dp.
    val numberRow = if (onItsSide()) NUMBER_ROW_SIDEWAYS else NUMBER_ROW
    val lineStyle = monthStyle()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(modifier) {
        val cell = maxWidth / 7
        val textWidthPx = with(density) { (cell - 4.dp).toPx() }
        val room = monthRoom(maxHeight.value, numberRow.value, LINE.value)
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
                    Row(Modifier.height(numberRow), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .background(if (date == today) Color.Black else Color.White, RoundedCornerShape(5.dp))
                                .padding(horizontal = 3.dp),
                        ) {
                            TextMMD(
                                text = date.dayOfMonth.toString(),
                                style = lineStyle.copy(fontSize = 13.sp),
                                // The month's own days in bold; the neighbours' plain, since
                                // there is no grey to set them back with.
                                fontWeight = if (inMonth || date == today) FontWeight.Bold else FontWeight.Normal,
                                color = if (date == today) Color.White else Color.Black,
                            )
                        }
                        // A cell with no line to spare (the month on its side) still says how
                        // many events the day holds, beside its number.
                        if (room == 0 && timed.size + barLanes.size > 0) {
                            TextMMD(
                                text = "+${timed.size + barLanes.size}",
                                style = lineStyle,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                    // A title too long for its line may take the free line under it, as Etar
                    // wraps "Silvermine Trail" — but only on a day with lines to spare, so it
                    // never costs another event its place.
                    var spare = if (overflow) 0 else free.size - timed.size
                    var next = 0
                    timed.take(shown).forEach { o ->
                        val title = o.title.ifBlank { stringResource(R.string.untitled) }
                        val slot = free[next]
                        val wraps = spare > 0 && next + 1 < free.size && free[next + 1] == slot + 1 &&
                            measurer.measure(title, lineStyle, softWrap = false).size.width > textWidthPx
                        val lines = if (wraps) 2 else 1
                        if (wraps) spare--
                        next += lines
                        TextMMD(
                            text = title,
                            style = lineStyle,
                            maxLines = lines,
                            softWrap = wraps,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.offset(y = numberRow + LINE * slot).height(LINE * lines),
                        )
                    }
                    if (hidden > 0 && free.isNotEmpty()) {
                        TextMMD(
                            text = "+$hidden",
                            style = lineStyle,
                            modifier = Modifier.offset(y = numberRow + LINE * free[minOf(next, free.lastIndex)]).height(LINE),
                        )
                    }
                }
            }
        }

        // The bars, over the cells. Taps fall through them to the day beneath.
        bars.filter { it.lane < barLimit }.forEach { bar ->
            Box(
                modifier = Modifier
                    .offset(x = cell * bar.from + 1.dp, y = numberRow + LINE * bar.lane)
                    .width(cell * (bar.to - bar.from + 1) - 2.dp)
                    // The whole line: any shorter and 12sp loses its descenders.
                    .height(LINE)
                    .background(Color.Black, RoundedCornerShape(3.dp))
                    .padding(horizontal = 3.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                TextMMD(
                    text = bar.occurrence.title.ifBlank { stringResource(R.string.untitled) },
                    style = lineStyle.copy(lineHeight = 12.sp),
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
private fun Timeline(model: AppModel, days: List<LocalDate>, onPull: () -> Unit) {
    val context = LocalContext.current
    val week = days.size > 1
    val today = LocalDate.now()
    // On its side the screen is about half as tall, so a page is six hours, not twelve: an hour
    // twelve to the screen leaves no room for its own label.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val span = if (landscape) 6 else 12
    val starts = if (landscape) listOf(0, 6, 12, 18) else listOf(0, 8, 12)
    val page = model.hours.coerceAtMost(starts.lastIndex)
    val startHour = starts[page]
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
            Row(Modifier.fillMaxWidth().padding(top = if (landscape) 0.dp else 4.dp)) {
                Spacer(Modifier.width(HOUR_COLUMN))
                days.forEach { d ->
                    val head: @Composable () -> Unit = {
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
                    // On its side the letter and the number share a line, to save the height.
                    if (landscape) {
                        Row(
                            modifier = Modifier.weight(1f).clickable { model.openDay(d) },
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) { head() }
                    } else {
                        Column(
                            modifier = Modifier.weight(1f).clickable { model.openDay(d) }.padding(vertical = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) { head() }
                    }
                }
                Spacer(Modifier.width(RAIL_WIDTH))
            }
        }

        AllDayStrip(model.occurrences.filter { it.allDay || it.firstDay != it.lastDay }, days, week, open)
        HorizontalDividerMMD(thickness = 1.dp)

        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // On the first third of the day a swipe down has nowhere earlier to go: it asks for a sync.
                .swipeVertical(page to span, { if (page == 0) onPull() else model.stepHours(false, starts.lastIndex) }, { model.stepHours(true, starts.lastIndex) }),
        ) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val hourHeight = maxHeight / span
                // Hour lines and labels.
                for (i in 0 until span) {
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
                            span = span,
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
            HoursRail(page, starts.size) { model.showHours(it) }
        }
    }
}

private val HOUR_COLUMN = 44.dp

/** The hours rail beside the grid; the header and all-day rows reserve the same width so their columns match the grid's. */
private val RAIL_WIDTH = 20.dp

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
        Spacer(Modifier.width(RAIL_WIDTH))
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
    span: Int,
    hourHeight: Dp,
    now: LocalDateTime,
    showTimes: Boolean,
    onOpen: (Occurrence) -> Unit,
    onAdd: (LocalTime) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val startMin = rangeStart.toSecondOfDay() / 60
    val endMin = startMin + span * 60
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
private fun HoursRail(page: Int, count: Int, onPick: (Int) -> Unit) {
    Column(
        modifier = Modifier.width(RAIL_WIDTH).fillMaxHeight(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (i in 0 until count) {
            Box(
                Modifier
                    .padding(vertical = if (count > 3) 2.dp else 4.dp)
                    .size(width = 8.dp, height = if (count > 3) 24.dp else 56.dp)
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
private fun AgendaView(model: AppModel, onPull: () -> Unit) {
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
        Box(Modifier.fillMaxSize().swipeVertical(Unit, onUp = onPull, onDown = {}).padding(24.dp)) {
            TextMMD(text = stringResource(R.string.agenda_empty), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val state = rememberLazyListState()
    // Today goes back to the first page, even when the anchor was already today.
    LaunchedEffect(model.todayPresses) { if (model.todayPresses > 0) state.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        PagedColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            state = state,
            onPullAtTop = onPull,
        ) {
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
        }
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
