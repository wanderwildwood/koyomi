package com.wanderwildwood.koyomi.ui

import android.accounts.Account
import android.content.ContentResolver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.davsync.DavSync
import com.wanderwildwood.davsync.R as DavR
import com.wanderwildwood.davsync.accounts.Elsewhere
import com.wanderwildwood.davsync.accounts.StatusText
import com.wanderwildwood.davsync.signin.Provider
import com.wanderwildwood.davsync.model.Collection
import com.wanderwildwood.davsync.servicedetection.DavResourceFinder
import com.wanderwildwood.davsync.servicedetection.CollectionMaker
import com.wanderwildwood.davsync.sync.SyncPolicy
import com.wanderwildwood.davsync.signin.Problem
import com.wanderwildwood.davsync.signin.SignIn
import com.wanderwildwood.davsync.signin.SignInResult
import com.wanderwildwood.koyomi.AppModel
import com.wanderwildwood.koyomi.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Settings > Calendar server: Datebook's own sync with a CalDAV server, in place of DAVx5.
 *
 * With no account yet it is the typed sign-in, as in Memo: the server address, the user name
 * and an app password. Test asks the server and says in plain words what is wrong; Sign in does
 * the same and keeps the account when it is good. Nothing is saved before the server answers.
 * Once signed in, the account's calendars are listed, each with its own switch; Sync now asks
 * the phone to sync, and Remove takes the account and its calendars off the phone (the server
 * keeps everything).
 */
