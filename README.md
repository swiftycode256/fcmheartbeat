# FCMHeartbeat (for Xiaomi HyperOS)

A simple, always-running helper tool app that stops Xiaomi HyperOS from restricting the background activity of Google Play's FCM (Cloud Messaging) services.

## Background

On certain Xiaomi HyperOS versions, its power management features actively restricts the background activity of Google Play's FCM services when the screen is locked, preventing app notifications from being delivered properly. This causes the FCM process to frequently disconnect with the errors `ERR_IO_FIN` or `ERR_IO_WRITE`. However, when Gboard is set as the active input method (on HyperOS 3 or below) or there are certain conversation-style notifications present, the system does not restrict FCM's background activity (possibly due to GMS having a higher process priority when an active app binds to it) and FCM keeps its connection when the screen is locked. The idea is to create a helper tool always active in the background and during sleep that binds to certain GMS services, deterring the system from restricting its background activity.

## Mechanism

When the app is opened or on system startup, it creates a `specialUse` foreground service, binds itself to the FCM service `com.google.android.gms.cloudmessaging.service.START` and Google Ads ID service `com.google.android.gms.ads.identifier.service.START` (for redundancy) using `bindService`, and acquires a partial wake lock to prevent the system from restricting its background activity. It elevates the process priority of GMS through this service binding while it's active.

When the app process is automatically terminated by power management features, it restarts the foreground service automatically in the background to prevent interruptions.

### Proof of concept

The foreground service + partial wake lock concept works under HyperOS 4, tested with Termux running a timer script; the timer kept running nonstop even when the screen is locked, with negligible battery life impact to the device. The wake lock is necessary for it to function, as the timer freezes in the background without it.

## Usage instructions

- Enable Autostart permissions and set power management mode to "Unrestricted" for this app
- Open the app for it to start running in the background (no additional "Start" button needed)
- Click the diagnostics button in the toolbar to check the FCM Diagnostics page
- Recommended: Lock the app window in the task switcher to reduce interruptions to its background process
