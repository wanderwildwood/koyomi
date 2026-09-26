package com.wanderwildwood.koyomi

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.koyomi.alerts.Alerts
import com.wanderwildwood.koyomi.ui.EditScreen
import com.wanderwildwood.koyomi.ui.EventScreen
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
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openFrom(intent)
        // Catch anything due while the app was away, and set the next alarm.
        thread { Alerts.check(applicationContext) }
        setContent {
            ThemeMMD(colorScheme = monochrome) {
                Koyomi(model)
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
    }

    /** A reminder that was pressed opens its event. */
    private fun openFrom(intent: Intent?) {
        val id = intent?.getLongExtra(Alerts.EXTRA_EVENT, -1) ?: -1
        if (id < 0) return
        val begin = intent!!.getLongExtra(Alerts.EXTRA_BEGIN, 0)
        val end = intent.getLongExtra(Alerts.EXTRA_END, 0)
        model.popAll()
        model.push(Screen.Event(id, begin, end))
        thread { Alerts.dismiss(applicationContext, id, begin) }
        intent.removeExtra(Alerts.EXTRA_EVENT)
    }
}

@Composable
private fun Koyomi(model: AppModel) {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        model.permissionsChanged()
    }
    if (!model.canRead) {
        PermissionScreen {
            ask.launch(
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR,
                    "android.permission.POST_NOTIFICATIONS",
                ),
            )
        }
        return
    }

    BackHandler(enabled = model.stack.isNotEmpty()) { model.pop() }

    when (val top = model.stack.lastOrNull()) {
        null -> ViewsScreen(model)
        is Screen.Event -> EventScreen(model, top)
        is Screen.Edit -> EditScreen(model, top)
        Screen.Repeat -> RepeatScreen(model)
        Screen.Search -> SearchScreen(model)
        Screen.Settings -> SettingsScreen(model)
    }
}
