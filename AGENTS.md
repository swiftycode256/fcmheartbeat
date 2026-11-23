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

If acquiring the wake lock fails, it should not proceed and terminate the foreground service, reporting it as an "Unable to start foreground service" error (with the wake lock exception message) in both the app and the error notification. There is no separate wake lock error type. If the app failed to bind to all services or GMS is not enabled on this device, it should still keep running but should display the "Errors detected" state in the app and the service notification. 

For the service binding errors, the app should automatically retry every 15 seconds in the background or when its main interface is opened, and only post new error notifications when the error type or exception message differs. If the binding disconnects, it should attempt to automatically re-create the bindings in the background to prevent interruptions. 

### Notifications

Example notifications are depicted in the `ui-drafts/Notifications.png`. The first two are the foreground service notifications (status title only, no running timer), while the rest are error/warning messages. These two types of notifications should use separate channels/categories so the user can manage them separately.

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
| Failed to start | `startForegroundService`/`startForeground` threw, or the wake lock could not be acquired (both shown as "Unable to start foreground service" + exception message) | red | Retry |

- A partial binding failure (some connected, some not) keeps the status **Active** and shows the yellow "Some services failed to bind" card (see `With warnings 2.png`).
- A detected process freeze is a **warning only**: it shows the freeze card but never changes the status.
- When the status is Failed to start, the bindings section shows the GMS-missing card if GMS is unavailable, otherwise the info card "Service bindings were not created" (see `Failed to start.png` and `Failed to start 2.png`).
- **Restart** fully tears down (unbind, release wake lock, stop service) and starts again; the running time resets. **Retry** does the same from the failed state.

## Foreground service

- Type `specialUse`. Manifest needs `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, and a `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" …/>` on the service. Return `START_STICKY`.
- **Startup order:** call `startForeground()` first (it must happen within a few seconds of `startForegroundService()`), then acquire the wake lock, then check GMS and bind. If the wake lock throws, call `stopForeground` + `stopSelf`, set status Failed to start, and post the "Unable to start foreground service" notification with the wake lock exception message.
- Keep `android:stopWithTask="false"` (the default) so swiping the task away does not stop the service on stock Android.
- Wake lock: `PARTIAL_WAKE_LOCK`, tag `FCMHeartbeat::Heartbeat`, acquired with no timeout and released in `onDestroy`. `acquire()` normally only throws when the permission is missing. If the system silently overrides a held wake lock, freeze detection is what catches it.
- **Start triggers:**
  - `MainActivity.onStart` (the app is in the foreground, so this is always allowed)
  - `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` receivers (the update kills the process). `specialUse` is not among the types Android 15 blocks from `BOOT_COMPLETED`.
  - `START_STICKY` restart after the process is killed
  - `onTaskRemoved()`: schedule an exact alarm about 1 s out (`setExactAndAllowWhileIdle`) that fires a `RestartReceiver` broadcast; the receiver calls `startForegroundService`. A broadcast from an exact alarm is exempt from the Android 12+ background-start restriction.
    - Permissions: `USE_EXACT_ALARM` (API 33+, granted automatically) plus `SCHEDULE_EXACT_ALARM` with `maxSdkVersion="32"`.
    - Check `canScheduleExactAlarms()` first. If it returns false, fall back to `setAndAllowWhileIdle`, which is best-effort only.
- **HyperOS swipe-kill restart never calls `onStartCommand`** (reproduced 2026-11-22 on OS4.0.4.0):
  - Swiping the app away triggers `ProcessSceneCleaner: SwipeUpClean`, which kills the process. AMS then logs "restart of crashed service" and recreates the service in a new process: `onCreate` runs, but no start is delivered (`lastStartId` stays the same).
  - A plain process kill (`run-as … kill -9`) restarts normally, with `onStartCommand(null)`.
  - The service therefore runs `promoteToForeground()` + `initialize()` from **`onCreate`**. `onStartCommand` still calls `startForeground` (required for each `startForegroundService`) and only retries when already running.
  - Before this fix, the restarted service stayed foreground with a stale "Active" notification but held no wake lock, bindings or virtual display. GMS was then frozen at the next screen-off.
- **Restarting from the background only works if the app is exempt from battery optimization** ("Unrestricted"). Otherwise a sticky or alarm restart can throw `ForegroundServiceStartNotAllowedException`. Catch it and post the "Unable to start foreground service" notification.
- Restart is best-effort. If HyperOS *force-stops* the app on swipe (as opposed to killing the process), Android cancels its alarms and stops delivering its broadcasts until the user opens it again, and nothing can bring it back. Locking the task in the switcher is the mitigation (see README).

## GMS check & bindings

- Manifest needs `<queries><package android:name="com.google.android.gms" /></queries>`. Without it, Android 11+ hides GMS from the app: package lookups fail and binds return `false`.
- GMS available = `packageManager.getApplicationInfo("com.google.android.gms", 0).enabled` (a `NameNotFoundException` means not installed). Avoid the Play Services client library; it is not needed.
- Bindings (both use explicit `setPackage("com.google.android.gms")`). Verified on the emulator (Android 17 / API 37, GMS 26.45.31) with `pm query-services`:

  | UI label | Intent action | Resolves to | Exported / permission |
  |---|---|---|---|
  | Google Ads ID | `com.google.android.gms.ads.identifier.service.START` | `.ads.identifier.service.AdvertisingIdService` | yes / none |
  | Firebase Cloud Messaging | `com.google.android.c2dm.intent.REGISTER` | `.gcm.PushMessagingRegistrarProxy` | yes / none |

  - `com.google.android.gms.cloudmessaging.service.START` (from the original plan) **does not exist** ("No services found"), so it is not used.
  - Binding only runs the proxy's `onCreate`/`onBind`, never `onStartCommand`, so it does **not** trigger an FCM registration. It is expected to return a null binder (`onNullBinding`), which still counts as Connected (see below).
- **Which process gets elevated:** a binding raises only the process *hosting the bound service*. On the emulator's GMS build:
  - There is no `com.google.android.gms.persistent` process. The FCM connection service (`.gcm.GcmService`) runs in the main `com.google.android.gms` process, the same process as both bound services, so either binding elevates the FCM process.
  - Older GMS builds ran GCM in `.persistent`. If the HyperOS device does, the bindings would elevate the wrong process (see Open questions).
