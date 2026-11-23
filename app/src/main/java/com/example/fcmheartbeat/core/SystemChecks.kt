package com.example.fcmheartbeat.core

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
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

    /**
     * HyperOS's own app settings page (Security app), which holds the battery saver
     * ("Unrestricted") and Autostart switches. Absent on non-Xiaomi devices.
     */
    private fun appSettingsIntent(context: Context) = Intent()
        .setComponent(ComponentName(SECURITY_CENTER, "com.miui.appmanager.ApplicationsDetailsActivity"))
        .putExtra("package_name", context.packageName)
        .putExtra("miui.intent.extra.USER_ID", Process.myUserHandle().hashCode()) // == user id

    fun isAppSettingsPageAvailable(context: Context): Boolean =
        context.packageManager.resolveActivity(appSettingsIntent(context), 0)?.activityInfo?.exported == true

    fun openAppSettings(context: Context): Boolean = launch(context, appSettingsIntent(context))

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (!launch(context, intent)) launch(context, appDetailsIntent(context))
    }

    private const val SECURITY_CENTER = "com.miui.securitycenter"

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
