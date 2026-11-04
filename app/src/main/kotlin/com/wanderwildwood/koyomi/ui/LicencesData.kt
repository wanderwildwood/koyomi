package com.wanderwildwood.koyomi.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wanderwildwood.koyomi.R

/** The address of the app's own source code. */
const val SOURCE_URL = "https://github.com/wanderwildwood/koyomi"

/** What the APK carries that is not this app's own code, and the licence each project declares. */
val LICENCE_COMPONENTS: List<LicenceComponent> = listOf(
    LicenceComponent("Mudita Mindful Design (MMD)", "Mudita", "Apache-2.0", "https://github.com/mudita/MMD"),
    LicenceComponent("Lato typeface, inside MMD", "tyPoland Lukasz Dziedzic", "OFL-1.1", "https://www.latofonts.com"),
    LicenceComponent("Jetpack Compose (UI, Foundation, Material 3), Activity, Lifecycle, Core", "Google", "Apache-2.0", "https://developer.android.com/jetpack"),
    LicenceComponent("Kotlin standard library and coroutines", "JetBrains", "Apache-2.0", "https://kotlinlang.org"),
    LicenceComponent("KompaktCalendar, the app this began from", "David Anderlohr", "GPL-3.0", "https://codeberg.org/davidanderlohr/KompaktCalendar"),
    LicenceComponent("Etar Calendar, whose repeat rules and saving of a changed occurrence this follows", "the Etar authors", "GPL-3.0", "https://github.com/Etar-Group/Etar-Calendar"),
    LicenceComponent("Android Open Source Project repeat-rule parser (calendarcommon2)", "The Android Open Source Project", "Apache-2.0", "https://source.android.com"),
    LicenceComponent("ZXing (reads the QR code of a calendar link)", "the ZXing authors", "Apache-2.0", "https://github.com/zxing/zxing"),
    LicenceComponent("CameraX (the camera while the scanner is open)", "Google", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/camera"),
    LicenceComponent("Material Symbols icons", "Google", "Apache-2.0", "https://fonts.google.com/icons"),
    LicenceComponent("dav4jvm, the WebDAV, CalDAV and CardDAV client (calendar and contacts sync)", "bitfire web engineering", "MPL-2.0", "https://github.com/bitfireAT/dav4jvm"),
    LicenceComponent("DAVx5's synctools and sync code, which the server sync is adapted from", "bitfire web engineering and the DAVx5 contributors", "GPL-3.0", "https://github.com/bitfireAT/davx5-ose"),
    LicenceComponent("iCal4j (reads and writes calendar files)", "Ben Fortuna", "BSD-3-Clause", "https://github.com/ical4j/ical4j"),
    LicenceComponent("ez-vcard (reads and writes contact cards)", "Michael Angstadt", "BSD-2-Clause", "https://github.com/mangstadt/ez-vcard"),
    LicenceComponent("Ktor client and OkHttp (the network connection to your server)", "JetBrains; Square", "Apache-2.0", "https://ktor.io"),
    LicenceComponent("Guava, Apache Commons Lang and Codec", "Google; the Apache Software Foundation", "Apache-2.0", "https://github.com/google/guava"),
    LicenceComponent("SLF4J (logging inside iCal4j)", "QOS.ch", "MIT", "https://www.slf4j.org"),
)

/** Each distinct licence text once: family id and the raw resource. */
val LICENCE_RAW: List<Pair<String, Int>> = listOf(
    "gpl" to R.raw.gpl_3_0,
    "apache" to R.raw.apache_2_0,
    "ofl" to R.raw.ofl_1_1,
    "mpl" to R.raw.mpl_2_0,
    "bsd" to R.raw.bsd_3_clause,
    "mit" to R.raw.mit,
)

@Composable
fun rememberLicencesData(): LicencesData = LicencesData(
    appName = "Datebook",
    sourceUrl = SOURCE_URL,
    licenceLine = stringResource(R.string.licences_licence),
    components = LICENCE_COMPONENTS,
    texts = LICENCE_RAW.map { (id, raw) ->
        LicenceText(id, stringResource(when (id) {
            "gpl" -> R.string.licences_full_gpl
            "apache" -> R.string.licences_full_apache
            "ofl" -> R.string.licences_full_ofl
            "mpl" -> R.string.licences_full_mpl
            "bsd" -> R.string.licences_full_bsd
            "mit" -> R.string.licences_full_mit
            else -> R.string.licences_full_head
        }), raw)
    },
)

@Composable
fun rememberLicencesLabels(): LicencesLabels {
    val none = stringResource(R.string.about_no_browser_address, "@@")
    val noText = stringResource(R.string.licences_no_text, "@@")
    return LicencesLabels(
        title = stringResource(R.string.licences_title),
        back = stringResource(R.string.licences_back),
        sourceHeading = stringResource(R.string.licences_source_head),
        noText = { licence -> noText.replace("@@", licence) },
        noBrowser = { address -> none.replace("@@", address) },
    )
}
