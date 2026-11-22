package com.example.fcmheartbeat.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.fcmheartbeat.core.HeartbeatController

/** Starts the heartbeat service on boot, after an app update, and from the restart alarm. */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_RESTART_ALARM -> HeartbeatController.start(context)
        }
    }

    companion object {
        const val ACTION_RESTART_ALARM = "com.example.fcmheartbeat.action.RESTART_ALARM"
    }
}
