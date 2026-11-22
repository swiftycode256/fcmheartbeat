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
