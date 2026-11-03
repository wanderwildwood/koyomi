package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.ui.backTarget
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
import com.wanderwildwood.koyomi.data.CalendarLink
import com.wanderwildwood.koyomi.data.Fetched
import com.wanderwildwood.koyomi.data.HttpFetcher
import com.wanderwildwood.koyomi.data.Refreshed
import com.wanderwildwood.koyomi.data.Subscription
import com.wanderwildwood.koyomi.data.SubscriptionStore
import com.wanderwildwood.koyomi.data.SubscriptionSync
import com.wanderwildwood.koyomi.data.SubscriptionTarget
import com.wanderwildwood.koyomi.data.CalendarStore
import com.wanderwildwood.koyomi.data.Draft
import com.wanderwildwood.koyomi.data.EventRecord
import com.wanderwildwood.koyomi.data.Ics
import com.wanderwildwood.koyomi.data.IcsEvent
import com.wanderwildwood.koyomi.data.LinkList
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

    /** Follow a calendar by its link: a field to paste it in, and a button to scan its QR code. */
    data object AddLink : Screen

    /** The camera, looking for a QR code that holds a calendar link. */
    data object ScanLink : Screen
}

/** What another app asked this one for: a new event, an event shown or changed, a file read. */
sealed interface Request {
    data class New(val prefill: Prefill) : Request
    data class Show(val eventId: Long, val begin: Long?, val end: Long?, val edit: Boolean) : Request
    data class Import(val uri: Uri) : Request
}

private const val MAX_LINK_FILE = 1024 * 1024

class AppModel(app: Application) : AndroidViewModel(app) {
    val store = CalendarStore(app)
    val settings = Settings(app)
    private val links = SubscriptionStore(app)

    /** Calendars followed by a link, with when each last arrived and what went wrong if it did not. */
    var subscriptions by mutableStateOf<List<Subscription>>(links.all())
        private set

    /** Whether links are being fetched right now. */
    var fetching by mutableStateOf(false)
        private set
    private var lastFetch = 0L

    /** A link the scanner found, waiting for the Add screen to take it. */
    var scannedLink by mutableStateOf<String?>(null)

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
            // All-day reminders are worked out from the same events; look them over again too.
            kotlin.concurrent.thread { com.wanderwildwood.koyomi.alerts.AllDayReminders.check(getApplication<Application>()) }
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

    /** Back from a view: the Month view of the same month (the anchor stays). */
    fun backToMonth() {
        backTarget(view)?.let { show(it) }
    }

    fun goTo(date: LocalDate) {
        anchor = date
        reload()
    }

    /**
     * Bumped by every press of Today. The anchor alone cannot say "go back": when it already is
     * today nothing changes, and a list paged down (the Agenda) would stay where it was.
     */
    var todayPresses by mutableStateOf(0)
        private set

    fun today() {
        hours = initialHours()
        todayPresses++
        goTo(LocalDate.now())
    }

