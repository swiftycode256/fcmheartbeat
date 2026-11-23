package com.fcmheartbeat.core

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.fcmheartbeat.service.HeartbeatService

/** Entry point for everything that needs to start or poke the heartbeat service. */
object HeartbeatController {
    private const val TAG = "HeartbeatController"

    /** Start the service, or ask a running one to retry failed bindings right away. */
    fun start(context: Context) = send(context, HeartbeatService.ACTION_START)

    /** Tear everything down and start from scratch (resets the running time). */
    fun restart(context: Context) = send(context, HeartbeatService.ACTION_RESTART)

    private fun send(context: Context, action: String) {
        val intent = Intent(context, HeartbeatService::class.java).setAction(action)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: IllegalStateException) {
            // Includes ForegroundServiceStartNotAllowedException (Android 12+ background start).
            Log.w(TAG, "Unable to start foreground service", e)
            HeartbeatRepository.reportStartFailure(e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to start foreground service", e)
            HeartbeatRepository.reportStartFailure(e)
        }
    }
}
