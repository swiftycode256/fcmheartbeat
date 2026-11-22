# App implementation details

See `README.md` for main functionality and mechanism

## Interface

The app consists of a simple, one-page (scroll) view that displays the current status and running time of the foreground service and the app's service bindings. It should display the current status (Active; Errors detected; Failed to start) and the service bindings. 

- If the app detects the power management mode is not set to "Unrestricted", it should show a message prompting the user to do so, and should disappear when this setting updates to the desired value. 
- There should be a button on the right-top toolbar that opens the FCM Diagnostics page (`com.google.android.gms.gcm.GcmDiagnostics` activity), and should be disabled if Google Play Services are not enabled or installed on the device (and vice versa). 
- It should also detect whether the system's power management features had frozen the app's process in the background by comparing the elapsed boot time against a counter updated every second. It should show a message saying that the background activity has been restricted if the difference exceeds 30 seconds. **The running time displayed in the current status should use this counter for an accurate representation.**
- It should handle and display errors when creating the foreground service, binding GMS services, etc.

The detailed implementation are depicted through the pictures in the `ui-drafts` folder. They are just a rough draft; make it more refined and polished and use native Material 3 Expressive components in the actual interface.

## Foreground service

When opening the app, on device boot, or when the app window is closed in the app switcher (HyperOS always terminates the foreground service when closing), it should create a foreground service that performs the following actions to initialize itself:

- Acquire a partial wake lock
- Create bindings to the relevant GMS/FCM services
    - Check whether Google Play Services are enabled on the device

If acquiring the wake lock fails, it should not proceed and terminate the foreground service while displaying an error notification. If the app failed to bind to all services or GMS is not enabled on this device, it should still keep running but should display the "Errors detected" state in the app and the service notification. 

For the service binding errors, the app should automatically retry every 15 seconds in the background or when its main interface is opened, and only post new error notifications when the error type or exception message differs. If the binding disconnects, it should attempt to automatically re-create the bindings in the background to prevent interruptions. 

### Notifications

Example notifications are depicted in the `ui-drafts/Notifications.png`. The first two are the foreground service notifications, while the rest are error/warning messages. These two types of notifications should use separate channels/categories so the user can manage them separately.

---

# Clarified behavior & technical details

This section pins down behavior the sections above leave open, plus platform constraints (targetSdk 37, minSdk 26). Where it conflicts with the sections above, this section wins.

## State model

A single process-wide state holder (`HeartbeatState`, a `StateFlow`) is the source of truth for the UI and the service notification. The service writes it; the activity only reads it and sends commands (start / restart / retry now). The app runs in a single process, so no IPC is needed.

| Status | Condition | Dot | Button |
|---|---|---|---|
| Starting | Service start requested, bindings not yet resolved | grey + loading indicator | none |
| Active | Service running and **at least one** binding connected | green | Restart |
| Errors detected | Service running but **every** binding failed, or GMS missing/disabled | yellow | Restart |
| Failed to start | `startForegroundService`/`startForeground` threw, or the wake lock could not be acquired | red | Retry |

- A partial binding failure (some connected, some not) keeps the status **Active** and shows the yellow "Some services failed to bind" card (see `With warnings 2.png`).
- A detected process freeze is a **warning only**: it shows the freeze card but never changes the status.
- **Restart** fully tears down (unbind, release wake lock, stop service) and starts again; the running time resets. **Retry** does the same from the failed state.

## Foreground service

