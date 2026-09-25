# Vitara Routine

A Kotlin routine app for Android 8.0 – Android 14 that rings like a real alarm clock:
sound + vibration, a **full screen takeover**, and it only stops when the user presses
**DISMISS** or **SNOOZE** (5 minutes).

> "Wake up 04:00 → alarm/vibration/notification, 04:15–05:00 study → ring when study
> ends, 05:00–06:00 rest → ring when it starts." Every routine block rings at its start
> and (optionally) when it ends, so the day keeps reminding you.

## The core idea (no compromise)

| Requirement | How it is implemented |
|---|---|
| Sound **plus** vibration | `RingService` (foreground, `mediaPlayback`) loops a `MediaPlayer` on `AudioAttributes.USAGE_ALARM` and a repeating `VibrationEffect`. It plays on the phone's **alarm stream**, so the volume buttons decide how loud it is — the app never overrides your volume. |
| Full screen takeover | `RingActivity` (`singleInstance`, own task, `showWhenLocked` + `turnScreenOn`) launched by a full screen intent notification. While the phone is **unlocked and you are using another app**, Android drops background activity starts, so the app also asks for **"display over other apps"** (`SYSTEM_ALERT_WINDOW`) — the platform's own exemption (`BackgroundActivityStartController` → `BAL_ALLOW_SAW_PERMISSION`). Blocked starts are detected and retried, and a log line tells you which permission is missing. |
| Only Dismiss ends it | The notification is `ongoing`, the ringing screen ignores the back gesture, there is no auto-timeout, and only the two deliberate buttons react: **SNOOZE** (5 minutes, then it rings again) and **DISMISS**. |
| Impossible to sleep through | `RingService` keeps a 60 second ringing window and re-posts the alert up to **4 times, 5 minutes apart**, so an alarm that was ignored still comes back instead of disappearing. |
| Vibration that never fades | A repeating `VibrationEffect` waveform is reinforced by an 8 second keep-alive loop, so aggressive vendor battery managers cannot silence an alarm that is still ringing. |
| Exact, on time | `AlarmManager.setAlarmClock()` — exact, survives Doze, shows the alarm icon; falls back to `setAndAllowWhileIdle()` if the exact-alarm permission was revoked. |
| Survives reboots | `BootReceiver` re-arms everything on boot, app update, clock/timezone change and when the exact-alarm permission is granted. The app also re-arms on every launch. |
| Nothing missed in the background | The audible notification is posted by the receiver itself (channel rings + vibrates), so even when Android refuses a background foreground-service start the alarm still alerts and its full screen intent takes over the screen. |

## Palette (exactly as specified, nowhere substituted)

`#7bdff2` Frosted Blue · `#b2f7ef` Icy Aqua · `#eff7f6` Mint Cream · `#f7d6e0` Petal Frost · `#f2b5d4` Blush Pop

Used in the theme (`res/values/themes.xml`), the header/ring gradients, the routine
template chips in the editor, the notification accent colour and the launcher icon.
The Kotlin mirror lives in `data/RoutineBlock.kt` → `enum class PaletteColor`.

## Build the APK

**Android Studio** – open the `vitararoutine` folder, wait for the Gradle sync, then
*Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)*. The debug APK lands in
`app/build/outputs/apk/debug/app-debug.apk`.
For a shareable release build: *Build ▸ Generate Signed App Bundle / APK ▸ APK*.

