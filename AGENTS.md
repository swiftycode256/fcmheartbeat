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
  - **Status: does not keep FCM connected on HyperOS 4 CN.** Greezer re-freezes 5 s after each thaw regardless of pings, often with network restrictions. Pings never fail, so they also can't detect the freeze. See the Greezer device finding below.

## Freeze detection & running time

Do **not** count ticks: `delay(1000)` overshoots by a few ms per tick, which adds up to minutes over a day and would trigger false freeze warnings. Use gap measurement instead:

- A ticker in the service runs every 1 s. On each tick: `gap = elapsedRealtime() - lastTick`. If `gap > 2000 ms`, add `gap - 1000` to `frozenMs` and record the event (start time, duration).
- `runningTime = elapsedRealtime() - serviceStartElapsed - frozenMs`. This is the freeze-aware counter shown in the UI status card. The notification shows no timer.
- Show the "Background activity restrictions detected" card when `frozenMs > 30 s` since the service started, with the total ("for 45s"). It stays visible until the service restarts.
- Post the matching notification once per service run, when `frozenMs` first crosses 30 s. The de-dup key includes the service start time, so constant freezes don't spam notifications, but a restarted service can notify again.
- `elapsedRealtime` includes deep sleep, so a device that suspends despite the wake lock also counts as frozen. That is intended, since it is the same failure.

## Power management detection

- "Unrestricted" = `PowerManager.isIgnoringBatteryOptimizations(packageName)`. Also treat `ActivityManager.isBackgroundRestricted()` (API 28+) as restricted.
- Re-check on every `ON_RESUME`. There is no public broadcast for this setting, and resume covers the user coming back from Settings.
- The card gets action buttons:
  - **Battery settings**: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (needs the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission), falling back to the app details page
  - **Autostart**: `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`, guarded by try/catch and hidden if it does not resolve
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
  - **Virtual-display shield (verified in code, not yet tested on device):**
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

## Remaining notes

- **`applicationId`** is still `com.example.fcmheartbeat`. Change it before any release.
- **Distribution:** `USE_EXACT_ALARM` and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` are restricted under Google Play policy. Both are fine for sideloading or GitHub releases. If the app is ever published on Play, revisit them.