    /**
     * Back after a while away: the view the reader chose to open on, at today. Only when nothing
     * is open over the views, so an event or a draft being written is never thrown away.
     */
    fun reopen() {
        if (stack.isNotEmpty()) return
        view = settings.openOn
        today()
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

    /** [last] is the last page there is: 2 for twelve-hour pages, 3 for the six-hour ones. */
    fun stepHours(later: Boolean, last: Int = 2) {
        hours = (hours.coerceAtMost(last) + if (later) 1 else -1).coerceIn(0, last)
    }

    fun showHours(page: Int) {
        hours = page.coerceIn(0, 3)
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

    /** No calendar at all that an event could be written to, hidden or not. */
    val noCalendarToWrite: Boolean get() = calendars.none { it.writable }

    /** Makes "On this phone", and puts an event being made into it. */
    fun makeLocalCalendar() {
        viewModelScope.launch {
            val name = getApplication<Application>().getString(R.string.calendar_on_this_phone)
            val id = withContext(Dispatchers.IO) { store.makeLocalCalendar(name) }
            calendars = withContext(Dispatchers.IO) { store.calendars() }
            if (id != null && draft.calendarId == null) draft = draft.copy(calendarId = id)
            reload()
        }
    }

    private fun failureText(f: Fetched.Failed): String {
        val app = getApplication<Application>()
        return when (f.kind) {
            Fetched.Kind.SERVER -> f.message
            Fetched.Kind.NO_HOST -> app.getString(R.string.link_err_no_host)
            Fetched.Kind.TIMEOUT -> app.getString(R.string.link_err_timeout)
            Fetched.Kind.CERTIFICATE -> app.getString(R.string.link_err_certificate)
            Fetched.Kind.UNREACHABLE -> app.getString(R.string.link_err_unreachable)
            Fetched.Kind.TOO_BIG -> app.getString(R.string.link_err_too_big)
            Fetched.Kind.OPEN -> app.getString(R.string.link_err_open)
        }
    }

    private fun refreshOne(sub: Subscription): Refreshed {
        val app = getApplication<Application>()
        return SubscriptionSync.refresh(
            sub, HttpFetcher(), SubscriptionTarget(store::replaceSubscriptionEvents), ZoneId.systemDefault(),
            System.currentTimeMillis(), ::failureText,
            notACalendar = app.getString(R.string.link_err_not_calendar),
            notSaved = app.getString(R.string.link_err_not_saved),
        )
    }

    /**
     * Fetches every followed link again, one after another, when the app opens (at most once in
     * five minutes unless [force]) and on a pull down. No background work. A link that fails
     * keeps its last copy and the server's words.
     */
    fun refreshSubscriptions(force: Boolean = false) {
        if (fetching || !canRead || !store.canWrite()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastFetch < 5 * 60 * 1000) return
        val gone = links.all().filter { s -> calendars.isNotEmpty() && calendars.none { it.id == s.calendarId } }
        gone.forEach { links.remove(it.calendarId) }
        val todo = links.all()
        if (todo.isEmpty()) { subscriptions = todo; return }
        lastFetch = now
        fetching = true
        viewModelScope.launch {
            var changed = false
            withContext(Dispatchers.IO) {
                for (sub in todo) {
                    val out = refreshOne(sub)
                    when (out) {
                        is Refreshed.Done -> { links.put(out.subscription); changed = true }
                        is Refreshed.Kept -> links.put(out.subscription)
                    }
                    subscriptions = links.all()
                }
            }
            fetching = false
            subscriptions = links.all()
            if (changed) {
                reload()
                viewModelScope.launch(Dispatchers.IO) { Alerts.check(getApplication()) }
            }
        }
    }

    /**
     * Follows [url] (already through [CalendarLink.normalise]): fetches it once, and only if that
     * gives a calendar makes the read-only calendar for it. Returns null when it worked, or the
     * words to show.
     */
    suspend fun followLink(url: String): String? {
        val app = getApplication<Application>()
        if (!store.canWrite()) return app.getString(R.string.link_err_no_permission)
        return withContext(Dispatchers.IO) {
            val first = when (val got = runCatching { HttpFetcher().fetch(url) }.getOrElse { Fetched.Failed(Fetched.Kind.OPEN) }) {
                is Fetched.Failed -> return@withContext failureText(got)
                is Fetched.Body -> got.text
            }
            if (!first.contains("BEGIN:VCALENDAR", ignoreCase = true)) return@withContext app.getString(R.string.link_err_not_calendar)
            val name = Ics.calendarName(first) ?: CalendarLink.hostOf(url)
            val id = store.makeSubscriptionCalendar(name) ?: return@withContext app.getString(R.string.link_err_not_saved)
            val sub = Subscription(id, url, name)
            val ok = SubscriptionSync.refresh(
                sub, { Fetched.Body(first) }, SubscriptionTarget(store::replaceSubscriptionEvents), ZoneId.systemDefault(),
                System.currentTimeMillis(),
            )
            if (ok is Refreshed.Done) {
                links.put(ok.subscription)
                null
            } else {
                store.deleteSubscriptionCalendar(id)
                app.getString(R.string.link_err_not_saved)
            }
        }.also {
            subscriptions = links.all()
            calendars = withContext(Dispatchers.IO) { store.calendars() }
            if (it == null) {
                reload()
                viewModelScope.launch(Dispatchers.IO) { Alerts.check(getApplication()) }
            }
        }
    }

    /** Writes the followed links to [uri], the file the person picked; false, with the file removed, when that fails. */
    suspend fun saveLinks(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val text = LinkList.write(links.all())
        val ok = runCatching {
            app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("no stream")
        }.isSuccess
        if (!ok) runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri) }
        ok
    }

