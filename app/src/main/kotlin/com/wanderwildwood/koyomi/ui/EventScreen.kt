package com.wanderwildwood.koyomi.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
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

    // Reloads when the store changes under it, so an edit shows once it is saved, and a
    // followed calendar fetched again while it is open keeps showing its event.
    var known by remember(screen) { mutableStateOf<EventRecord?>(null) }
    LaunchedEffect(screen, model.occurrences) {
        record = model.loadAgain(screen, known)
        record?.let { known = it }
        loaded = true
    }

    // Made by another app that can show what it was made from (Wallet's card): opening it there
    // is the next move from such an event, so it is a button under the title, on those events
    // only and only while that app is on the phone to answer.
    val link = remember(record, screen.begin) { record?.let { appLink(context, it, screen.begin) } }

    // The place opens in Atlas, the map, when it is on the phone (GitHub #4): a tap on the
    // place itself, underlined so it reads as a link, and nothing new on the screen otherwise.
    val place = remember(record) { record?.location?.let { atlasLink(context, it) } }

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

        // A long description in rows of whole lines, so a turn never opens on half a line.
        val fit = rememberWholeLineFit()
        val body = MaterialTheme.typography.bodyMedium
        val notesLabel = stringResource(R.string.event_notes)
        PagedColumn(Modifier.fillMaxSize().padding(padding).wholeLinePage(fit), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 12.dp), showCutRow = true) {
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
            link?.let { (label, intent) ->
                item {
                    Spacer(Modifier.height(14.dp))
                    OutlinedButtonMMD(
                        onClick = {
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Notice.say(context, R.string.event_app_failed)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { TextMMD(text = stringResource(R.string.event_open_in, label), style = MaterialTheme.typography.bodyMedium) }
                }
            }
            if (r.rrule != null) {
                item { Detail(stringResource(R.string.event_repeats), RepeatText.of(context.resources, r.rrule, start.toLocalDate(), r.allDay)) }
            }
            // The place can be selected too, like the notes below: Copy, Define, Translate.
            r.location?.let {
                item {
                    Detail(stringResource(R.string.event_where), it, selectable = true, open = place?.let { (label, intent) ->
                        stringResource(R.string.event_open_in, label) to {
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Notice.say(context, R.string.event_app_failed)
                            }
                        }
                    })
                }
            }
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
            r.description?.let { text ->
                wholeLineItems("notes", text, body, fit, gap = 40.dp) { run, part, textModifier ->
                    Detail(if (part == 0) notesLabel else null, run, selectable = true, textModifier = textModifier)
                }
            }
            item {
                Spacer(Modifier.height(18.dp))
                OutlinedButtonMMD(
                    onClick = { model.duplicateEvent(r, screen.begin, screen.end) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { TextMMD(text = stringResource(R.string.duplicate), style = MaterialTheme.typography.bodyMedium) }
            }
            // Said, rather than left as an Edit and a Delete that are simply not there.
            if (!writable) {
                item { Detail(stringResource(R.string.event_read_only_label), stringResource(R.string.event_read_only)) }
            }

        }

        fun remove(s: Scope) {
            askDelete = false
            scope.launch {
                if (model.delete(r, screen.begin, screen.end, s)) {
                    model.pop()
                } else {
                    Notice.say(context, R.string.event_not_deleted)
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

/**
 * The app named in the event's `CUSTOM_APP_PACKAGE`, by its name, and the request that opens
 * the event there (`CalendarContract.ACTION_HANDLE_CUSTOM_EVENT`, as AOSP Calendar sends it);
 * null when the event names none or that app is not on the phone to answer.
 */
private fun appLink(context: Context, r: EventRecord, begin: Long): Pair<String, Intent>? {
    val pkg = r.appPackage ?: return null
    val uri = r.appUri ?: return null
    val intent = Intent(CalendarContract.ACTION_HANDLE_CUSTOM_EVENT)
        .setPackage(pkg)
        .putExtra(CalendarContract.EXTRA_CUSTOM_APP_URI, uri)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
    val pm = context.packageManager
    val found = pm.resolveActivity(intent, 0) ?: return null
    return found.activityInfo.applicationInfo.loadLabel(pm).toString() to intent
}

/** Atlas's own name and the search for [place] in it, or null while Atlas is not on the phone. */
private fun atlasLink(context: Context, place: String): Pair<String, Intent>? {
    // A place written over several lines (street, town) is searched as one line.
    val query = place.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
    if (query.isEmpty()) return null
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query))).setPackage(ATLAS)
    val pm = context.packageManager
    val found = pm.resolveActivity(intent, 0) ?: return null
    return found.activityInfo.applicationInfo.loadLabel(pm).toString() to intent
}

private const val ATLAS = "com.wanderwildwood.chizu"

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
private fun Detail(label: String?, value: String, selectable: Boolean = false, open: Pair<String, () -> Unit>? = null, textModifier: Modifier = Modifier) {
    // A null label is a later run of a cut text (wholeLineItems): no label, no top gap.
    Column(Modifier.fillMaxWidth().padding(top = if (label != null) 14.dp else 0.dp)) {
        if (label != null) TextMMD(text = label, style = MaterialTheme.typography.labelSmall)
        if (selectable) {
            SelectionContainer(Modifier.textActions()) {
                // A tap opens it in [open]'s app; a long press still selects it, as before.
                TextMMD(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (open != null) TextDecoration.Underline else null,
                    modifier = (if (open != null) Modifier.clickable(onClickLabel = open.first, onClick = open.second) else Modifier).then(textModifier),
                )
            }
        } else {
            TextMMD(text = value, style = MaterialTheme.typography.bodyMedium, modifier = textModifier)
        }
    }
}