**Command line** (JDK 17, wrapper included):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
.\gradlew.bat assembleDebug      # -> app\build\outputs\apk\debug\app-debug.apk
```

Toolchain: AGP 8.5.2 · Kotlin 1.9.24 · Gradle 8.7 · compileSdk/targetSdk 34 · minSdk 26.

## Interface

Apple-style "liquid glass": a soft mint/teal gradient with translucent bokeh circles,
frosted cards and dark slate pill buttons, 12-hour times everywhere and a deliberately
**icon-free, emoji-free interface** — hierarchy comes from type, weight and colour only.

* **Home** – big live clock and a *Settings* pill, a **NEXT** card with the countdown, the
  routine list (time window, title, `DONE` / `NOW` / `OFF`, on-off switch, ⋮ menu) and a
  **New routine** pill. One compact row appears above the list whenever an alarm
  permission is still missing, and disappears on its own once everything is granted.
* **Settings** – the permission cards with a *Grant* button each, the alarm-volume note,
  the *10 Hours Study* template (**Use this template**, every day or Mon & Tue) and the
  app version. With nothing left to grant, the whole permissions section is hidden.
* **Editor** – glass sections: routine name and message, *Start from* template chips
  (each chip is tinted with the palette it applies), time window, repeat days and the
  alarm switches. No icon or colour pickers: the chips carry the colour, the typography
  carries the identity.
* **Ringing** – full screen glass takeover with the routine title, the live clock, the
  message and one range pill, then **SNOOZE** and **DISMISS** as two clearly separated
  full width buttons.

## First run checklist (in-app banners guide you)

1. **Notifications** – required, alarms are notifications.
2. **Display over other apps** – *this is what makes the alarm screen appear while you
   are using another app.* Without it Android only lets the ringing screen come up when
   the app is already open or the phone is locked/asleep. After granting it, leave the app
   with the back button and your next alarm will take over the screen anyway.
3. **Exact alarms** – Settings ▸ Special app access ▸ Alarms & reminders.
4. **Full screen alarms** – Android 14 only pre-grants this to alarm apps; the card opens *Settings ▸ Special app access ▸ Manage full screen intents*.

The settings screen lists anything still missing (and hides the section completely once
nothing is left), while the home screen keeps one compact *Finish alarm setup* row that
opens Settings.

### Trigger an alarm without touching the phone (debug APK only)

The debug build ships an extra receiver (it is **not** part of release builds) so you
can prove the whole chain from a terminal:

```powershell
# ring now
adb shell am broadcast -a com.vitara.routine.DEBUG_RING -n com.vitara.routine/.alarm.DebugRingReceiver

# end it (same as pressing DISMISS)
adb shell am broadcast -a com.vitara.routine.DEBUG_RING --ez dismiss true -n com.vitara.routine/.alarm.DebugRingReceiver
```

What a healthy run looks like (`adb shell dumpsys activity services com.vitara.routine`):

```
isForeground=true foregroundId=990 types=0x00000002 foregroundNoti=Notification(
  channel=vitara_routine_alarms flags=ONGOING_EVENT|ONLY_ALERT_ONCE|FOREGROUND_SERVICE|HIGH_PRIORITY
  color=0xffb2f7ef category=alarm actions=1 vis=PUBLIC)
```

`types=0x00000002` is `mediaPlayback`, and the notification carries both
`fullscreenIntent` and the `Dismiss` action.

**How it behaves on every Android version.** `setAlarmClock()` fires the receiver, which
immediately posts the ringing notification (so it cannot be missed) and then starts the
ringing service. From Android 12 upward the system may refuse a foreground-service start
that comes from the background — when that happens the notification's channel sound +
vibration and its full screen intent alert you, the full screen screen starts the service
from the foreground (always allowed), and the loop continues until you press DISMISS.

## Troubleshooting: the alarm screen only appears while the app is open

That is Android's background-activity-start restriction, not a bug in the ring code.
`RingActivity` is started from a background receiver, and the framework silently drops
that unless it can see a reason to allow it:

* **Phone locked or asleep** → the notification's full screen intent is enough.
* **Phone unlocked, another app in front** → the app must hold **"display over other
  apps"** (`SYSTEM_ALERT_WINDOW`). Android's own `BackgroundActivityStartController`
  grants the exemption as `BAL_ALLOW_SAW_PERMISSION`.

Grant it from the home-screen card (*Allow alarms over other apps → Grant*), or via adb
while developing:

```powershell
adb shell appops set com.vitara.routine SYSTEM_ALERT_WINDOW allow   # reproduce the working case
adb shell appops set com.vitara.routine SYSTEM_ALERT_WINDOW ignore  # reproduce the broken case
```

`RingController` verifies that the ringing screen really appeared, retries twice, and then
logs exactly which permission is missing, for example:

```
W RingController: Alarm screen could not take over the screen ("Wake up").
  Grant "display over other apps" so alarms can appear over the current app.
  Sound and vibration are still ringing; the notification's Dismiss button ends the alarm.