- Flags: `BIND_AUTO_CREATE or BIND_IMPORTANT`. `BIND_IMPORTANT` asks the system to raise GMS to the client's (foreground-service) priority, which is the whole point. `BIND_INCLUDE_CAPABILITIES` (API 29+) can be tried as an experiment.
- Per-binding state: `Connecting`, `Connected`, `Error(message)`.
  - `bindService` returning `false`: Error ("Service not found or permission denied"). Unbind anyway to release the connection.
  - `SecurityException` / `IllegalArgumentException`: Error(exception message).
  - `onServiceConnected`: Connected.
  - `onNullBinding` (API 28+): treat as **Connected**. The binding still holds and still raises GMS priority even without an interface.
  - `onServiceDisconnected`: Connecting. The system re-binds on its own once GMS restarts, so do nothing.
  - `onBindingDied`: unbind and re-bind right away. The system will not reconnect a dead binding.
- Retry loop: while any binding is in Error or GMS is unavailable, a coroutine retries every 15 s (re-checking GMS first). `MainActivity.onResume` triggers an immediate retry.
- **Ping loop (HyperOS Greezer workaround):**
  - Every 4 s, the service calls `pingBinder()` on each binding that returned a binder. In practice that's only the Ads ID service; the FCM proxy returns a null binding.
  - A synchronous binder call into a frozen GMS makes Greezer thaw it (`sync_binder2`). The interval is below Greezer's 5 s quick-freeze timeout (`fz_timeout=5000`).
  - The ping runs on an IO thread with a 2 s timeout, since a call into a frozen process may block. Only one ping per binding is in flight at a time.
  - On failure (`pingBinder()` false or timeout), that binding becomes `Error("Service not responding …")`. The status stays Active, with the "Some services failed to bind" warning and alert.
  - The next successful ping sets it back to Connected, which clears the warning and cancels the alert.
  - Pings that take more than 200 ms are logged under the `GmsBindingManager` tag.
  - **Status: does not keep FCM connected on HyperOS 4 CN on its own.** Greezer re-freezes 5 s after each thaw regardless of pings, often with network restrictions. Pings never fail, so they also can't detect the freeze.
    - The virtual-display shield (see Helpers) is what prevents the freezes.
    - The ping loop is kept so it can undo a China GMS-limit quick freeze, which doesn't cut the network.
    - See the Greezer device finding below.

## Helpers (virtual-display shield)

- **What it does:** `service/VirtualDisplayShield` creates a private 1×1 virtual display, `createVirtualDisplay("FCMHeartbeat", 1, 1, 160, null, 0, …)`.
  - No surface, flags 0, no permission. It is created in `initialize()` after the wake lock and released in `teardown()`.
  - Verified on device: `dumpsys display` shows `owner com.example.fcmheartbeat (uid 10506)`, `FLAG_PRIVATE`, `state OFF`, and no status-bar cast indicator.
- **State:** `HeartbeatState.helpers: Map<Helper, BindingState>` (`Connecting` = starting, `Connected` = active).
  - `VirtualDisplay.Callback.onStopped` → `Error`. The 15 s retry loop and `ACTION_START` (opening the app) recreate it.
- **Status rule:** if every helper is in `Error`, the status is "Errors detected" even when the bindings are fine, because without the shield GMS is frozen at screen-off.
- **UI:** a "Helper processes" section after "Service bindings" (see `ui-drafts/Main UI.png`):
  - list rows labelled "Active" / "Error" / loading
  - "All helper processes operational" (success card) or "Unable to start helper processes" (error card) with `<label>: <message>` lines
  - "Helper processes not started" (info) when the service failed to start
- **Alert:** `AlertNotifier.Type.Helper` (ID 14), "Unable to start helper processes". It is de-duplicated like the binding alert and cleared once all helpers are active.

## Freeze detection & running time

Do **not** count ticks: `delay(1000)` overshoots by a few ms per tick, which adds up to minutes over a day and would trigger false freeze warnings. Use gap measurement instead:

- A ticker in the service runs every 1 s. On each tick: `gap = elapsedRealtime() - lastTick`. If `gap > 2000 ms`, add `gap - 1000` to `frozenMs` and record the event (start time, duration).
- `runningTime = elapsedRealtime() - serviceStartElapsed - frozenMs`. This is the freeze-aware counter shown in the UI status card. The notification shows no timer.
- Show the "Background activity restrictions detected" card when `frozenMs > 30 s` since the service started, with the total ("for 45s"). It stays visible until the service restarts.
- Post the matching notification once per service run, when `frozenMs` first crosses 30 s. The de-dup key includes the service start time, so constant freezes don't spam notifications, but a restarted service can notify again.
- `elapsedRealtime` includes deep sleep, so a device that suspends despite the wake lock also counts as frozen. That is intended, since it is the same failure.

## Power management detection

- "Unrestricted" = `PowerManager.isIgnoringBatteryOptimizations(packageName)`. Also treat `ActivityManager.isBackgroundRestricted()` (API 28+) as restricted.
- Re-check every time the activity comes to the foreground (`ON_RESUME`). There is no public broadcast for this setting. Resume covers the user coming back from Settings, from another app or from the switcher. The card therefore disappears once the setting is "Unrestricted" and shows again if it is later changed back.
- The card has one action, **Open app settings**: `com.miui.securitycenter/com.miui.appmanager.ApplicationsDetailsActivity` with extras `package_name` (our package) and `miui.intent.extra.USER_ID` (int).
  - These extras were read from the activity's `onCreate` in the device's SecurityCenter APK, and launching it was verified on HyperOS OS4.0.4.0.
  - The page holds both the battery saver ("Unrestricted") and Autostart switches.
  - The button is shown only if the component resolves and is exported, so on non-Xiaomi devices the card has no button. The launch is wrapped in try/catch.
  - Package visibility comes from `<queries><package android:name="com.miui.securitycenter" /></queries>`.
- Autostart status cannot be read through a public API. It is only mentioned in the text, never detected.

## Diagnostics button

