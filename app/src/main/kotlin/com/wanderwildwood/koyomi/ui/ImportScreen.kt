package com.wanderwildwood.koyomi.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.koyomi.AppModel
import com.wanderwildwood.koyomi.R
import com.wanderwildwood.koyomi.Screen
import com.wanderwildwood.koyomi.data.CalendarStore
import com.wanderwildwood.koyomi.data.IcsEvent
import com.wanderwildwood.koyomi.repeat.RepeatText
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The events in an .ics file, the calendar they will go in, and Add. Every event in the file
 * is added; an invitation becomes a plain event, with no reply sent.
 */
@Composable
fun ImportScreen(model: AppModel, screen: Screen.Import) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf<List<IcsEvent>?>(null) }
    var read by remember { mutableStateOf(false) }
    var calendarId by remember { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(screen) {
        events = model.readIcs(screen.uri)
        read = true
    }
    val writable = model.writableCalendars()
    if (calendarId == null || writable.none { it.id == calendarId }) {
        calendarId = (
            writable.firstOrNull { it.id == model.settings.defaultCalendar }
                ?: writable.firstOrNull { it.isPrimary && it.accountType != android.provider.CalendarContract.ACCOUNT_TYPE_LOCAL }
                ?: writable.firstOrNull()
            )?.id
    }

    fun add() {
        val list = events.orEmpty()
        val target = calendarId
        if (adding || list.isEmpty()) return
        if (target == null) {
            Toast.makeText(context, R.string.edit_no_calendar, Toast.LENGTH_LONG).show()
            return
        }
        adding = true
        scope.launch {
            val (added, had) = model.addAll(list, target)
            val words = buildList {
                if (added > 0) add(context.resources.getQuantityString(R.plurals.import_added, added, added))
                if (had > 0) add(context.resources.getQuantityString(R.plurals.import_already, had, had))
            }
            if (words.isEmpty()) {
                adding = false
                Toast.makeText(context, R.string.import_failed, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, words.joinToString("; "), Toast.LENGTH_LONG).show()
                model.popAll()
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.import_title)) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.cd_close)) { model.pop() } },
                actions = {
                    if (!events.isNullOrEmpty()) {
                        Box(
                            Modifier.height(48.dp).clickable { add() }.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TextMMD(text = stringResource(R.string.import_add), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (!read) return@Scaffold
        val list = events
        if (list.isNullOrEmpty()) {
            Column(Modifier.padding(padding).padding(20.dp)) {
                TextMMD(
                    text = stringResource(if (list == null) R.string.import_unreadable else R.string.import_nothing),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Scaffold
        }
        LazyColumnMMD(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            item {
                SettingRow(
                    title = stringResource(R.string.event_calendar),
                    value = writable.firstOrNull { it.id == calendarId }?.name ?: stringResource(R.string.edit_no_calendar_short),
                    onClick = { picking = true },
                )
                HorizontalDividerMMD(thickness = 1.dp)
            }
            list.forEach { e -> item { ImportRow(e) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (picking) {
        EInkDialog(onDismiss = { picking = false }) {
            TextMMD(text = stringResource(R.string.event_calendar), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            if (writable.isEmpty()) {
                TextMMD(text = stringResource(R.string.edit_no_calendar), style = MaterialTheme.typography.labelSmall)
            }
            writable.forEach { c ->
                ChoiceRow(c.name, bold = c.id == calendarId) {
                    calendarId = c.id
                    picking = false
                }
            }
        }
    }
}

/** What, when — in the phone's own time — where, and how it repeats. */
@Composable
private fun ImportRow(e: IcsEvent) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val start = CalendarStore.toLocal(e.startMillis, e.allDay, zone)
    val finish = CalendarStore.toLocal(e.endMillis, e.allDay, zone)
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        TextMMD(
            text = e.title.ifBlank { stringResource(R.string.untitled) },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        TextMMD(
            text = if (e.allDay) {
                stringResource(R.string.event_all_day, Dates.span(context, start, finish, true))
            } else {
                Dates.span(context, start, finish, false)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        e.rrule?.let {
            TextMMD(text = RepeatText.of(context.resources, it, start.toLocalDate(), e.allDay), style = MaterialTheme.typography.labelSmall)
        }
        e.location?.let { TextMMD(text = it, style = MaterialTheme.typography.labelSmall) }
        e.unknownZone?.let {
            TextMMD(text = stringResource(R.string.import_unknown_zone, it), style = MaterialTheme.typography.labelSmall)
        }
    }
}