```

## Times are 12-hour

The picker shows **AM/PM** and every label, list and notification prints times the same
way (`10:40 PM`), so what you set is what you read back.

Because a 24-hour picker was the most common way to end up with a silent alarm, two
safeguards are now built in:

* every routine lists **"Next: starts today 10:40 PM · in 2 m"** — the exact next alarm the
  system has armed for it, so a wrong AM/PM is visible immediately;
* when you save a routine whose start time has already passed today you get a dialog:
  *"First alarm is tomorrow — 10:40 PM has already passed today…"* with a **Ring it now**
  button.

## Troubleshooting: an alarm did not ring

Check, in this order (all of it is visible in the app except the log lines):

1. **Was the time in the past?** The home screen's list shows `DONE`, `NOW` or the weekdays;
   the NEXT card always shows the next ring.
2. **Is the routine switched on?** The switch on its row shows `OFF` when disabled.
3. **Permissions** – the row above the routine list (and every card in Settings). No
   notifications = no sound when the app is closed; no exact alarms = the ring can be
   late; no "display over other apps" = no screen takeover while you are in another app.
4. **Logcat** – every firing and arming is logged:

```powershell
adb logcat -s AlarmScheduler:I AlarmReceiver:I RingController:W RingService:W
# I AlarmScheduler: Armed "Wake up" START for Sep 24, 2026 11:08:00 PM (exact)
# I AlarmReceiver:  Firing START of "Live test" (test=false)
```

* `(inexact - grant "Alarms & reminders")` in the arming line means the ring may be minutes
  late — grant *Alarms & reminders* for the app.
* Nothing in the log at all means the alarm was never armed for that moment (see step 1).

## Project layout

```
app/src/main/java/com/vitara/routine/
├─ VitaraApp.kt                  Application: channels + re-arm on launch, app visibility
├─ alarm/
│  ├─ AlarmScheduler.kt          setAlarmClock / setAndAllowWhileIdle, cancel, test alarm
│  ├─ AlarmReceiver.kt           arms the next occurrence, then rings
│  ├─ AlarmActionReceiver.kt     Snooze / Dismiss buttons on the notification
│  ├─ BootReceiver.kt            re-arm after reboot / update / time change
│  ├─ AlarmNotifications.kt      channel + ongoing alarm notification + full screen intent
│  ├─ RingService.kt             looping sound + vibration, 60 s ring window, retries
│  └─ RingController.kt          single entry point: notify → service → ringing screen
├─ data/
│  ├─ RoutineBlock.kt            model + palette + JSON
│  ├─ RoutineRepository.kt       SharedPreferences store, seeds Wake up/Study/Rest
│  ├─ LegacyIcons.kt             maps the emoji of older stores to icon keys (data only)
│  └─ Schedule.kt                next occurrence, today's events (overnight safe)
├─ ui/
│  ├─ MainActivity.kt            next-up countdown, routines, permissions, add button
│  ├─ BlockEditActivity.kt       templates, time window, days, sound, switches
│  ├─ RingActivity.kt            full screen alarm, Snooze + Dismiss
│  ├─ SettingsActivity.kt        permission cards, template, about
│  ├─ AlarmSetup.kt              which capability is still missing, and how to grant it
│  └─ BlockAdapter.kt            routine rows and their ⋮ menu
└─ util/TimeText.kt              time/day/countdown formatting
```

## Choices worth knowing

* **Storage** – JSON in `SharedPreferences` (`RoutineRepository`), no database and no
  annotation processors, so the build stays fast and the whole app is one module.
* **Ring replacement** – if two events land on the same minute (Study ends 05:00, Rest
  starts 05:00) the newest takes over; there is never more than one alarm screen.
* **Sound** – default system alarm sound unless you pick another one (device ringtone
  list or any audio file via the document picker, persisted across reboots).
* **Block length** – an end time earlier than the start time means the block runs past
  midnight (22:00 → 06:00), and the end alarm fires the next morning.
* **Snooze (5 minutes)** – the ringing screen and the notification both offer it.
  `AlarmActionReceiver` → `RingController.snooze()` cancels the current ring, stores the
  snooze deadline and lets `AlarmScheduler` arm a one-off alarm for that moment, so a
  snoozed alarm behaves exactly like a scheduled one (test alarms included).
* **Wake up 04:00** etc. can be edited; the app seeds *Wake up 04:00–04:15*,
  *Study 04:15–05:00*, *Rest 05:00–06:00* for every day on first launch.
#   v i t a r a _ r o u t i n e  
 