- Opens `ComponentName("com.google.android.gms", "com.google.android.gms.gcm.GcmDiagnostics")`. Verified on the emulator: the activity is exported and launches. Enabled when GMS is available (re-checked on resume). Wrap `startActivity` in try/catch (`ActivityNotFoundException`, `SecurityException`) and show a snackbar on failure.

## Notifications

- **Runtime permission:** on Android 13+, request `POST_NOTIFICATIONS` on first launch. Without it the service still runs, but neither the status notification nor error notifications are visible. If it is denied, show an info card in the UI with a button to the app's notification settings.
- Channels:
  - `status`: importance LOW, no sound or badge, for the ongoing FGS notification
  - `alerts`: importance DEFAULT, for errors and warnings
- **Status notification:** title = status ("Active" / "Errors detected"), no body text and no timer (`setShowWhen(false)`). Re-post only when the status changes. Tapping it opens `MainActivity`. Note: on Android 14+ users can swipe the FGS notification away; the service keeps running.
- **Alert types:**

  | Alert | Shown when |
  |---|---|
  | Background activity restrictions detected | a freeze crosses the threshold |
  | Google Play Services not found or enabled | GMS is missing or disabled |
  | Unable to bind to services | every binding failed |
  | Some services failed to bind | some bindings failed |
  | Unable to start foreground service | the service could not start; also covers wake lock failures |

  - "Unable to bind to services" and "Some services failed to bind" replace each other: they share one notification ID.
  - "Unable to start foreground service" is always posted, even when the start came from the open UI, since the user may leave. It is cancelled once the service starts successfully.
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

## Dependencies (applied in `gradle/libs.versions.toml`)

- **Compose BOM 2026.11.01**: Compose UI 1.13.0 and Material 3 **1.5.0**, which includes the Expressive APIs.
  - These artifacts and `core` 1.19.1 declare `minCompileSdk=37` and need AGP ≥ 9.1. The project already uses compileSdk 37 and AGP 9.5.2.
  - The opt-in for `ExperimentalMaterial3ExpressiveApi` is set in `app/build.gradle.kts`.
- **Kotlin stays at 2.2.10.** AGP 9.5.2's built-in Kotlin bundles KGP 2.2.10, and the Compose compiler plugin version must match it. Compose 1.13 requires Kotlin ≥ 2.2. Bump Kotlin only together with AGP.
- **Added:**
  - `lifecycle-runtime-compose` (`collectAsStateWithLifecycle`)
  - `lifecycle-service` (`LifecycleService`)
  - `kotlinx-coroutines-android` 1.10.2, which works with the 2.2 compiler
- **Bumped:** `core-ktx` 1.19.1, `activity-compose` 1.13.0, `lifecycle` 2.11.1, `androidx.test.ext:junit` 1.3.0, `espresso-core` 3.7.0.

## Device findings (HyperOS, confirmed by the user)

- **GMS process layout:** the device runs GCM in the main `com.google.android.gms` process (no `.persistent` process), the same as the emulator. Both bindings therefore elevate the FCM connection process.
- **"Unrestricted" mapping:** HyperOS's per-app "Unrestricted" battery setting does update Android's battery-optimization allowlist. This was checked with the hidden AOSP permission page, and other apps (e.g. Microsoft Authenticator) detect it, so `isIgnoringBatteryOptimizations()` is a reliable signal.

