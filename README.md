# FCMHeartbeat (for Xiaomi HyperOS)

A simple, always-running helper tool app that stops Xiaomi HyperOS from restricting the background activity of Google Play's FCM (Cloud Messaging) services.

## Technical analysis

On certain Xiaomi HyperOS versions, its power management features actively restrict the background activity and network sockets of Google Play's FCM services when the screen is locked, preventing app notifications from being delivered properly. This causes the FCM process to frequently disconnect with the errors `ERR_IO_FIN` or `ERR_IO_WRITE`. On HyperOS 3, using Gboard as the input method deters the system from restricting FCM due to its service bindings to GMS, but that's no longer the case for HyperOS 4 with additional power management mechanisms implemented.

---

The restrictions come from HyperOS's own process freezer, **Greezer**. This only applies to China ROMs, as Greezer excempts all Google apps from background restrictions on Global ROMs. 

About 30 seconds after the screen turns off, Greezer freezes the GMS process with the reason `screen_off` (also `tobg` and `from system`) and closes its network sockets while blocking new connections, causing its connection to close immediately with `ERR_IO_FIN`.
Any wake-up (e.g. a binder call, an incoming broadcast, a provider query) only unfreezes GMS for 5 seconds before it gets frozen again.
GMS's reconnect alarm is held when it's frozen, which is why FCM often stays offline even when the screen turns back on unless a network change occurs.

There's also a quick-freeze mechanism on China ROMs, which Greezer freezes all GMS instances on the system 1 minute after screen lock. While it does not close its network sockets, GMS stays inactive in the background unless a service ping or other activity causes Greezer to temporarily unfreeze it.
It's controlled by the `mGmsLimitEnabled` config value, which is on by default. The debug command `dumpsys greezer IM GMS disable` turns it off until the next reboot.
(HyperOS 3 had this mechanism too, but it only applies to the dual-app/second-space copy of GMS and only restricted wake locks and network rules.)

Greezer also ignores Android's process priority: increasing the GMS process priority with a wake-lock enabled app holding GMS service bindings does not prevent Greezer from restricting FCM.

---

However, Greezer does not restrict FCM's background activity if a "protected" app holds service bindings to GMS (and is not frozen by the system).

The system considers an app as "protected" if it's the current input method (screen on only), owns a virtual display instance, is using the camera/vibration, or is actively using audio or location services besides being the current app on screen.
The freeze mechanism is explicitly skipped by Greezer under these circumstances as shown in log messages.

Therefore, an app only holding a wake lock and GMS service bindings does not make it "protected"; it also needs to satisfy at least one of the conditions mentioned above for it to work, which is what FCMHeartbeat does.
Gboard (as the current input method) was also considered as a "protected" app in HyperOS 3, but that's no longer the case in HyperOS 4 when the screen turns off, which explains why it no longer works.

## App mechanism

FCMHeartbeat makes itself a "protected" app by creating a virtual display instance. The virtual display exemption applies even with the screen off on HyperOS 4.

When the app is opened or on device start, the app starts a `specialUse` foreground service that holds a partial wake lock to keep it always running.
It binds to the GMS services `com.google.android.c2dm.intent.REGISTER` (FCM registration) and `com.google.android.gms.ads.identifier.service.START` (Google Ads ID) and creates a private 1×1 virtual display with no surface.
Nothing is ever rendered in it, so there is almost no battery life impact, and it needs no additional permissions to work.

The service also pings the Google Ads ID service every 4 seconds, which causes the other quick-freeze mechanism to temporarily unfreeze it. It repeatedly unfreezes the process to make FCM stay active in the background. The quick-freeze mechanism does not close network sockets, so it won't disconnect FCM when it freezes the process again.

The app is tested to work on HyperOS version `4.0.4.0.XPLCNXM.D02` running on REDMI Turbo 5 Max. Log messages show Greezer skipped the freeze mechanism when the app is running, and FCM disconnect errors didn't appear.

## Usage instructions

- Enable Autostart permissions and set power management mode to "Unrestricted" for this app
- Open the app for it to start running in the background (no additional "Start" button needed)
- Click the diagnostics button in the toolbar to check the FCM Diagnostics page
- Recommended: Lock the app window in the task switcher to reduce interruptions to its background process
