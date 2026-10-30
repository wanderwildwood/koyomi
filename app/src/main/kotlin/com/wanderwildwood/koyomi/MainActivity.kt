package com.wanderwildwood.koyomi

import com.wanderwildwood.koyomi.ui.backTarget
import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.koyomi.alerts.Alerts
import com.wanderwildwood.koyomi.ui.EditScreen
import com.wanderwildwood.koyomi.ui.EventScreen
import com.wanderwildwood.koyomi.ui.ImportScreen
import com.wanderwildwood.koyomi.ui.PermissionScreen
import com.wanderwildwood.koyomi.ui.RepeatScreen
import com.wanderwildwood.koyomi.ui.SearchScreen
import com.wanderwildwood.koyomi.ui.SettingsScreen
import com.wanderwildwood.koyomi.ui.ViewsScreen
import com.wanderwildwood.koyomi.ui.monochrome
import kotlin.concurrent.thread

class KoyomiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Alerts.createChannel(this)
    }
}

class MainActivity : ComponentActivity() {
    private companion object {
        const val AWAY_MS = 5 * 60 * 1000L
    }

    private val model: AppModel by viewModels()
    private val turner = TurnWithPhone(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        turner.set(model.settings.turnWithPhone)
        openFrom(intent)
        // Catch anything due while the app was away, and set the next alarm.
        thread { Alerts.check(applicationContext) }
        setContent {
            // A steady cursor: a blinking one is a repaint twice a second on an e-ink panel
            // for as long as a field has focus.
            CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
                // Read again whenever a setting changes, so the switch takes effect at once.
                LaunchedEffect(model.settingsVersion) { turner.set(model.settings.turnWithPhone) }
                // On its side the status bar goes, for the height; a swipe from the edge shows
                // it for a moment. Upright it is back. Read again on every turn and every
                // fresh start, so a recreated or resumed activity gets it right.
                val sideways = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                DisposableEffect(sideways) {
                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (sideways) controller.hide(WindowInsetsCompat.Type.statusBars())
                    else controller.show(WindowInsetsCompat.Type.statusBars())
                    onDispose { }
                }
                ThemeMMD(colorScheme = monochrome) {
                    Koyomi(model)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    override fun onResume() {
        super.onResume()
        model.permissionsChanged()
        turner.resume()
    }

    override fun onPause() {
        turner.pause()
        super.onPause()
    }

    /** When the app was last left; 0 while it is in front. */
    private var leftAt = 0L

    override fun onStop() {
        super.onStop()
        leftAt = SystemClock.elapsedRealtime()
    }

    // Opening Calendar again after a while away starts on the view chosen in Settings, at today,
    // as it does after a fresh start. A quick look elsewhere and back keeps the place.
    override fun onStart() {
        super.onStart()
        if (leftAt > 0 && SystemClock.elapsedRealtime() - leftAt >= AWAY_MS) model.reopen()
        leftAt = 0L
    }

    /** A reminder that was pressed opens its event. */
    private fun openFrom(intent: Intent?) {
        val id = intent?.getLongExtra(Alerts.EXTRA_EVENT, -1) ?: -1
        if (id < 0) return
        val begin = intent!!.getLongExtra(Alerts.EXTRA_BEGIN, 0)
        val end = intent.getLongExtra(Alerts.EXTRA_END, 0)
        model.popAll()
        model.push(Screen.Event(id, begin, end))
        val kind = com.wanderwildwood.koyomi.data.AllDayKind.of(intent.getIntExtra(Alerts.EXTRA_KIND, 0))
        thread { Alerts.dismiss(applicationContext, id, begin, kind) }
        intent.removeExtra(Alerts.EXTRA_EVENT)
    }
}

/**
 * The app's screens. [finish] is given where another app asked for one thing — a new event,
 * one event, a file — and is called once that is done, to go back to the app that asked.
 */
@Composable
internal fun Koyomi(model: AppModel, finish: (() -> Unit)? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var denied by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.permissionsChanged()
        denied = !model.canRead
    }
    if (!model.canRead) {
        PermissionScreen(
            denied = denied,
            onAllow = {
                ask.launch(
                    arrayOf(
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR,
                        "android.permission.POST_NOTIFICATIONS",
                    ),
                )
            },
            onOpenSettings = {
                runCatching {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")),
                    )
                }
            },
        )
        return
    }

    BackHandler(enabled = model.stack.isNotEmpty()) { model.pop() }
    BackHandler(enabled = model.stack.isEmpty() && backTarget(model.view) != null) { model.backToMonth() }

    when (val top = model.stack.lastOrNull()) {
        null -> if (finish == null) {
            ViewsScreen(model)
        } else if (model.opened) {
            LaunchedEffect(Unit) { finish() }
        }
        is Screen.Event -> EventScreen(model, top)
        is Screen.Edit -> EditScreen(model, top)
        Screen.Repeat -> RepeatScreen(model)
        Screen.Search -> SearchScreen(model)
        Screen.Settings -> SettingsScreen(model)
        is Screen.Import -> ImportScreen(model, top)
    }
}
