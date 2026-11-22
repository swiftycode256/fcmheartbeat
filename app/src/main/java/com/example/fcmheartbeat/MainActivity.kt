package com.example.fcmheartbeat

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.fcmheartbeat.core.Gms
import com.example.fcmheartbeat.core.HeartbeatController
import com.example.fcmheartbeat.core.HeartbeatRepository
import com.example.fcmheartbeat.core.PowerChecks
import com.example.fcmheartbeat.ui.DeviceChecks
import com.example.fcmheartbeat.ui.HeartbeatActions
import com.example.fcmheartbeat.ui.HeartbeatScreen
import com.example.fcmheartbeat.ui.theme.FCMHeartbeatTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FCMHeartbeatTheme {
                HeartbeatRoute()
            }
        }
    }
}

@Composable
private fun HeartbeatRoute() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val state by HeartbeatRepository.state.collectAsStateWithLifecycle()
    var checks by remember { mutableStateOf(DeviceChecks.read(context)) }
    val diagnosticsUnavailable = stringResource(R.string.diagnostics_unavailable)

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { checks = DeviceChecks.read(context) }

    // Opening (or returning to) the app starts the service, or makes a running service retry
    // failed bindings right away. Settings that have no change broadcast are re-read here too.
    LifecycleResumeEffect(Unit) {
        checks = DeviceChecks.read(context)
        HeartbeatRepository.update { it.copy(gmsAvailable = Gms.isAvailable(context)) }
        HeartbeatController.start(context)
        onPauseOrDispose { }
    }

    // Ask for notification permission once, on first launch (Android 13+).
    LaunchedEffect(Unit) {
        if (needsNotificationPermission(context) && !UiPrefs.askedForNotifications(context)) {
            UiPrefs.markAskedForNotifications(context)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    HeartbeatScreen(
        state = state,
        checks = checks,
        snackbarHostState = snackbarHostState,
        actions = HeartbeatActions(
            onRestart = { HeartbeatController.restart(context) },
            onRetry = { HeartbeatController.start(context) },
            onOpenDiagnostics = {
                try {
                    context.startActivity(Gms.diagnosticsIntent())
                } catch (_: ActivityNotFoundException) {
                    scope.launch { snackbarHostState.showSnackbar(diagnosticsUnavailable) }
                } catch (_: SecurityException) {
                    scope.launch { snackbarHostState.showSnackbar(diagnosticsUnavailable) }
                }
            },
            onOpenBatterySettings = { PowerChecks.openBatterySettings(context) },
            onOpenAutostart = { PowerChecks.openAutostartSettings(context) },
            onAllowNotifications = {
                // Ask again while the system still allows it; after that, only Settings can grant it.
                val canAsk = needsNotificationPermission(context) && activity != null &&
                    activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                if (canAsk) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    PowerChecks.openNotificationSettings(context)
                }
            },
        ),
    )
}

private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

private object UiPrefs {
    private const val NAME = "ui"
    private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"

    fun askedForNotifications(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY_ASKED_NOTIFICATIONS, false)

    fun markAskedForNotifications(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit { putBoolean(KEY_ASKED_NOTIFICATIONS, true) }
}
