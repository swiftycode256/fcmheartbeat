package com.example.fcmheartbeat.core

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

const val GMS_PACKAGE = "com.google.android.gms"

object Gms {
    fun isAvailable(context: Context): Boolean = try {
        context.packageManager.getApplicationInfo(GMS_PACKAGE, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun diagnosticsIntent(): Intent = Intent()
        .setComponent(ComponentName(GMS_PACKAGE, "$GMS_PACKAGE.gcm.GcmDiagnostics"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

object PowerChecks {
    /** "Unrestricted" battery usage. On HyperOS this tracks the per-app battery setting (AGENTS.md). */
    fun isUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        val ignoring = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        val restricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted ?: false
        } else {
            false
        }
        return ignoring && !restricted
    }

    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    private val autostartIntent: Intent
        get() = Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
        )

    fun isAutostartPageAvailable(context: Context): Boolean =
        context.packageManager.resolveActivity(autostartIntent, 0) != null

    fun openAutostartSettings(context: Context): Boolean = launch(context, autostartIntent)

    /**
     * Opens the system "let app always run in background?" prompt, falling back to the app details
     * page (where HyperOS shows the battery saver setting).
     */
    @SuppressLint("BatteryLife") // Sideloaded utility whose whole purpose is background execution.
    fun openBatterySettings(context: Context) {
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", context.packageName, null),
        )
        if (!PowerChecks.isUnrestricted(context) && launch(context, request)) return
        launch(context, appDetailsIntent(context))
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (!launch(context, intent)) launch(context, appDetailsIntent(context))
    }

    private fun appDetailsIntent(context: Context) = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