@Composable
fun ServerScreen(model: AppModel) {
    val context = LocalContext.current
    val davSync = remember { DavSync(context) }
    // AccountManager is not Compose state; a counter bumped after each change redraws the list.
    var version by remember { mutableIntStateOf(0) }
    val accounts = remember(version) { davSync.accounts.accounts() }
    var adding by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf<String?>(null) }
    // Held here, not in the row: a row scrolled off the page is thrown away, and with it a
    // check still asking the server.
    val passwordForm = remember { PasswordForm() }
    var making by remember { mutableStateOf<String?>(null) }
    val newForm = remember { NewForm() }
    val screenScope = rememberCoroutineScope()
    // Calendars DAVx5 (or any other account) already syncs here, by address.
    val elsewhere = remember(version) { Elsewhere.calendars(context, davSync.accounts.accountType) }
    // Calendars made or removed in Nextcloud since: read the list again each time the page opens.
    LaunchedEffect(accounts.size) {
        for (account in accounts)
            CollectionMaker.refresh(davSync, account, DavResourceFinder.Service.CALDAV)
        version++
    }
    // The framework runs the sync; its start and end redraw the "last synced" line.
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val handle = ContentResolver.addStatusChangeListener(
            ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE or ContentResolver.SYNC_OBSERVER_TYPE_PENDING,
        ) { main.post { version++ } }
        onDispose { ContentResolver.removeStatusChangeListener(handle) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.server_title)) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.cd_close)) { model.pop() } },
            )
        },
    ) { padding ->
        if (accounts.isEmpty() || adding) {
            SignInForm(davSync, Modifier.padding(padding)) { account ->
                davSync.accounts.enableSync(account, CalendarContract.AUTHORITY)
                davSync.accounts.requestSync(account, CalendarContract.AUTHORITY)
                adding = false
                version++
            }
        } else {
            PagedColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 20.dp, end = 12.dp),
                showCutRow = true,
            ) {
                for (account in accounts) {
                    item(key = "account-${account.name}") {
                        TextMMD(
                            text = account.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                        )
                    }
                    val calendars = davSync.accounts.collections(account, Collection.TYPE_CALENDAR).filter { it.supportsVEVENT != false }
                    item(key = "status-${account.name}") {
                        val syncing = ContentResolver.isSyncActive(account, CalendarContract.AUTHORITY)
                        TextMMD(
                            text = StatusText.of(context, davSync.accounts.status(account), syncing) +
                                (if (calendars.none { it.sync }) "\n" + stringResource(com.wanderwildwood.davsync.R.string.davsync_pick) else "") +
                                (if (calendars.any { Elsewhere.match(it, elsewhere) != Elsewhere.Match.NONE })
                                    "\n" + stringResource(com.wanderwildwood.davsync.R.string.davsync_moving_calendars) else ""),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    for (c in calendars) {
                        item(key = "cal-${account.name}-${c.id}") {
                            val twice = Elsewhere.match(c, elsewhere)
                            SwitchRow(
                                c.title(),
                                c.sync,
                                note = listOfNotNull(
                                    if (c.readOnly()) stringResource(R.string.server_read_only) else null,
                                    when (twice) {
                                        Elsewhere.Match.SURE -> stringResource(DavR.string.davsync_elsewhere_calendar)
                                        Elsewhere.Match.MAYBE -> stringResource(DavR.string.davsync_maybe_calendar)
                                        Elsewhere.Match.NONE -> null
                                    },
                                ).joinToString(" · ").ifEmpty { null },
                                compact = true,
                            ) { on ->
                                // Not twice: one DAVx5 surely syncs stays off here until it is off there.
                                if (on && twice == Elsewhere.Match.SURE) return@SwitchRow
                                davSync.accounts.setSync(account, c.id, on)
                                davSync.accounts.requestSync(account, CalendarContract.AUTHORITY)
                                version++
                            }
                        }
                    }
                    item(key = "new-${account.name}") {
                        if (making == account.name)
                            NewCalendar(davSync, account, newForm, screenScope) {
                                newForm.reset()
                                making = null
                                davSync.accounts.requestSync(account, CalendarContract.AUTHORITY)
                                version++
                            }
                        else
                            SettingRow(
                                stringResource(DavR.string.davsync_new_calendar),
                                stringResource(DavR.string.davsync_new_calendar_note),
                            ) { newForm.reset(); making = account.name }
                    }
                    item(key = "interval-${account.name}") {
                        val now = davSync.accounts.interval(account)
                        ChoiceRow(
                            label = stringResource(DavR.string.davsync_interval),
                            choices = listOf(
                                stringResource(DavR.string.davsync_interval_15m),
                                stringResource(DavR.string.davsync_interval_1h),
                                stringResource(DavR.string.davsync_interval_4h),
                            ),
                            chosen = SyncPolicy.INTERVALS.indexOf(now),
                            enabled = true,
                            perLine = 3,
                        ) {
                            davSync.accounts.setInterval(account, CalendarContract.AUTHORITY, SyncPolicy.INTERVALS[it])
                            version++
                        }
                    }
                    item(key = "wifi-${account.name}") {
                        SwitchRow(
                            stringResource(DavR.string.davsync_wifi_only),
                            davSync.accounts.wifiOnly(account),
                            note = stringResource(DavR.string.davsync_wifi_only_note),
                        ) { on ->
                            davSync.accounts.setWifiOnly(account, on)
                            version++
                        }
                    }
                    item(key = "sync-${account.name}") {
                        SettingRow(stringResource(R.string.server_sync_now), stringResource(R.string.server_sync_now_note)) {
                            davSync.accounts.requestSync(account, CalendarContract.AUTHORITY)
                        }
                    }
                    item(key = "password-${account.name}") {
                        if (changing == account.name)
                            ChangePassword(davSync, account, passwordForm, screenScope) { saved ->
                                passwordForm.reset()
                                changing = null
                                if (saved) davSync.accounts.requestSync(account, CalendarContract.AUTHORITY)
                                version++
                            }
                        else
                            SettingRow(
                                stringResource(com.wanderwildwood.davsync.R.string.davsync_change_password),
                                stringResource(com.wanderwildwood.davsync.R.string.davsync_change_password_note),
                            ) { changing = account.name }
                    }
                    item(key = "remove-${account.name}") {
                        ArmedRow(stringResource(R.string.server_remove), stringResource(R.string.server_remove_armed)) {
                            davSync.accounts.remove(account)
                            version++
                        }
                    }
                }
                item(key = "add") {
                    SettingRow(stringResource(R.string.server_add), null) { adding = true }
                }
            }
        }
    }
}