- Type `specialUse`. Manifest needs `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, and a `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" …/>` on the service. Return `START_STICKY`.
- **Startup order:** call `startForeground()` first (it must happen within a few seconds of `startForegroundService()`), then acquire the wake lock, then check GMS and bind. If the wake lock fails, call `stopForeground` + `stopSelf` and post the "Unable to acquire wake lock" notification.
- Wake lock: `PARTIAL_WAKE_LOCK`, tag `FCMHeartbeat::Heartbeat`, acquired with no timeout and released in `onDestroy`. `acquire()` normally only throws when the permission is missing. If the system silently overrides a held wake lock, freeze detection is what catches it.
- **Start triggers:**
  - `MainActivity.onStart` (the app is in the foreground, so this is always allowed)
  - `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` receivers (the update kills the process). `specialUse` is not among the types Android 15 blocks from `BOOT_COMPLETED`.
  - `START_STICKY` restart after the process is killed
  - `onTaskRemoved()`: schedule an exact alarm about 1 s out (`setExactAndAllowWhileIdle`) whose `PendingIntent.getForegroundService` restarts the service. Exact alarms are exempt from the Android 12+ background-start restriction. Needs `SCHEDULE_EXACT_ALARM`, or `USE_EXACT_ALARM` if the app is not distributed on Play.
- **Restarting from the background only works if the app is exempt from battery optimization** ("Unrestricted"). Otherwise a sticky or alarm restart can throw `ForegroundServiceStartNotAllowedException`. Catch it and post the "Unable to start foreground service" notification.
- Restart is best-effort. If HyperOS *force-stops* the app on swipe (as opposed to killing the process), Android cancels its alarms and stops delivering its broadcasts until the user opens it again, and nothing can bring it back. Locking the task in the switcher is the mitigation (see README).

## GMS check & bindings

- Manifest needs `<queries><package android:name="com.google.android.gms" /></queries>`. Without it, Android 11+ hides GMS from the app: package lookups fail and binds return `false`.
- GMS available = `packageManager.getApplicationInfo("com.google.android.gms", 0).enabled` (a `NameNotFoundException` means not installed). Avoid the Play Services client library; it is not needed.
- Bindings (both use explicit `setPackage("com.google.android.gms")`):
  - Google Ads ID: `com.google.android.gms.ads.identifier.service.START`
  - FCM: `com.google.android.gms.cloudmessaging.service.START` **(unverified: check that GMS actually exports it before relying on it; see Open questions)**
- Flags: `BIND_AUTO_CREATE or BIND_IMPORTANT`. `BIND_IMPORTANT` asks the system to raise GMS to the client's (foreground-service) priority, which is the whole point. `BIND_INCLUDE_CAPABILITIES` (API 29+) can be tried as an experiment.
- Per-binding state: `Connecting`, `Connected`, `Error(message)`.
  - `bindService` returning `false`: Error ("Service not found or permission denied"). Unbind anyway to release the connection.
  - `SecurityException` / `IllegalArgumentException`: Error(exception message).
  - `onServiceConnected`: Connected.
  - `onNullBinding` (API 28+): treat as **Connected**. The binding still holds and still raises GMS priority even without an interface.
  - `onServiceDisconnected`: Connecting. The system re-binds on its own once GMS restarts, so do nothing.
  - `onBindingDied`: unbind and re-bind right away. The system will not reconnect a dead binding.
- Retry loop: while any binding is in Error or GMS is unavailable, a coroutine retries every 15 s (re-checking GMS first). `MainActivity.onResume` triggers an immediate retry.

## Freeze detection & running time

Do **not** count ticks: `delay(1000)` overshoots by a few ms per tick, which adds up to minutes over a day and would trigger false freeze warnings. Use gap measurement instead:

- A ticker in the service runs every 1 s. On each tick: `gap = elapsedRealtime() - lastTick`. If `gap > 2000 ms`, add `gap - 1000` to `frozenMs` and record the event (start time, duration).
- `runningTime = elapsedRealtime() - serviceStartElapsed - frozenMs`. This is the freeze-aware counter shown in the UI and the notification.
- Show the "Background activity restrictions detected" card when `frozenMs > 30 s` since the service started, with the total ("for 45s"). It stays visible until the service restarts.
- Post the matching notification at most once per freeze event, and only for events that alone push the total past the threshold or add ≥ 30 s more.
- `elapsedRealtime` includes deep sleep, so a device that suspends despite the wake lock also counts as frozen. That is intended, since it is the same failure.

## Power management detection