    sealed interface LinksRestored {
        data class Done(val added: Int, val already: Int) : LinksRestored
        data class Refused(val kind: LinkList.Refused.Kind) : LinksRestored
        data object Unreadable : LinksRestored
        data object NoPermission : LinksRestored
    }

    /**
     * Follows every link in the file at [uri] that is not followed already. The whole file is
     * read and checked first. Each calendar is made straight away under the name in the file and
     * then fetched like the others, so a link stays followed even when the phone is offline now:
     * it shows the server's words and fills on the next pull.
     */
    suspend fun restoreLinks(uri: Uri): LinksRestored {
        val app = getApplication<Application>()
        if (!store.canWrite()) return LinksRestored.NoPermission
        val result = withContext(Dispatchers.IO) {
            val text = runCatching {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    // Read up to one byte past the cap (readNBytes is API 33), so too big can be told from just fits.
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (out.size() <= MAX_LINK_FILE) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    if (out.size() > MAX_LINK_FILE) null else out.toString(Charsets.UTF_8.name())
                }
            }.getOrNull()
            if (text == null) return@withContext LinksRestored.Unreadable
            val all = try {
                LinkList.read(text)
            } catch (e: LinkList.Refused) {
                return@withContext LinksRestored.Refused(e.kind)
            }
            val todo = LinkList.notYetFollowed(all, links.all())
            var added = 0
            for (link in todo) {
                val id = store.makeSubscriptionCalendar(link.name) ?: continue
                links.put(Subscription(id, link.url, link.name))
                added++
            }
            LinksRestored.Done(added, all.size - todo.size)
        }
        subscriptions = links.all()
        calendars = withContext(Dispatchers.IO) { store.calendars() }
        if (result is LinksRestored.Done && result.added > 0) refreshSubscriptions(force = true)
        return result
    }

    /** Stops following [sub]: its calendar goes, with its events, and no other calendar is touched. */
    fun unfollow(sub: Subscription) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.deleteSubscriptionCalendar(sub.calendarId)
                links.remove(sub.calendarId)
            }
            subscriptions = links.all()
            calendars = withContext(Dispatchers.IO) { store.calendars() }
            reload()
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

    /**
     * A new event filled in from [record] as the occurrence shown (same day and time, no repeat
     * rule). Nothing is saved until the person saves it from the editor.
     */
    fun duplicateEvent(record: EventRecord, begin: Long, end: Long) {
        val own = writableCalendars().firstOrNull { it.id == record.calendarId }
        val fallback = writableCalendars().let { w -> w.firstOrNull { it.id == settings.defaultCalendar } ?: w.firstOrNull() }
        draft = Draft.of(record, begin, end, keepRule = false).copy(calendarId = (own ?: fallback)?.id)
        push(Screen.Edit(null, 0, 0, Scope.ALL))
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
            Ics.parse(String(bytes, Charsets.UTF_8), ZoneId.systemDefault())
        }.onFailure { Log.w(TAG, "Could not read $uri", it) }.getOrNull()
    }

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
                val there = e.uid != null && runCatching {
                    if (e.recurrenceId != null) store.hasUidAt(calendarId, e.uid, e.startMillis) else store.hasUid(calendarId, e.uid)
                }.getOrDefault(false)
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
