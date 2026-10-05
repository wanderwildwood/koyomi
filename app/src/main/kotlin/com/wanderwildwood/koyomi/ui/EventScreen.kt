package com.wanderwildwood.koyomi.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.koyomi.AppModel
import com.wanderwildwood.koyomi.R
import com.wanderwildwood.koyomi.Screen
import com.wanderwildwood.koyomi.data.CalendarStore
import com.wanderwildwood.koyomi.data.EventRecord
import com.wanderwildwood.koyomi.data.Scope
import com.wanderwildwood.koyomi.repeat.RepeatText
import kotlinx.coroutines.launch
import java.time.ZoneId

/** One occurrence, read out, with what can be done to it underneath. */
@Composable
fun EventScreen(model: AppModel, screen: Screen.Event) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var record by remember { mutableStateOf<EventRecord?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var askScope by remember { mutableStateOf(false) }
    // Delete asks first, in a dialog: it sits in the bar beside Edit, where it can be found,
    // and one tap there must not be enough to lose an event.
    var askDelete by remember { mutableStateOf(false) }

    // Reloads when the store changes under it, so an edit shows once it is saved.
    LaunchedEffect(screen, model.occurrences) {
        record = model.load(screen.eventId)
        loaded = true
    }

    val writable = record?.let { r -> model.calendars.firstOrNull { it.id == r.calendarId }?.writable } ?: false

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.event_title)) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.cd_close)) { model.pop() } },
                actions = {
                    if (writable) {
                        BarButton(Icons.Delete, stringResource(R.string.cd_delete)) { if (record != null) askDelete = true }
                        BarButton(Icons.Edit, stringResource(R.string.cd_edit)) {
                            val r = record ?: return@BarButton
                            if (r.rrule != null) askScope = true else model.editEvent(r, screen.begin, screen.end, Scope.ALL)
                        }
                    }
                },
            )
        },
    ) { padding ->
        val r = record
        if (r == null) {
            if (loaded) {
                Column(Modifier.padding(padding).padding(20.dp)) {
                    TextMMD(text = stringResource(R.string.event_gone), style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@Scaffold
        }
        val zone = ZoneId.systemDefault()
        val start = CalendarStore.toLocal(screen.begin, r.allDay, zone)
        val finish = CalendarStore.toLocal(screen.end, r.allDay, zone)
        val calendar = model.calendars.firstOrNull { it.id == r.calendarId }

        LazyColumnMMD(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            item {
                Spacer(Modifier.height(16.dp))
                TextMMD(
                    text = r.title.ifBlank { stringResource(R.string.untitled) },
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))
                TextMMD(
                    text = if (r.allDay) {
                        stringResource(R.string.event_all_day, Dates.span(context, start, finish, true))
                    } else {
                        Dates.span(context, start, finish, false)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (r.rrule != null) {
                item { Detail(stringResource(R.string.event_repeats), RepeatText.of(context.resources, r.rrule, start.toLocalDate(), r.allDay)) }
            }
            r.location?.let { item { Detail(stringResource(R.string.event_where), it) } }
            if (r.reminders.isNotEmpty()) {
                item {
                    Detail(
                        stringResource(R.string.event_reminders),
                        r.reminders.map { it.minutes }.distinct().sorted().joinToString("\n") { ReminderText.of(context, it, r.allDay) },
                    )
                }
            }
            calendar?.let { item { Detail(stringResource(R.string.event_calendar), it.name) } }
            // The notes can be selected, for Copy and for Define and the other apps that act on
            // text; see TextActions.
            r.description?.let { item { Detail(stringResource(R.string.event_notes), it, selectable = true) } }
            // Said, rather than left as an Edit and a Delete that are simply not there.
            if (!writable) {
                item { Detail(stringResource(R.string.event_read_only_label), stringResource(R.string.event_read_only)) }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }

        fun remove(s: Scope) {
            askDelete = false
            scope.launch {
                if (model.delete(r, screen.begin, screen.end, s)) {
                    model.pop()
                } else {
                    Toast.makeText(context, R.string.event_not_deleted, Toast.LENGTH_SHORT).show()
                }
            }
        }

        if (askDelete) {
            EInkDialog(onDismiss = { askDelete = false }) {
                TextMMD(
                    text = stringResource(if (r.rrule == null) R.string.delete_which_one else R.string.delete_which),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(6.dp))
                if (r.rrule == null) {
                    ChoiceRow(stringResource(R.string.delete), bold = true) { remove(Scope.ALL) }
                } else {
                    ChoiceRow(stringResource(R.string.delete_one)) { remove(Scope.ONE) }
                    ChoiceRow(stringResource(R.string.delete_following)) { remove(Scope.FOLLOWING) }
                    ChoiceRow(stringResource(R.string.delete_all)) { remove(Scope.ALL) }
                }
                ChoiceRow(stringResource(R.string.cancel)) { askDelete = false }
            }
        }

        if (askScope) {
            EInkDialog(onDismiss = { askScope = false }) {
                TextMMD(text = stringResource(R.string.edit_which), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                ChoiceRow(stringResource(R.string.edit_this_one)) {
                    askScope = false
                    model.editEvent(r, screen.begin, screen.end, Scope.ONE)
                }
                ChoiceRow(stringResource(R.string.edit_every_one)) {
                    askScope = false
                    model.editEvent(r, screen.begin, screen.end, Scope.ALL)
                }
            }
        }
    }
}

@Composable
fun ChoiceRow(label: String, bold: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        TextMMD(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun Detail(label: String, value: String, selectable: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        TextMMD(text = label, style = MaterialTheme.typography.labelSmall)
        if (selectable) {
            SelectionContainer(Modifier.textActions()) {
                TextMMD(text = value, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            TextMMD(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
