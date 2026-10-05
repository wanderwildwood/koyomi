package com.wanderwildwood.koyomi

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wanderwildwood.koyomi.alerts.Alerts
import com.wanderwildwood.koyomi.data.CalendarInfo
import com.wanderwildwood.koyomi.data.CalendarStore
import com.wanderwildwood.koyomi.data.Draft
import com.wanderwildwood.koyomi.data.EventRecord
import com.wanderwildwood.koyomi.data.Ics
import com.wanderwildwood.koyomi.data.IcsEvent
import com.wanderwildwood.koyomi.data.Occurrence
import com.wanderwildwood.koyomi.data.Prefill
import com.wanderwildwood.koyomi.data.Scope
import com.wanderwildwood.koyomi.data.Settings
import com.wanderwildwood.koyomi.data.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** A screen laid over the four views. The views themselves are the bottom of the stack. */
sealed interface Screen {
    data class Event(val eventId: Long, val begin: Long, val end: Long) : Screen

    /** [record] is null for a new event. */
    data class Edit(val record: EventRecord?, val begin: Long, val end: Long, val scope: Scope) : Screen
    data object Repeat : Screen
    data object Search : Screen
    data object Settings : Screen

    /** The events of an .ics file another app opened here, to be added to a calendar. */
    data class Import(val uri: Uri) : Screen
}

/** What another app asked this one for: a new event, an event shown or changed, a file read. */
sealed interface Request {
    data class New(val prefill: Prefill) : Request
    data class Show(val eventId: Long, val begin: Long?, val end: Long?, val edit: Boolean) : Request
    data class Import(val uri: Uri) : Request
}

class AppModel(app: Application) : AndroidViewModel(app) {
    val store = CalendarStore(app)
    val settings = Settings(app)

    var canRead by mutableStateOf(store.canRead())
        private set

    var view by mutableStateOf(settings.openOn)
        private set

    /** The date the views are about: the month it is in, the week, the day. */
    var anchor by mutableStateOf(LocalDate.now())
        private set

    /** Which third of the day a timeline shows: 0 is midnight to noon, 1 is 8 to 8, 2 noon on. */
    var hours by mutableStateOf(initialHours())
        private set

    var occurrences by mutableStateOf<List<Occurrence>>(emptyList())
        private set

    var calendars by mutableStateOf<List<CalendarInfo>>(emptyList())
        private set

    /** Bumped when settings change, so screens reading [settings] redraw. */
    var settingsVersion by mutableStateOf(0)
        private set

    val stack = mutableStateListOf<Screen>()

    /** The editor's working copy, which the repeat screen also edits. */
    var draft by mutableStateOf(Draft())

