# FCMHeartbeat (for Xiaomi HyperOS)

A simple, always-running helper tool app that stops Xiaomi HyperOS from restricting the background activity of Google Play's FCM (Cloud Messaging) services.

## Technical analysis

On certain Xiaomi HyperOS versions, its power management features actively restricts the background activity and network sockets of Google Play's FCM services when the screen is locked, preventing app notifications from being delivered properly. This causes the FCM process to frequently disconnect with the errors `ERR_IO_FIN` or `ERR_IO_WRITE`. On HyperOS 3, using Gboard as the input method deters the system from restricting FCM, but that's no longer the case for HyperOS 4 as more power management mechanisms are implemented.

### How HyperOS restricts FCM

The restrictions come from HyperOS's own process freezer, **Greezer** (`com.miui.server.greeze` in `/system_ext/framework/miui-services.jar`, inspected with `adb shell dumpsys greezer`). Android's standard power management isn't the cause: GMS is on the system Doze allowlist, its Doze network rules stay unblocked, and it remains at foreground-service priority while bound.

Greezer freezes GMS in two ways once the screen turns off:

1. **Screen-off freeze.** About 10–30 s after the screen turns off, Greezer freezes the GMS process with reason `screen_off` (also `tobg` and `from system`). The freeze also applies network restrictions (`NET_SOCKET|NET_CHAIN`): it closes the app's sockets and blocks its network.
   - The FCM connection dies within about 0.3 s. Every `Close err:20` (`ERR_IO_FIN`) in the FCM log lines up with one of these freezes.
   - On China ROMs, GMS is on Greezer's system blacklist. Global ROMs remove it from that list and also exempt all Google apps; China ROMs skip both.
   - Any wake-up (a binder call, an incoming broadcast, a provider query) only unfreezes GMS for a fixed 5 s. Greezer then freezes it again, often with the network cut again.
   - GMS's reconnect alarm is held while it's frozen, so FCM stays offline until a network change or the screen turns on. In logs, these outages lasted from minutes to over 6 hours.
2. **China-only "GMS limit".** On China models, about 10 s after GMS becomes active while the screen is off (in practice about once a minute), Greezer takes GMS off its allowlist and **quick-freezes** every GMS process, including dual-app and second-space copies.
   - This freeze leaves the network alone, but GMS can't run until something unfreezes it.
   - It's controlled by `mGmsLimitEnabled`, which is on by default. The debug command `adb shell dumpsys greezer IM GMS disable` turns it off until the next reboot.
   - HyperOS 3 had a GMS limit too, but it only covered the dual-app/second-space copy of GMS and only restricted wake locks and network rules.

Bindings, wake locks and foreground services don't protect GMS on their own. Greezer ignores Android's process priority for GMS: an app bound to GMS with `BIND_IMPORTANT` from a foreground service didn't stop either freeze.

### When HyperOS doesn't restrict FCM

Greezer skips a freeze in a few cases:

- **While charging:** blacklisted system apps are exempt while plugged in (`sysblack_plugged`). That's why tests done on the charger look fine.
- **While GMS is on screen or starting an activity**, for example with the FCM diagnostics page open.
- **For apps set to "No restrictions"** in HyperOS's battery settings (`NO_RESTRICT_APP`). Play services doesn't offer this setting.
- **When a protected app is bound to GMS.** This is the key one. Before freezing an app, Greezer checks every app bound to it (`checkBind`). If one of those clients counts as protected, the freeze is skipped (`… is calling`). A client counts as protected if it is the current keyboard (IME), owns a virtual display, is using the camera or vibrator, or is running a backup or instrumentation. With the screen on, being the top or a visible app counts too.
- **On the 60 s GMS limit only:** the app currently in front holding a binding to GMS also prevents it.

**Why Gboard used to work:** on HyperOS 3 the keyboard check was simply "is this the current keyboard". Gboard was therefore never frozen, and because Gboard is bound to GMS (`.chimera.PersistentApiService`), GMS was skipped as well. HyperOS 4 changed the check to "current keyboard **and screen on**". Now Gboard and GMS both get frozen at every screen-off.

Conversation notifications have also been seen to keep FCM alive. No code path linking them to GMS has been found yet.

## App mechanism

FCMHeartbeat makes itself a protected client of GMS, the role Gboard had on HyperOS 3. The virtual-display check, unlike the keyboard check, still applies with the screen off on HyperOS 4.

- **Foreground service.** When the app is opened, on boot, after an app update, or whenever the system restarts it, the app runs a `specialUse` foreground service. That service:
  - holds a partial wake lock
  - binds to the GMS services `com.google.android.c2dm.intent.REGISTER` (FCM registration) and `com.google.android.gms.ads.identifier.service.START` (Google Ads ID)
  - creates a **private 1×1 virtual display** with no surface. Nothing is ever drawn, so it costs no battery, and it needs no permission. Owning it makes the app count as a protected client, so Greezer refuses the screen-off freeze of GMS. Logcat shows `VIRTUAL_DISPLAY_APP com.example.fcmheartbeat calling`.
- **Pings.** The service pings the Ads ID binding every 4 s. If the GMS limit's quick freeze catches GMS, the next ping unfreezes it. Any later attempt to refreeze it with the network cut is blocked by the virtual display.
- **Tested on HyperOS 4.0.4.0 (China ROM)**, unplugged with the screen off. All 56 screen-off freeze attempts on GMS were refused, FCM stayed connected, and pushes arrived. This was a short test; a longer, overnight run is still to be confirmed.
- **Restart behavior.** The app relies on Android's sticky-service restart, and schedules an exact-alarm restart for when its task is removed.
  - When the app is swiped away, HyperOS kills it (`SwipeUpClean`) and restarts the service without calling `onStartCommand`. The service therefore starts all of its work from `onCreate`.
  - Restarting from the background requires the "Unrestricted" battery setting.
- **Status display.** The app shows the service status, its running time (excluding any time the app itself was frozen), the state of each binding, and the virtual display ("Helper processes").
  - Errors and warnings are posted on a separate notification channel.
  - The FCM Diagnostics page can be opened from the toolbar.

## Usage instructions

- Enable Autostart permissions and set power management mode to "Unrestricted" for this app
- Open the app for it to start running in the background (no additional "Start" button needed)
- Click the diagnostics button in the toolbar to check the FCM Diagnostics page
- Recommended: Lock the app window in the task switcher to reduce interruptions to its background process
