# Privacy

Datebook reads and writes the calendars already on the phone. It reaches the network only to
fetch the calendar links you add yourself, and uses the camera only while you scan a QR code
for one.

That is the whole policy. The rest of this page is the evidence for it, because a privacy
policy that cannot be checked is just a promise.

## Permissions

`app/src/main/AndroidManifest.xml` declares these, and no others:

- `READ_CALENDAR` and `WRITE_CALENDAR` — the phone's calendar store, which is the whole
  point of the app.
- `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `SCHEDULE_EXACT_ALARM` and
  `RECEIVE_BOOT_COMPLETED` — for reminders: to show one, to show it on the whole screen, to
  wake on time for it, and to set the next one again after the phone restarts.

- `INTERNET` — only to fetch the calendar links you add (Settings → Add calendar link): a plain
  download of that address, when the app opens and when you pull down in Settings. Nothing from
  your calendars is sent; the only thing that leaves the phone is the request for the link. There is
  no background fetching, and no other address is ever contacted (`data/Subscriptions.kt` is the
  only network code).
- `CAMERA` — only while the QR scanner is open, to read a calendar link from a code. Nothing is
  recorded or saved, and the camera is let go when the scanner closes.

Syncing a server account is still done by DAVx5, or by whichever account put the calendar on the
phone, under that app's own permissions.

## Calendar links

A link such as Google's "secret address" lets anyone who has it read that calendar, so it is
treated as a secret. It is kept in the app's private storage (`subscriptions` preferences), is
excluded from backup and device transfer (`res/xml/backup_rules.xml`,
`data_extraction_rules.xml`), is never written to a log, and is shown on screen only as the
server's name. Only `https://` is used (`webcal://` is read as `https://`); `http://` is refused.
Removing a followed calendar deletes that calendar and its events from the phone.

**Save the calendar links** (Settings) writes the followed calendars' names and addresses to a
plain text file you choose through the phone's file picker, and only when you ask, so a new
phone can follow them again. That file holds the secrets: keep it somewhere private. **Follow
the links in a saved file** reads such a file the same way, checks all of it first, and only
adds calendars; it removes nothing.

## What is stored

The events themselves live in the phone's calendar store, not in this app. The app keeps
six settings in `SharedPreferences`, all visible in `data/Settings.kt`: which view it opens
on, the first day of the week, whether week numbers show, the calendar and the reminder a new
event starts with, and whether a reminder fills the screen.

## The lock screen

If the Glance app is installed and its lock-screen panel is on, Datebook hands it today's events
- their titles and times - to show on the lock screen, where anyone holding the phone can read
them. It answers Glance alone, through a provider any other app is refused by
(`glance/GlanceProvider.kt`), and hands over nothing while **Settings → Today's events on the
lock screen** is off. Nothing leaves the phone.

## No analytics

No crash reporting, no telemetry, no advertising identifier, no third-party SDK. The
dependency list in `app/build.gradle.kts` is AndroidX (Jetpack Compose, CameraX), Mudita's MMD
component library and ZXing (which reads the QR code on the phone, with no Google services), and
nothing else.

## Checking for yourself

```
aapt2 dump badging app-release.apk | grep uses-permission
```

That prints every permission the built app actually carries.

Besides the permissions above it prints one more:

```
uses-permission: name='com.wanderwildwood.koyomi.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
```

That one is not mine and is not a permission in the sense you care about. AndroidX defines
it automatically for every app; it is a signature-level permission scoped to this package,
which only this app can hold, and it grants access to nothing.