- **Greezer freezes GMS despite the binding (HyperOS OS4.0.4.0 CN, 2026-11-22):**
  - HyperOS's own freezer (`dumpsys greezer`) freezes the GMS uid (10122) 10–30 s after screen off. The freeze is logged as `FZ ... reason : screen_off ... resCtrl:SENSOR|NET_SOCKET|NET_CHAIN|NET_WAKELOCK`.
  - The `NET_SOCKET`/`NET_CHAIN` restrictions kill the FCM socket within ~0.3 s. Every `Close err:20` (ERR_IO_FIN) in `dumpsys activity service com.google.android.gms/.gcm.GcmService` lines up with one of these freezes.
  - It happens while FCMHeartbeat is running, bound with `BIND_IMPORTANT`, and GMS is at FGS proc state with network capability. Greezer ignores bindings, AOSP Doze allowlisting and proc state for GMS.
  - The greezer dump shows `IM mGmsLimitEnabled : true`, and GMS's no-control reason is `whiteapp|systemblack`. Its frozen rate is ~0.80.
  - GMS is only left unfrozen while visible, starting an activity, or charging (`sysblack_plugged`), so tests done while plugged in look fine.
  - Apps whose HyperOS battery setting is "No restrictions" show `NO_RESTRICT_APP` and are never frozen. This app is one of them. Whether that setting also works for GMS (given `mGmsLimitEnabled`) is untested.
  - **Every thaw lasts a fixed 5.0 s** (`fz_timeout=5000`), whatever triggered it. Binder calls into an already-thawed GMS don't extend it.
  - The re-freeze is either `quick freeze` (`SENSOR|NET_WAKELOCK|BINDER_PROXY`, network untouched) or `from system` (`NET_SOCKET|NET_CHAIN`, kills the socket). Which one depends on Greezer's mode:
    - After a `from system` freeze, every `sync_binder2` thaw led to another `from system` freeze (4/4).
    - `provider` / `execute_service` thaws usually led to `quick freeze`.
  - **`high_freq_binder` and `execute_service` are one-off thaw triggers, not exemptions:**
    - `high_freq_binder` fires when **system_server** sends ≥20 oneway binder transactions/s to a frozen uid (logcat: `GreezeManager: High freq system oneway binder to uid=… count=20/s, thawing`).
    - `execute_service` fires when the system dispatches a service lifecycle call (create/start/bind) into a frozen process.
    - Across all uids, the median thaw from either is 5.0 s. The following freeze was usually network-cutting: `from system` in 100/194 `high_freq_binder` cases and 49/65 `execute_service` cases.
    - Longer GMS thaws with the screen off (28–84 s) came from GMS's own activity (e.g. `GCM_WORK` wake locks), not from anything an app controls.
  - **Gboard (the HyperOS 3 workaround) no longer helps:**
    - Gboard (uid 10432) is the current IME. It binds GMS `.chimera.PersistentApiService` and `.chimera.PersistentDirectBootAwareApiService` with flags `0x1081` (`AUTO_CREATE|ADJUST_WITH_ACTIVITY|INCLUDE_CAPABILITIES`), the standard Play Services client binding, with nothing FCM-specific.
    - The system binds Gboard's IME service at FGS level, so Gboard sits at `BFGS` proc state.
    - Greezer still freezes Gboard on every screen-off (48/48 on 2026-11-22, frozen rate 0.79, `NET_SOCKET|NET_CHAIN`), even though it lists `mCurrentIMEPacageName : com.google.android.inputmethod.latin`. `IME_APP` appears only as a past no-control reason that ended in a freeze.
    - A frozen Gboard can't keep GMS alive, and Greezer ignores bindings for GMS anyway.
  - **Greezer policy, read from `/system_ext/framework/miui-services.jar` (package `com.miui.server.greeze`, disassembled with `dexdump`).** Two separate paths freeze GMS at screen-off:
    1. **Generic screen-off freeze** (`power/PolicyMaker.isAllowFreeze`, reasons `screen_off`/`tobg`/`from system`, with network restrictions).
       - On CN models GMS stays in `ListAppsManager.SYSTEM_BLACK_LIST`; non-CN models remove it. `systemblack` is only exempt while plugged in.
       - The Google-apps exemption (`googleapp`) applies only when `ConfigManager.isGlobal`.
    2. **China-only GMS limit** (`GreezeManagerService.triggerGMSLimitAction`). About 60 s after every screen-off (logcat `Aurogon_Test: triggerGMSLimitAction`), it removes GMS from the Aurogon allowlist and quick-freezes every GMS uid.
       - It is gated only by `isCnModel && mGmsLimitEnabled && screen off`. `mGmsLimitEnabled` is true by default.
       - PowerKeeper's `GmsObserver` reports Google reachability (`limit = !reachable`) through `updateGmsNetStatus`. That only triggers an extra immediate limit; the stored status is never read.
       - **Debug switch:** `adb shell dumpsys greezer IM GMS disable|enable|limit` sets the flag. It is in memory only and resets on reboot. Whether it also stops path 1 is untested.
  - **Exemption checks and whether they can apply to GMS:**
    - Path 1 consults: `NO_RESTRICT`, `TOP`, `IME` (screen on only), `WIDGET`, `VPN`, `VALID`, `CARWITH`, `VISIBLE`, `cloudwhite`/`whiteapp` (cancelled by `AwareResourceControl.isNetworkDisableAble`), `importantapp`, `wallpaper`, `deviceowner`, `accessibility`, `fg_perceptible` (static config table), `bluetooth`, `brightwhite` (screen on), `holdon`, `dlpkg`, `fgservice_sys`, active audio/GPS. It also consults the `freezeUids`/`freezeAction` filters (`BIND_SYSTEMUI`, `APP_FUNCTION`, `ZYGOTE`, `BROADCAST_LAUNCH`).
    - Path 2 (quick freeze) skips: allowlist, accessibility uid, wallpaper, on-screen window, a uid the **top app holds a binding to** (`ActivityManagerServiceImpl.holdConnectionUids(mTopAppUid)`), foreground, policy-allowed, GPS/audio/vibrator active, Bluetooth, current IME, widget app, VPN app, ordered broadcast, downloading, dynamic island.
    - Not available for GMS:
      - accessibility and widget: GMS ships no accessibility service or widget provider
      - `NO_RESTRICT`: HyperOS offers no battery setting for GMS
      - `googleapp`: Global ROMs only
      - IME, VPN, wallpaper and device owner don't apply to GMS
    - **`bind_systemui`:** any binding SystemUI opens to an app's service (`ActivityManagerServiceImpl.addConnection`) thaws and protects that uid until the notification shade closes or the uid dies. Apps cannot request it (`thawUids` accepts only SystemUI or system callers). The only GMS service SystemUI can bind is the `MotionSicknessAssistTileService` QS tile.
    - **Untested candidates:**
      - FCMHeartbeat being the top app at screen-off would make GMS "connected" for path 2 only.
      - GMS having active Bluetooth (e.g. a Wear OS watch connected through GMS).
      - GMS actively using GPS (high battery cost).
    - No code path linking conversation notifications to GMS was found.
  - **Why Gboard protected GMS on HyperOS 3 but not on HyperOS 4.** Compared `miui-services.jar` from `OS3.0.306.0.WPLCNXM` (extracted from the full OTA's `payload.bin` → `system_ext` EROFS image) with the device's `OS4.0.4.0.XPLCNXM`.
    - **Dependency protection (both versions):** `GreezeManagerService.freezeUids` calls `checkBind(uid)` before freezing. It walks every client bound to the target (`ActivityManagerServiceImpl.heldConnectionByUids`).
      - If any client passes `preCheckInterest(client)` (an `AurogonFilterManager.filter` mask of `0x1521c00` = BACKUP, IME, VIBRATOR, VALID, VIRTUAL_DISPLAY, INSTRUMENTATION, CAMERA), the target is skipped. Logcat shows `freezeUid uid=… return:<pkg> is calling`.
      - Gboard binds GMS (`.chimera.PersistentApiService`), so as long as Gboard counts as an IME app, GMS can't be frozen by this path.
    - **The change:** in OS3, the filter's IME bit (`0x800`) is just `isIMEApp(uid)`. OS4 adds `&& getScreenOn()`, and `PolicyMaker` gains the `sys_ime_screenon` reason.
      - In OS3 the current keyboard was never frozen, and anything it was bound to (GMS) was skipped by `checkBind`.
      - In OS4 both protections end at screen-off: Gboard is frozen (48/48 screen-offs) and GMS loses its dependency shield.
    - **Also new in OS4:**
      - The main-user GMS limit (`mGmsLimitEnabled`, quick freeze ~60 s after screen-off). OS3's `triggerGMSLimitAction(Z)` only touched `mGmsMultiUid` (GMS in a second space or dual app) and only limited wake locks and uid rules.
      - `SYSTEM_BLACK_LIST` (OS3 called it `mSystemBlackList` and already included GMS).
      - `isSystemuiBindProtected` and `restrictIME`.
    - **Unchanged:** `isInFgBinder`, `isInFgBinderApp`, binder thaw handling (`dealBinder` → `delayFreeze`), and the network cut on freeze (`closeSocketForAurogon` + `updateAurogonUidRule` unless `isRestrictNet` is false, i.e. messaging apps only).
    - **Possible OS4 workaround (untested):** `checkBind` still honors the `VIRTUAL_DISPLAY` bit.
      - `GreezeManagerService.isUsingVirtualDisplay(uid)` → `DisplayManagerInternal.isUsingVirtualDisplay(uid)`, minus `mVirtualDisplayBlackList`.
      - If FCMHeartbeat (bound to GMS) owned a private virtual display, `checkBind(GMS)` would treat it as an "interesting" client and skip the screen-off freeze (path 1).
      - It does **not** cover the China GMS limit (path 2), which quick-freezes through `freezeAction` without `checkBind`. That path would still need `dumpsys greezer IM GMS disable`.
    - **Extraction tools** used for the OTA: a small Python `payload.bin` extractor (full OTA ops only) and an EROFS reader with LZ4 compact-index support. Results were verified with `unzip -t`.
  - **When the China GMS limit (path 2) does *not* freeze GMS** (traced in OS4 `miui-services.jar`):
    - **Scheduling:** `GreezeManagerService$12.onUidActive` (the uid observer) posts message 10 with a 10 s delay whenever a GMS uid goes idle→active while the screen is off. `onScreenStateChanged` cancels it on screen-on.
      - `triggerGMSLimitAction` then requires `isCnModel && mGmsLimitEnabled && !mScreenOn`, and only queues uids for which `isAppRunning` is true.
      - Nothing is scheduled while GMS stays idle, the screen is on, the ROM is Global, or the debug switch is off.
    - **`triggerQuickFreezeAction` skips GMS when:**
      - the screen is on
      - GMS is in `mNoRestrictAppSet`
      - GMS is already frozen
      - `checkCondition` matches: `mAllowList`/`mAllowUidList`, accessibility uid, wallpaper, on-screen window, or **the top app (`mTopAppUid`) holds a binding to GMS** (`holdConnectionUids`, logged "is connected, skip check!")
    - **`checkAppStatusForFreeze` failing only postpones the freeze** (retried every 500 ms). It fails when GMS:
      - is in the foreground
      - is using GPS, audio or the vibrator
      - is pre-launching or in a freeform small window
      - has a Bluetooth scene active, or a backup or instrumentation running
      - is the activity controller, the current IME, a non-controllable widget app, or the VPN app
      - is in multi-window (screen on), receiving an ordered broadcast, downloading, or using the dynamic island
      - `PolicyManager.isAllowUid` doesn't help: it always returns false on CN.
    - **`freezeAction` also skips GMS (retried after 3 s) when:**
      - boot hasn't completed, or a screen-state update is in progress
      - GMS shows a window
      - `BIND_SYSTEMUI` protection or an app function is executing
      - GMS has an FGS and is in the "map" list
      - GMS is using a virtual display or the camera
      - an app zygote is starting or attach is pending
      - a MIUI car connection or CarWith is active
      - a cold-start receiver is pending
    - **The quick freeze never cuts the network** (`resCtrl:SENSOR|NET_WAKELOCK|BINDER_PROXY`, caller 8). FCM sockets are killed only by path 1 (caller 1: `screen_off`/`tobg`/`from system`).
    - **Device check (2026-11-22, 8 limit triggers):**
      - Only 1 ended in an actual quick freeze (21:15, launcher on top). Twice GMS was already frozen, and in the other 5 cases path 1 froze it within 0.5–4 s.
      - At 18:53 and 19:06, the GMS diagnostics activity was the last resumed activity, so GMS counted as foreground.
      - At 18:08 (charging, FCMHeartbeat opened at 18:04:47), no freeze followed. This is consistent with the "top app holds a connection" exemption but unconfirmed, since logcat didn't cover it.
  - **Virtual-display shield, device test (2026-11-22 22:45–22:50; unplugged, screen off ~4.7 min, after a swipe-kill restart):**
    - Greezer tried to freeze GMS with `screen_off` 56 times (about every 5 s). Every attempt was refused: `freezeUid uid=10122 return:freeze_able|VIRTUAL_DISPLAY_APP com.example.fcmheartbeat calling` → `ActionExecute: freezeUid FAILED … reason=screen_off`.
    - Greezer's history shows **no GMS freeze at all** in the window.
    - The China GMS limit triggered twice (22:46:20, 22:49:25), but no quick freeze followed.
    - FCM stayed connected the whole time: no `Close err:20`, connected since ~22:31. A push for `com.tangem.wallet` arrived at 22:46:18 with the screen off.
    - **Still to confirm:** a long run (overnight) covering deep Doze and multiple FCM heartbeat intervals (~9–29 min).
  - **Virtual-display shield (code analysis):**
    - Every network-cutting freeze of GMS goes through `GreezeManagerService.freezeUids`, which unconditionally calls `checkBind(GMS)`.
      - This covers path 1's initial `screen_off`/`tobg` freeze and the `from system` re-freeze after a thaw: `PolicyMaker.isAllowFreeze` → `ActionExecute.freezeUid`/`handleDelayedFreeze` → `GreezeManagerInternal.freezeUids`.
    - `checkBind` sees every app binding: `ActiveServices.bindServiceLocked` → `ActivityManagerServiceImpl.addConnection`.
    - A bound client counts if `preCheckInterest(client)` matches the `VIRTUAL_DISPLAY` bit. That calls `GreezeManagerService.isUsingVirtualDisplay(uid)` → `DisplayManagerInternal.isUsingVirtualDisplay` → `VirtualDisplayAdapter.isUsingVirtualDisplayLocked`.
      - It is true if **any** virtual display has `ownerUid == uid` (or `applicationName` resolves to that uid). No flags, display state or content are required.
      - `mVirtualDisplayBlackList` only lists delivery and bike apps (Meituan, Ele.me, Easybike…).
    - So if FCMHeartbeat (bound to GMS, never frozen itself thanks to `NO_RESTRICT_APP`) owns a private virtual display, `checkBind(GMS)` should return true. Logcat would show `freezeUid uid=10122 return:com.example.fcmheartbeat is calling`.
    - **Path 2 isn't covered:** quick freeze → `freezeAction` never calls `checkBind`, and its only binding exemption needs FCMHeartbeat to be the top app. But it doesn't cut the network, and a ping thaw afterwards is only re-frozen via path 1, which the shield blocks.
  - **Provider-query thaw (checked 2026-11-22, all Greezer history 00:04–19:25, charging state from battery history):**
    - Considering only windows that were unplugged with the screen off, `provider` thaws of GMS were followed by a network-safe `quick freeze` 14/15 times. That includes 6/6 that came right after a network-cutting freeze.
    - For comparison, `sync_binder2` thaws (which include the app's pings) were followed by a network-cutting `from system` freeze 156/196 times.
    - **It still doesn't restore FCM:**
      - The initial `screen_off` freeze has already killed the socket.
      - Afterwards GMS doesn't reconnect during thaw windows, even with the network allowed. Provider thaws at 16:35 and 17:05 (the latter lasted 59 s) produced no reconnect until a network change at 17:27.
      - At 19:25 the GcmService dump showed "Not connected" with `Reconnect Scheduler Alarm: in PT-34M` (34 min overdue). GMS's reconnect alarm is held while it is frozen, so 5 s thaws, ping-triggered or not, never get FCM back.
    - Which GMS provider other apps were querying is unknown, and an app-queryable one hasn't been identified.
  - **Ping test (2026-11-22 18:17, unplugged, screen off):** every 4 s ping thawed GMS (`sync_binder2`, aligned to the ping cadence). Greezer re-froze it ~5 s later with `from system` each time.
    - FCM reconnected 3 times in ~50 s; each connection died after 7–11 s (`Close err:20`).
    - No ping failed or took over 200 ms, because Greezer thaws GMS in time for the ping to return. The UI therefore stayed "Connected" while GMS was being frozen.
    - Caveat: the phone was charging from 18:02:31 to 18:16:51 (GMS exempt), so only ~1 min of unplugged screen-off was observed.
  - **Diagnose with:** `adb shell dumpsys greezer | grep -E "uid = 10122|uid=10122"` alongside the GcmService dump.

## ColorOS 17 CN findings (OTA analysis, not yet tested on a device)

Source: OnePlus PYB110 full OTA `PYB110_17.0.1.201(CN01)` (Android 17, ColorOS V17.0.0, analyzed 2026-11-23). "Confirmed" means read in code or config. "Inferred" means a conclusion that wasn't traced end to end. Nothing here has been checked on a phone yet.

- **Components:**
  - **Hans** ("OFreezer 3.0") freezes apps. It lives in `oplus-services.jar` (`com.android.server.hans.*`, `com.android.server.am.OplusHansManager`) with the native daemon `/system_ext/bin/hans` (cgroup v2 freezer).
    - Its config is `sys_elsa_config_list.xml`. A copy ships in the Athena APK assets and is installed as `/data/oplus/os/bpm/sys_elsa_config_list.xml`, updatable by RUS (Oplus's cloud config).
  - **Athena** (`com.oplus.athena`, `system_ext/app/Athena`) kills apps: swipe-up, one-key and memory clears.
  - **Battery** (`com.oplus.battery`) holds the per-app battery settings, the Google reachability check and "Sleep standby optimization".
  - **OGuard** (`com.android.server.hans.oguard`) handles abnormal power use.
  - **Startup manager** (`OplusAppStartupManager`) controls autostart.
  - **Doze, SmartDoze and alarm alignment** live in `oplus-service-jobscheduler.jar`.
- **Extraction:** the same approach as the HyperOS OTA, run straight from the zip. A lazy `payload.bin` reader handled the full-OTA ops (XZ/BZ2/replace). An EROFS reader covered the images, which use LZ4 + big pcluster + 0-padding, with no tail-packing or fragments. `dexdump -d` disassembled the code.

### Google restricted mode (`Settings.Secure google_restric_info`)

This is the switch everything GMS-specific depends on. It plays the role of the HyperOS China GMS limit, but goes further.

- **Writer:** Battery `GoogleRestrictionController` (confirmed). It writes 0 when it starts.
- **When it re-checks:**
  - at boot (+5 s)
  - on connectivity changes (+1 s; if the network MCC is not 460, it un-restricts immediately)
  - when the default network becomes validated, while restricted
  - when packages in `google_restriction_list` change
  - when System `customize_control_cn_gms` (the CN "Google services" switch) changes
  - when the cloud list changes
  - **There is no periodic re-check.**
- **Decision (`NetworkDetector`):**
  - On mobile data, it reports "reachable" without probing if the SIM's IMSI isn't 460, **any VPN is active**, or the network MCC isn't Chinese. Otherwise it uses a per-SIM cache (`oplus_google_mobile_probe_cache`; a cached failure fails immediately) or probes.
  - On Wi-Fi, a non-Chinese MCC also means "reachable". Otherwise it probes. **Wi-Fi has no VPN shortcut.**
  - The probe is `GET https://www.google.com/generate_204` then `http://www.google.com/gen_204` (3 s connect timeout, needs a 204). It tries 3 times, 15 s apart, then sets restricted=1.
  - It only probes if GMS is enabled, `customize_control_cn_gms==1`, a network is connected and a Google package is installed. Otherwise restricted means "a Google app is installed".
- **Effects of 1 (confirmed):**
  - **Network:** `OplusNetworkingControlManager.setUidPolicy(uid, 4)` (reject all networks) for GMS, Play Store and configupdater. Packages flagged in Global `oplus_user_change_gms_network_control` are skipped; who writes that flag is unknown.
  - **Hans freezing:** Hans adds GMS, GSF and Play Store to its frozen set as ordinary third-party apps (`HansSceneManager.updateGmsRestrictPkg`). A config `<prevent scene="110" pkg="com.google.android.gms" mask="1111111110"/>` applies with the screen on and off:
    - On freeze, sockets are closed and a firewall deny is applied (`isNetBlackList` → `nwPowerSetFirewall`).
    - Alarms, sync, jobs, broadcasts, providers, binds, service starts and async binder calls no longer wake it (`HansL7Restriction` bits 512/128/…).
  - **Wakeups:** foreground-app wakeups into GMS are blocked (`restrictFgAssWakeupForGmsApp`). GMS partial wake locks can be refused (`OplusProxyWakeLock.acquireWLFilterGms`). GMS wakeup alarms become non-wakeup (`OplusGoogleAlarmRestrict`).
- **Effect of 0:** Hans doesn't freeze GMS at all; it's removed from the frozen set.
- **Readability:** the key isn't declared in the framework's `Settings.Secure`, so the Android 12+ hidden-settings read block shouldn't apply (inferred; test). The change broadcast `oplus.intent.action.google_restrict_change` needs `oplus.permission.OPLUS_COMPONENT_SAFE`, so the app can't receive it.
- **The app can't change it.** The ROM's SettingsProvider (`mutateSecureSetting`) requires `WRITE_SECURE_SETTINGS` or the Oplus signature permission `com.oplus.permission.safe.SETTINGS` for any Secure write (confirmed). The user-grantable "Modify system settings" (`WRITE_SETTINGS`) only covers public `Settings.System` keys, so it can't touch this key, or `customize_control_cn_gms`, which would trigger a re-check.
- **Writing the value is pointless anyway:** even with `WRITE_SECURE_SETTINGS` granted over adb, Battery rewrites it on the next check. The network block is applied separately and isn't lifted by the setting.

### Hans (freezer)

- **App classes** are pushed by Athena (`x/c.s`):
  - 1: third-party
  - 2: third-party whitelist. These are the user's "Allow background activity" apps (Battery → `RemoteGuardElfService.onBgFrozenPrefChanged` → `/data/oplus/os/bpm/bpm.xml` → `x/c.i0`) plus `whitePkg` config entries.
  - 4: Oplus whitelist
  - 8: GMS (`<gms>` entries)
  - 16: system "sys-black" apps
- **Scenes:**
  - `LcdOn` and `LcdOff` freeze classes 1 and 4, so **class 2 isn't frozen** in normal use.
  - `FastFreezer` targets 1, 2 and 4.
  - `nightDeepDoze` and the deep-sleep scenes target 1, 2 and 4 when `deepSleepFreezeWhite` is set (it's `true` in the config), so **allowlisted apps are frozen too**. `nightDeepDoze` is off in the code defaults; the bundled config turns it on for 00:00–06:00.
- **Importance (freeze exemptions), `OplusHansImportance.isHansImportantCaseLocked`.** Each scene has a bitmask:
  - **Screen off:** traffic, audio focus/recorder, GPS, Bluetooth, default launcher/SMS/dialer, VPN, screen recorder, visible activity, preload, "extra", app card, NFC payment, uid-disnet.
  - **Foreground service** (`fg-service` / `bound-fg-service`, proc state ≤ BFGS) is added **only on export ROMs** (`HansSceneItem.<init>`, `getLcdOffSkipImportance`). **On CN an FGS protects nothing.**
  - **Foreground dependency** (`fg-dependency`) means the resumed app's associations, and only with the screen on. There is **no equivalent of the HyperOS virtual-display `checkBind`.**
  - **Visible activity** counts visible windows, excluding Oplus "Mirage" cast displays.
- **Thawing:**
  - An alarm for a frozen app thaws it (`checkAlarmIfRestricted`) unless a prevent rule covers alarms.
  - A sync binder call thaws it (kernel → `unfreezeForKernel` "SyncBinder").
  - Incoming packets thaw only configured IM apps (`imPkg`: WeChat, QQ).
  - `com.google.android.c2dm.intent.RECEIVE` broadcasts thaw any target (`cpnBroadcast type="white"`), so FCM can still reach frozen apps.
  - Frozen apps are firewalled unless allowed network: `isAllowNet` is true for Doze-allowlisted uids and IM types.
- **Ads ID bind:** `com.google.android.gms.ads.identifier.service.START` is on a `cpnService type="black"` list for GMS. Calls with that action don't wake a frozen GMS.
- **Binder service `oplus_freeze` (`OplusHansFreezeManagerService`):** not usable by this app.
  - Keep-alive, frozen-state listeners and requests need `com.oplus.permission.safe.POWER` or `APP_MANAGER`.
  - `startBackgroundRunning` and `requestFreezeDelay` (caller's own uid only) need per-package quota info, which is only created for sys-black config apps.

### Athena (kills) and the startup manager

- **Swipe from recents** (`SwipeUpClearAction`, config `swipeup_forcestop_clear`):
  - It doesn't skip apps with a foreground service, apps in the foreground, or apps locked in recents.
  - It **skips apps with a home-screen widget**, a live wallpaper or an IME (`cc_skip_widget=true`). The widget list comes from `clear/i.B` → `SystemResState.m()` → `AthenaAmManagerInternal.getWidgetsList` → Osense `STATUS_WIDGET`, fed by `AppWidgetServiceImplExtImpl.notifyOnWidgetProviderAddedOrChangedLocked` (confirmed).
  - Other third-party apps get stop type 2, a **force-stop** (`forceStopPackage`: stopped state; alarms cleared, inferred AOSP behavior). It becomes a plain kill if Auto launch, Allow background or a recents lock applies.
- **Other clears** (`sys_athena_config_list.xml`):
  - Only `ai_battery_adaptive_clear` skips FGS apps.
  - Most memory clears skip widget, VPN, Bluetooth and audio apps.
  - None skip anything in `abnormal_memory*` and the excessive-memory clears.
  - After an Athena memory release, restarts of that app are refused for 5 min.
  - Osense memory kills treat third-party FGS apps with no audio, GPS, recorder or traffic as non-perceptible, so killable.
- **Startup manager (CN):** a dead third-party app can't be started without **Auto launch**. That covers `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, alarm broadcasts, `START_STICKY` restarts (`getForbidRSMode()=2`) and notification-listener binds (`system[notificationListener]` → `isAllowStartFromBindService`).
  - `APPWIDGET_ENABLED` is allowlisted; `APPWIDGET_UPDATE` isn't.
  - The Auto launch state is in OplusSettings `startup/startup_dynamic_list.xml`. Its provider needs `OPLUS_COMPONENT_SAFE`, so **the app can't detect it**.
- **GMS** is never force-stopped (`proc_keep`). The abnormal- and excessive-memory clears can still kill it.

### Battery settings and OGuard

- **Per-app background modes** (`f9/h$d`):

  | Mode | Doze allowlist | OGuard `background_value` |
  |---|---|---|
  | 1005 Allow | added, so `isIgnoringBatteryOptimizations()` is true | 2 |
  | 1006 Prohibit | removed | 1 |
  | 1007 Intelligent (default) | default list restored | 0 |

  - **Sideloaded (ECM-restricted) apps default to Prohibit.**
  - The per-app page `PowerControlActivity` isn't exported; use `ACTION_APPLICATION_DETAILS_SETTINGS`.
  - The AOSP `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog only changes the AOSP allowlist, not the Oplus lists (inferred).
- **OGuard wake-lock analysis** (`AppWakelockAnalyzer`):
  - Thresholds: high 5 min, serious 10 min, special 20 min or ≥40% of a window of ≥30 min.
  - For Allow apps: level 1 is a freeze or a silent wake-lock release (`proxyWakeLockForOGuard`). Level 2 is a reminder, or a **force-stop if the wake lock is held ≥1 h and ≥25% of the window**.
  - The reminder path force-stops the app silently if it isn't among the 20 most recent tasks.
  - Exempt: any importance ≥256 (audio, navigation, download, BLE connection, VPN, screen recording, accessibility service enabled, visible window, wallpaper, call). **A foreground service isn't exempt.**
  - Third-party analysis is enabled by cloud config (`thirdAppConfig`). It's off in the code defaults; inferred to be on in the field.

### Night, Doze and alarms

- **Sleep standby optimization** (`com.oplus.deepsleep.ControllerCenter`, Secure `deepsleep_switch_state`):
  - In the predicted sleep window, after ~20 min without motion, it cuts the network device-wide. It either turns off Wi-Fi and mobile data, or uses a netd mode where only whitelisted pids keep network.
  - The whitelist is fixed: SOS, `com.heytap.mcs`, Find My Phone, the network stack, plus WeChat/QQ/WeCom/DingTalk when the IM switch is on. **GMS can't be added**, and it's killed at the next screen-on after the netd mode.
  - With the switch off, the per-uid cut through Hans's night state only affects frozen apps, so it shouldn't touch GMS in normal mode (inferred).
- **Doze:** the CN Oplus list (`my_region/etc/battery/sys_deviceidle_whitelist.xml`) replaces the `system_ext` one and omits GMS. GMS is still on the AOSP allowlist through `google.xml` `allow-in-power-save` (inferred).
- **Alarms:** exact alarms aren't aligned except in the deep-sleep network-cut state. GMS is on `AlignWhiteArray`.
- **Weak-signal firewall** (`OplusHansConnectivityManager`, chain 9): with the screen off on cellular, it denies Doze-allowlisted third-party and GMS uids. Whether config enables it is unknown.

### Compared with HyperOS

| | HyperOS 4 CN | ColorOS 17 CN |
|---|---|---|
| When GMS is frozen | every screen-off (`SYSTEM_BLACK_LIST`), plus the China GMS limit | only while `google_restric_info=1` |
| Google reachability | PowerKeeper `GmsObserver`; only triggers an extra limit | Battery decides, and also blocks GMS's network |
| Exemption the app can give GMS | bound client owning a virtual display (`checkBind`) | none |
| The app's own FGS | safe with "No restrictions" | needs Allow background; FGS isn't an exemption on CN |
| Swipe from recents | process kill, restart via `onCreate` | force-stop unless Auto launch, Allow background, recents lock or a widget |

### Methods for the app (evaluated, not implemented)

- **Recommended:**
  - **Detect restricted mode:** read `google_restric_info` and watch it with a `ContentObserver`. When it's 1, show "Errors detected" with guidance:
    - on mobile data, any active VPN makes ColorOS treat Google as reachable
    - on Wi-Fi, `generate_204` must be reachable for the Battery app, so a VPN must route system apps
    - switching networks forces a re-check
  - **Home-screen widget** (`AppWidgetProvider`, e.g. status + running time + restart button):
    - Athena then skips the app on swipe-up and in most memory clears (confirmed in config and code).
    - Placement is detectable with `AppWidgetManager.getAppWidgetIds`.
    - A tap can restart the service through a `PendingIntent` (user interaction exempts the FGS start).
    - It doesn't affect Hans or OGuard.
  - **ColorOS power card:**
    - Allow background activity (detectable through `isIgnoringBatteryOptimizations()`)
    - Allow auto launch (text only)
    - lock in recents
    - widget placed (detectable)
    - The button opens `ACTION_APPLICATION_DETAILS_SETTINGS`. The current HyperOS SecurityCenter button doesn't resolve here.
  - **Wake lock off (or optional) on ColorOS**, because of OGuard. Freeze detection then has to measure gaps in `uptimeMillis` so normal device sleep isn't counted as a freeze.
  - **Warn about Sleep standby optimization** by reading `deepsleep_switch_state` (value meaning unverified).
  - **Run the virtual-display helper only on HyperOS.** The ping loop is harmless but proves nothing on ColorOS.
  - **Keep the GMS bindings.** They're free and keep GMS at FGS proc state, which Osense memory kills skip (inferred).
- **Optional:**
  - **An inert accessibility service** only exempts the wake lock from OGuard. Hans counts accessibility only with the screen on, and sideloaded apps need "restricted settings" allowed first.
  - **An end-to-end FCM probe** (own Firebase project sending periodic high-priority messages) would measure real delivery, but needs a backend.
- **Rejected:**
  - `VpnService`: conflicts with the user's proxy VPN and would fake reachability.
  - `NotificationListenerService`: system rebinds still need Auto launch.
  - Hans binder APIs: need permissions or a sys-black quota.
  - Silent audio, GPS or BLE keepalives: battery cost, and OGuard checks for abnormal audio (`isValidAudio`).
  - An activity on the app's virtual display to earn "visible activity": untested; the display probably turns off with the screen (inferred).
- **Device checks:**
  - `adb shell settings get secure google_restric_info`
  - `adb shell settings get secure deepsleep_switch_state`
  - `adb shell dumpsys deviceidle whitelist`
  - the GcmService dump above

## Remaining notes

- **`applicationId`** is still `com.example.fcmheartbeat`. Change it before any release.
- **Distribution:** `USE_EXACT_ALARM` is restricted under Google Play policy. It is fine for sideloading or GitHub releases. If the app is ever published on Play, revisit it. (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` was dropped along with the old "Battery settings" button.)
