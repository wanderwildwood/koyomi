# Privacy

Calendar reads and writes the calendars already on the phone, and has no way to reach the
network.

That is the whole policy. The rest of this page is the evidence for it, because a privacy
policy that cannot be checked is just a promise.

## Permissions

`app/src/main/AndroidManifest.xml` declares these, and no others:

- `READ_CALENDAR` and `WRITE_CALENDAR` — the phone's calendar store, which is the whole
  point of the app.
- `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `SCHEDULE_EXACT_ALARM` and
  `RECEIVE_BOOT_COMPLETED` — for reminders: to show one, to show it on the whole screen, to
  wake on time for it, and to set the next one again after the phone restarts.

There is no `INTERNET` permission. Without it Android will not let the app open a network
connection, so nothing in your calendar could leave the phone through it even by accident.
Syncing with a server is done by DAVx5, or by whichever account put the calendar on the
phone, under that app's own permissions.

## What is stored

The events themselves live in the phone's calendar store, not in this app. The app keeps
six settings in `SharedPreferences`, all visible in `data/Settings.kt`: which view it opens
on, the first day of the week, whether week numbers show, the calendar and the reminder a new
event starts with, and whether a reminder fills the screen.

## No analytics

No crash reporting, no telemetry, no advertising identifier, no third-party SDK. The
dependency list in `app/build.gradle.kts` is AndroidX, Jetpack Compose and Mudita's MMD
component library, and nothing else.

## Checking for yourself

```
aapt2 dump badging app-release.apk | grep uses-permission
```

That prints every permission the built app actually carries.

Besides the six above it prints one more:

```
uses-permission: name='com.wanderwildwood.koyomi.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

That one is not mine and is not a permission in the sense you care about. AndroidX defines
it automatically for every app; it is a signature-level permission scoped to this package,
which only this app can hold, and it grants access to nothing.
