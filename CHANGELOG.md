# Changelog

## 1.0

First release.

- Hibernates (force-stops) awake apps with one tap, per app, from a Quick Settings tile, or
  automatically after the screen turns off.
- Fast mode: a built-in adb client stops apps through the phone's own wireless debugging,
  switched on only for the run. About two seconds for a whole run, nothing on screen.
- Accessibility mode as the fallback without Wi-Fi: presses Force stop and OK on each app's
  App info page behind a progress screen.
- Answers the Android 11 and 12 "Allow wireless debugging on this network?" prompt after a
  reboot.
- Memory dashboard; awake, hibernated and failed lists with pull to refresh.
- User apps, system apps or only your own list; a never-hibernate list; keyboard, home
  screen, phone and messaging apps, accessibility services, notification listeners, live
  wallpaper and device admins are always kept awake.
- Backup and restore of settings as JSON.
- Android 11 or newer.
