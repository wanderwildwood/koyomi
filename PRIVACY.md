# Privacy

Datebook reads and writes the calendars already on the phone. It reaches the network only to
fetch the calendar links you add yourself and, if you sign in to your own calendar server, to
sync with that server. It uses the camera only while you scan a QR code for a link.

That is the whole policy. The rest of this page is the evidence for it, because a privacy
policy that cannot be checked is just a promise.

## Permissions

`app/src/main/AndroidManifest.xml` declares these, and no others:

- `READ_CALENDAR` and `WRITE_CALENDAR` — the phone's calendar store, which is the whole
  point of the app.
- `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `SCHEDULE_EXACT_ALARM` and
  `RECEIVE_BOOT_COMPLETED` — for reminders: to show one, to show it on the whole screen, to
  wake on time for it, and to set the next one again after the phone restarts. The restart
  permission also keeps the few-hourly fetch of calendar links scheduled.

- `INTERNET` — for two things only:
  - the calendar links you add (Settings → Add calendar link): a plain download of that address,
    every few hours, when the app opens and when you pull down in Settings. Nothing from your calendars is sent;
    the only thing that leaves the phone is the request for the link (`data/Subscriptions.kt`, `data/LinkRefresh.kt`).
  - your own calendar server, once you sign in to it (Settings → Calendar server): the calendars
    you chose are kept the same on the phone and on that server, in both directions, hourly, after
    each change and when you ask. That server, the one whose address you typed, is the only one
    contacted (`davsync/`, see below).
- `READ_SYNC_SETTINGS`, `WRITE_SYNC_SETTINGS`, `READ_SYNC_STATS` — to turn on and read the sync
  of Datebook's own calendar account. They give no access to anything else.
- `CAMERA` — only while the QR scanner is open, to read a calendar link from a code. Nothing is
  recorded or saved, and the camera is let go when the scanner closes.

## Your calendar server

Signing in (Settings → Calendar server) asks for the server's address, your user name and a
password; an app password made for this phone is best, where the server has them. Nothing is
kept until the server has answered. The password is then sealed with a key in the phone's
keystore, which cannot be exported, and kept only in the phone's own account store, which no
other app can read; it is sent only to that server's domain, and over `https://` unless the
address is on your home network or a VPN (a private or Tailscale address). The account appears
in the phone's settings under Accounts, and Remove account takes it and its calendars off the
phone; the server keeps everything.

The sync code is DAVx5's (bitfire, GPL-3.0), copied into this app in `dav4jvm/`, `synctools/`
and `davsync/`, not a separate app or service. If an event was changed on the phone and on the
server at once, the server's version is kept and the phone's is saved beside it as "(phone's
copy)", so nothing is lost.

Calendars from DAVx5, or from whichever account put them on the phone, are still synced by that
app, under its own permissions.

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

<!-- shared-about privacy section -->
## Who we are

Datebook is made by wander wildwood, an independent developer: one person, not a company. wander wildwood is the data controller for anything the app itself handles, and that is very little. Datebook keeps what you put in it on your phone, apart from what it sends to the services whose calendar links you add (Google Calendar, for example) as described above, and sends nothing to the developer, so the developer holds no data about you.

## How to contact us

Write to email@wanderthe.dev about your data, this policy or anything else. The same address is in the app, under About.

## Your rights

If you are in the EU, the EEA or the UK, the GDPR gives you the right to:

- see the personal data held about you (access)
- have it corrected (rectification) or deleted (erasure)
- limit how it is used (restriction)
- object to its use (objection)
- get a copy to take elsewhere (portability)
- withdraw your consent at any time, where something rests on it
- complain to a data protection supervisory authority, for example the one where you live

Similar laws elsewhere, such as the CCPA in California, give you the right to know, delete and correct, and to opt out of the sale or sharing of your data. Nothing from Datebook is sold or shared.

Since Datebook keeps what it stores only on your phone, you can exercise most of these rights yourself: change or delete events in the app, remove a followed calendar link or your server account in Settings, clear the app's data in Android's settings, or uninstall the app, which deletes all of it. For anything you cannot do yourself, write to the address above.

Datebook connects to the services whose calendar links you add (Google Calendar, for example). They are separate from this app and from its developer, and what they receive is covered by their own privacy policies; ask them about what they hold.
If you point Datebook at a server of your own, what that server keeps is yours to manage.
<!-- /shared-about privacy section -->