- "Unrestricted" = `PowerManager.isIgnoringBatteryOptimizations(packageName)`. Also treat `ActivityManager.isBackgroundRestricted()` (API 28+) as restricted.
- Re-check on every `ON_RESUME`. There is no public broadcast for this setting, and resume covers the user coming back from Settings.
- The card gets action buttons:
  - **Battery settings**: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (needs the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission), falling back to the app details page
  - **Autostart**: `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`, guarded by try/catch and hidden if it does not resolve
- Autostart status cannot be read through a public API. It is only mentioned in the text, never detected.

## Diagnostics button

- Opens `ComponentName("com.google.android.gms", "com.google.android.gms.gcm.GcmDiagnostics")`. Enabled when GMS is available (re-checked on resume). Wrap `startActivity` in try/catch (`ActivityNotFoundException`, `SecurityException`) and show a snackbar on failure.

## Notifications

- **Runtime permission:** on Android 13+, request `POST_NOTIFICATIONS` on first launch. Without it the service still runs, but neither the status notification nor error notifications are visible. If it is denied, show an info card in the UI with a button to the app's notification settings.
- Channels:
  - `status`: importance LOW, no sound or badge, for the ongoing FGS notification
  - `alerts`: importance DEFAULT, for errors and warnings
- **Status notification:** title = status, plus a chronometer (`setUsesChronometer(true)`, `setWhen(currentTimeMillis() - runningTime)`). The system draws the ticking clock, so the app does not post an update every second (that would be rate-limited and waste battery). Re-post only when the status changes or a freeze is detected (to re-base the chronometer on the freeze-aware time). Tapping it opens `MainActivity`. Note: on Android 14+ users can swipe the FGS notification away; the service keeps running.
- **Alert de-duplication:** keep the last posted `(type, message)` per alert type, persisted in `SharedPreferences` so a crash/restart loop does not spam. Post only when it differs. When the error clears, cancel its notification and forget the stored key, so a later recurrence notifies again.
- Each alert type uses a fixed notification ID, so a newer alert replaces an older one of the same type.

## UI (Material 3 Expressive)

- `MaterialExpressiveTheme` with dynamic color (Android 12+). Light and dark both supported, edge-to-edge.
- `LargeFlexibleTopAppBar` / `TopAppBar` with the diagnostics `IconButton` (Material Symbols "monitor_heart" or "ecg_heart" vector drawable; do not use the deprecated icons-extended library).
- Status card:
  - Animated status dot (gentle pulse while Active)
  - `LoadingIndicator` while Starting
  - Running time as `HH:MM:SS`, recomposed every second from the shared state (no separate UI timer)
  - Restart/Retry button
- Bindings list: a segmented / grouped list with a trailing status label (Connected / Error / small `LoadingIndicator`).
- Message cards use theme roles (no hard-coded colors), with an icon, title, body and optional actions:
  - info → `surfaceContainerHigh`
  - warning → `tertiaryContainer` (or a custom warning container in `Color.kt`)
  - error → `errorContainer`
- Animate cards appearing and disappearing (`AnimatedVisibility`) so status changes don't cause jumps.

## Dependencies to add / bump

- Compose BOM to the current stable, and `material3` ≥ 1.5 for the Expressive APIs (`MaterialExpressiveTheme`, `LoadingIndicator`, flexible app bars). Some are still `@ExperimentalMaterial3ExpressiveApi`, so opt in.
- `lifecycle-runtime-compose` (`collectAsStateWithLifecycle`), `lifecycle-service` (`LifecycleService`), `kotlinx-coroutines-android`. Bump `core-ktx`, `activity-compose` and `lifecycle` to current versions.

## Open questions

- Is `com.google.android.gms.cloudmessaging.service.START` a real exported service action? Verify on device with `adb shell pm query-services -a com.google.android.gms.cloudmessaging.service.START com.google.android.gms`. If not, choose another always-present GMS service action for the FCM binding.
- Does HyperOS's per-app "No restrictions" battery setting map to `isIgnoringBatteryOptimizations() == true`? Verify on device. If not, the power-management card may stay visible after the user sets it.
- `applicationId` is still `com.example.fcmheartbeat`. Change it before any release.