    private var loadJob: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // A sync arrives as a burst of changes; one reload after it settles is enough.
            reload(debounce = true)
        }
    }

    init {
        watch()
        reload()
    }

    private fun watch() {
        if (!canRead) return
        runCatching {
            getApplication<Application>().contentResolver
                .registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        }
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }

    fun permissionsChanged() {
        val before = canRead
        canRead = store.canRead()
        if (canRead && !before) {
            watch()
            viewModelScope.launch(Dispatchers.IO) { Alerts.check(getApplication()) }
        }
        reload()
    }

    /** The dates the current view covers. */
    fun range(): Pair<LocalDate, LocalDate> = when (view) {
        View.MONTH -> {
            val first = YearMonth.from(anchor).atDay(1).with(TemporalAdjusters.previousOrSame(settings.weekStart))
            first to first.plusDays(41)
        }
        View.WEEK -> {
            val first = anchor.with(TemporalAdjusters.previousOrSame(settings.weekStart))
            first to first.plusDays(6)
        }
        View.DAY -> anchor to anchor
        View.AGENDA -> anchor to anchor.plusMonths(6)
    }

    fun reload(debounce: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (debounce) delay(600)
            val (from, to) = range()
            val (found, cals) = withContext(Dispatchers.IO) {
                store.occurrences(from, to) to store.calendars()
            }
            occurrences = found
            calendars = cals
        }
    }

    fun show(v: View) {
        view = v
        reload()
    }

    fun goTo(date: LocalDate) {
        anchor = date
        reload()
    }

    fun today() {
        hours = initialHours()
        goTo(LocalDate.now())
    }

    fun step(forward: Boolean) {
        val n = if (forward) 1L else -1L
        goTo(
            when (view) {
                View.MONTH -> anchor.plusMonths(n)
                View.WEEK -> anchor.plusWeeks(n)
                View.DAY -> anchor.plusDays(n)
                View.AGENDA -> anchor.plusMonths(n)
            },
        )
    }

    fun openDay(date: LocalDate) {
        anchor = date
        show(View.DAY)
    }

    fun stepHours(later: Boolean) {
        hours = (hours + if (later) 1 else -1).coerceIn(0, 2)
    }

    fun showHours(page: Int) {
        hours = page.coerceIn(0, 2)
    }

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    /** Back to the views, with nothing laid over them. */
    fun popAll() {
        stack.clear()
    }

    fun settingsChanged() {
        settingsVersion++
        reload()
    }

    fun refreshCalendars() {
        viewModelScope.launch {
            calendars = withContext(Dispatchers.IO) { store.calendars() }
        }
    }

    fun setVisible(calendar: CalendarInfo, visible: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.setVisible(calendar.id, visible) }
            reload()
        }
    }

    // ------------------------------------------------------------------------------------
    // Editing

    /**
     * The calendars a new event may go in: ones that can be written to and are showing.
     *
     * Found on his Kompakt: the calendar the phone marks primary is Mudita's "PC Sync" — local,
     * hidden, and never synced. Picked as the fallback, a new event went where nothing would
     * ever show it or carry it to a server. A hidden calendar is not offered, and a synced one
     * comes before a local one.
     */
    fun writableCalendars(): List<CalendarInfo> = calendars
        .filter { it.writable && it.visible }
        .sortedBy { it.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL }

    /** A new event on [date], at [time] if a timeline was long-pressed, otherwise the next hour. */
    fun newEvent(date: LocalDate = anchor, time: LocalTime? = null) {
        val writable = writableCalendars()
        val calendar = writable.firstOrNull { it.id == settings.defaultCalendar }
            ?: writable.firstOrNull { it.isPrimary && it.accountType != CalendarContract.ACCOUNT_TYPE_LOCAL }
            ?: writable.firstOrNull()
        val start = time ?: LocalTime.now().withMinute(0).withSecond(0).withNano(0).plusHours(1)
        val rolled = time == null && start == LocalTime.MIDNIGHT
        val day = if (rolled && date == LocalDate.now()) date.plusDays(1) else date
        draft = Draft(
            calendarId = calendar?.id,
            startDate = day,
            startTime = start,
            endDate = if (start.plusHours(1) < start) day.plusDays(1) else day,
            endTime = start.plusHours(1),
            reminders = settings.defaultReminder.takeIf { it >= 0 }?.let { listOf(it) } ?: emptyList(),
        )
        push(Screen.Edit(null, 0, 0, Scope.ALL))
    }

    fun editEvent(record: EventRecord, begin: Long, end: Long, scope: Scope) {
        draft = Draft.of(record, begin, end, keepRule = scope != Scope.ONE)
        push(Screen.Edit(record, begin, end, scope))
    }

    /** Can the event being edited go to another calendar? See [CalendarStore.update]. */
    suspend fun canMove(screen: Screen.Edit): Boolean {
        val r = screen.record ?: return true
        if (r.originalId != null) return false
        if (r.rrule != null && screen.scope != Scope.ALL) return false
        if (writableCalendars().none { it.id != r.calendarId }) return false
        return withContext(Dispatchers.IO) { runCatching { !store.hasExceptions(r) }.getOrDefault(false) }
    }

    suspend fun save(screen: Screen.Edit): Boolean {
        val d = draft
        // The store refuses what it will not take by throwing; that is a "no" to say on
        // screen, not a reason to lose the editor and what was typed into it.
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                if (screen.record == null) {
                    store.insert(d) != null
                } else {
                    store.update(screen.record, screen.begin, screen.end, d, screen.scope)
                }
            }.onFailure { Log.w(TAG, "Save refused", it) }.getOrDefault(false)
        }
        if (ok) {
            reload()
            viewModelScope.launch(Dispatchers.IO) { Alerts.check(getApplication()) }
        }
        return ok
    }

    suspend fun delete(record: EventRecord, begin: Long, end: Long, scope: Scope): Boolean {
        val ok = withContext(Dispatchers.IO) {
            runCatching { store.delete(record, begin, end, scope) }
                .onFailure { Log.w(TAG, "Delete refused", it) }.getOrDefault(false)
        }
        if (ok) reload()
        return ok
    }

    suspend fun load(eventId: Long): EventRecord? = withContext(Dispatchers.IO) { store.event(eventId) }

    suspend fun search(query: String): List<Occurrence> = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        store.occurrences(today.minusYears(1), today.plusYears(2), query)
    }

    // ------------------------------------------------------------------------------------
    // Asked for by another app

    /** Has the request been turned into a screen yet? Until then there is nothing to finish. */
    var opened by mutableStateOf(false)
        private set

    suspend fun open(request: Request) {
        if (opened) return
        // The request is answered from the calendars, which the first reload may not have
        // brought in yet: a new event would otherwise have no calendar to go in.
        calendars = withContext(Dispatchers.IO) { store.calendars() }
        when (request) {
            is Request.New -> {
                newEvent()
                val p = request.prefill
                val calendar = p.calendarId?.takeIf { id -> writableCalendars().any { it.id == id } }
                draft = p.copy(calendarId = calendar).draft(draft, ZoneId.systemDefault(), LocalDateTime.now())
            }
            is Request.Show -> {
                val r = load(request.eventId)
                val begin = request.begin ?: r?.dtStart ?: 0L
                val end = request.end
                    ?: r?.let { it.dtEnd ?: (it.dtStart + (it.duration?.let(Ics::duration)?.toMillis() ?: 0L)) }
                    ?: begin
                push(Screen.Event(request.eventId, begin, end))
                // A repeating event asks which occurrences to change; that question is on the
                // event's own screen, so the editor is not opened over it.
                val writable = r != null && calendars.any { it.id == r.calendarId && it.writable }
                if (request.edit && r != null && r.rrule == null && writable) editEvent(r, begin, end, Scope.ALL)
            }
            is Request.Import -> push(Screen.Import(request.uri))
        }
        opened = true
    }

    /** The events in an .ics file, or null if it could not be read. */
    suspend fun readIcs(uri: Uri): List<IcsEvent>? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
                // A calendar of events is small; a file the size of a photograph is not one.
                val buffer = ByteArray(MAX_ICS + 1)
                var n = 0
                while (n < buffer.size) {
                    val read = input.read(buffer, n, buffer.size - n)
                    if (read < 0) break
                    n += read
                }
                require(n <= MAX_ICS) { "Too large for a calendar file" }
                buffer.copyOf(n)
            }
            Ics.parse(String(bytes, Charsets.UTF_8), ZoneId.systemDefault(), ::zoneOf)
        }.onFailure { Log.w(TAG, "Could not read $uri", it) }.getOrNull()
    }

    /** An IANA zone, or a Windows one as Outlook writes them ("W. Europe Standard Time"). */
    private fun zoneOf(tzid: String): ZoneId? = Ics.ianaZone(tzid) ?: runCatching {
        android.icu.util.TimeZone.getIDForWindowsID(tzid.trim().trim('"'), "001")?.let { ZoneId.of(it) }
    }.getOrNull()

    /**
     * Adds [events] to [calendarId], each with the reminder a new event would get. Returns how
     * many were added and how many were there already, by UID.
     */
    suspend fun addAll(events: List<IcsEvent>, calendarId: Long): Pair<Int, Int> {
        val reminder = settings.defaultReminder.takeIf { it >= 0 }
        val result = withContext(Dispatchers.IO) {
            var added = 0
            var had = 0
            for (e in events) {
                val there = e.uid != null && runCatching { store.hasUid(calendarId, e.uid) }.getOrDefault(false)
                if (there) {
                    had++
                    continue
                }
                val reminders = when {
                    reminder == null -> emptyList()
                    e.allDay -> listOf(900)
                    else -> listOf(reminder)
                }
                runCatching { store.insertImported(e, calendarId, reminders) }
                    .onFailure { Log.w(TAG, "Import refused", it) }
                    .getOrNull()?.let { added++ }
            }
            added to had
        }
        if (result.first > 0) {
            reload()
            viewModelScope.launch(Dispatchers.IO) { Alerts.check(getApplication()) }
        }
        return result
    }

    private companion object {
        const val TAG = "AppModel"
        const val MAX_ICS = 4 * 1024 * 1024
    }

    private fun initialHours(): Int {
        val h = LocalTime.now().hour
        return when {
            h < 8 -> 0
            h < 18 -> 1
            else -> 2
        }
    }
}