@Composable
private fun SignInForm(davSync: DavSync, modifier: Modifier, onSignedIn: (Account) -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Int?>(null) }
    var said by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(Provider.NEXTCLOUD) }
    var region by remember { mutableStateOf(Provider.ZOHO_REGIONS.first()) }

    fun choose(p: Provider, r: String = region) {
        val was = provider.address(DavResourceFinder.Service.CALDAV, region)
        provider = p; region = r
        val now = p.address(DavResourceFinder.Service.CALDAV, r)
        // A filled-in address follows the choice; a typed one is left alone.
        if (now != null) address = now else if (address == was) address = ""
        status = null
    }

    fun go(keep: Boolean) {
        busy = true
        status = R.string.server_checking
        scope.launch {
            val result = SignIn.check(davSync, address, user, password, DavResourceFinder.Service.CALDAV)
            busy = false
            said = (result as? SignInResult.Bad)?.detail.orEmpty()
            status = when (result) {
                is SignInResult.Good -> if (keep) {
                    val account = SignIn.keep(davSync, result)
                    if (account != null) {
                        onSignedIn(account)
                        null
                    } else R.string.server_not_kept
                } else R.string.server_good
                is SignInResult.Bad -> when (result.problem) {
                    Problem.BAD_ADDRESS -> R.string.server_bad_address
                    Problem.PLAIN_HTTP -> R.string.server_plain_http
                    Problem.NOT_FOUND -> R.string.server_not_found
                    Problem.UNREACHABLE -> R.string.server_unreachable
                    Problem.CERTIFICATE -> R.string.server_certificate
                    Problem.WRONG_PASSWORD -> R.string.server_wrong_password
                    Problem.NO_SERVICE -> R.string.server_no_caldav
                    Problem.NOTHING_FOUND -> R.string.server_no_calendars
                    Problem.KEYSTORE -> R.string.server_not_kept
                    Problem.OTHER -> R.string.server_other
                }
            }
        }
    }

    val ready = address.isNotBlank() && user.isNotBlank() && password.isNotEmpty() && !busy

    PagedColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 12.dp),
        showCutRow = true,
    ) {
        item(key = "why") {
            TextMMD(
                text = stringResource(R.string.server_why),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        item(key = "kind") {
            ChoiceRow(
                label = stringResource(com.wanderwildwood.davsync.R.string.davsync_kind),
                choices = Provider.entries.map { stringResource(it.label) },
                chosen = provider.ordinal,
                enabled = !busy,
            ) { choose(Provider.entries[it]) }
        }
        if (provider == Provider.ZOHO) item(key = "region") {
            ChoiceRow(
                label = stringResource(com.wanderwildwood.davsync.R.string.davsync_zoho_region),
                choices = Provider.ZOHO_REGIONS.map { ".$it" },
                chosen = Provider.ZOHO_REGIONS.indexOf(region),
                enabled = !busy,
                perLine = 3,
            ) { choose(Provider.ZOHO, Provider.ZOHO_REGIONS[it]) }
        }
        item(key = "note") {
            TextMMD(
                text = stringResource(provider.note),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item(key = "address") {
            TextFieldMMD(
                value = address,
                onValueChange = { address = it.replace("\n", "").trim(); status = null },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                label = { TextMMD(text = stringResource(R.string.server_address)) },
                placeholder = { TextMMD(text = "cloud.example.org") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            )
        }
        item(key = "user") {
            TextFieldMMD(
                value = user,
                onValueChange = { user = it.replace("\n", ""); status = null },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                label = { TextMMD(text = stringResource(R.string.server_user)) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            )
        }
        item(key = "password") {
            Column(Modifier.padding(top = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextFieldMMD(
                        value = password,
                        onValueChange = { password = it.replace("\n", ""); status = null },
                        modifier = Modifier.weight(1f),
                        label = { TextMMD(text = stringResource(R.string.server_password)) },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onGo = { if (ready) go(keep = true) }),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButtonMMD(onClick = { shown = !shown }, modifier = Modifier.widthIn(min = 72.dp).height(48.dp)) {
                        TextMMD(
                            text = stringResource(if (shown) R.string.server_hide else R.string.server_show),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                TextMMD(
                    text = stringResource(R.string.server_password_note),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        status?.let { s ->
            item(key = "status") {
                TextMMD(
                    text = stringResource(s) + if (said.isNotBlank()) "\n" + stringResource(com.wanderwildwood.davsync.R.string.davsync_said, said.take(300)) else "",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
        item(key = "buttons") {
            Column(Modifier.padding(top = 20.dp, bottom = 20.dp)) {
                ButtonMMD(onClick = { go(keep = true) }, enabled = ready, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    TextMMD(text = stringResource(R.string.server_sign_in))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButtonMMD(onClick = { go(keep = false) }, enabled = ready, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    TextMMD(text = stringResource(R.string.server_test))
                }
            }
        }
    }
}

/** A label over a row of plain buttons, one of them chosen (filled). [perLine] to a line. */
@Composable
private fun ChoiceRow(label: String, choices: List<String>, chosen: Int, enabled: Boolean, perLine: Int = 2, onChoose: (Int) -> Unit) {
    Column(Modifier.padding(top = 16.dp)) {
        TextMMD(text = label, style = MaterialTheme.typography.labelMedium)
        for (line in choices.indices.chunked(perLine)) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                for (i in line) {
                    val mod = Modifier.weight(1f).height(44.dp).padding(end = if (i == line.last()) 0.dp else 6.dp)
                    val pad = PaddingValues(horizontal = 4.dp)
                    if (i == chosen)
                        ButtonMMD(onClick = { onChoose(i) }, enabled = enabled, modifier = mod, contentPadding = pad) {
                            TextMMD(text = choices[i], style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    else
                        OutlinedButtonMMD(onClick = { onChoose(i) }, enabled = enabled, modifier = mod, contentPadding = pad) {
                            TextMMD(text = choices[i], style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                }
                repeat(perLine - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A new app password for a signed-in account, kept only once the server has taken it. */
@Composable
private fun ChangePassword(davSync: DavSync, account: Account, form: PasswordForm, scope: CoroutineScope, onDone: (Boolean) -> Unit) {
    var password by form::password
    var busy by form::busy
    var status by form::status
    Column(Modifier.padding(vertical = 8.dp)) {
        TextFieldMMD(
            value = password,
            onValueChange = { password = it.replace("\n", ""); status = null },
            modifier = Modifier.fillMaxWidth(),
            label = { TextMMD(text = stringResource(DavR.string.davsync_new_password)) },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        )
        status?.let { TextMMD(text = stringResource(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            OutlinedButtonMMD(onClick = { onDone(false) }, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp)) {
                TextMMD(text = stringResource(DavR.string.davsync_cancel))
            }
            Spacer(Modifier.width(8.dp))
            ButtonMMD(
                onClick = {
                    busy = true
                    status = DavR.string.davsync_checking
                    scope.launch {
                        val result = SignIn.changePassword(davSync, account, password, DavResourceFinder.Service.CALDAV)
                        busy = false
                        when {
                            result is SignInResult.Good -> onDone(true)
                            (result as SignInResult.Bad).problem == Problem.WRONG_PASSWORD -> status = DavR.string.davsync_password_refused
                            else -> status = DavR.string.davsync_password_unchecked
                        }
                    }
                },
                enabled = !busy && password.isNotEmpty(),
                modifier = Modifier.weight(1f).height(48.dp),
            ) { TextMMD(text = stringResource(DavR.string.davsync_save)) }
        }
    }
}

/** What the Change password row holds while it is open. */
private class PasswordForm {
    var password by mutableStateOf("")
    var busy by mutableStateOf(false)
    var status by mutableStateOf<Int?>(null)
    fun reset() { password = ""; busy = false; status = null }
}

/** The colors a new calendar may take on the server (this app draws every calendar in black). */
private val CALENDAR_COLORS = listOf(
    DavR.string.davsync_color_blue to 0xFF0082C9.toInt(),
    DavR.string.davsync_color_green to 0xFF46BA61.toInt(),
    DavR.string.davsync_color_red to 0xFFE9322D.toInt(),
    DavR.string.davsync_color_orange to 0xFFF39C12.toInt(),
    DavR.string.davsync_color_purple to 0xFF8E44AD.toInt(),
    DavR.string.davsync_color_gray to 0xFF767676.toInt(),
)

/** A new calendar, made on the server and turned on here. */
@Composable
private fun NewCalendar(davSync: DavSync, account: Account, form: NewForm, scope: CoroutineScope, onDone: () -> Unit) {
    var name by form::name
    var color by form::color
    var busy by form::busy
    var status by form::status
    Column(Modifier.padding(vertical = 8.dp)) {
        TextFieldMMD(
            value = name,
            onValueChange = { name = it.replace("\n", ""); status = null },
            modifier = Modifier.fillMaxWidth(),
            label = { TextMMD(text = stringResource(DavR.string.davsync_new_name)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        ChoiceRow(
            label = stringResource(DavR.string.davsync_color),
            choices = CALENDAR_COLORS.map { stringResource(it.first) },
            chosen = color,
            enabled = !busy,
            perLine = 3,
        ) { color = it }
        status?.let { TextMMD(text = stringResource(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            OutlinedButtonMMD(onClick = onDone, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp)) {
                TextMMD(text = stringResource(DavR.string.davsync_cancel))
            }
            Spacer(Modifier.width(8.dp))
            ButtonMMD(
                onClick = {
                    busy = true
                    status = DavR.string.davsync_creating
                    scope.launch {
                        val result = CollectionMaker.create(
                            davSync, account, DavResourceFinder.Service.CALDAV, name.trim(), CALENDAR_COLORS[color].second,
                        )
                        busy = false
                        when (result) {
                            is CollectionMaker.Result.Made -> onDone()
                            CollectionMaker.Result.NoHome -> status = DavR.string.davsync_create_no_home
                            CollectionMaker.Result.Password -> status = DavR.string.davsync_create_password
                            CollectionMaker.Result.Unreachable -> status = DavR.string.davsync_create_unreachable
                            is CollectionMaker.Result.Refused -> status = DavR.string.davsync_create_refused
                        }
                    }
                },
                enabled = !busy && name.isNotBlank(),
                modifier = Modifier.weight(1f).height(48.dp),
            ) { TextMMD(text = stringResource(DavR.string.davsync_create)) }
        }
    }
}

/** What the New calendar row holds while it is open (held above the row, as [PasswordForm]). */
private class NewForm {
    var name by mutableStateOf("")
    var color by mutableIntStateOf(0)
    var busy by mutableStateOf(false)
    var status by mutableStateOf<Int?>(null)
    fun reset() { name = ""; color = 0; busy = false; status = null }
}

