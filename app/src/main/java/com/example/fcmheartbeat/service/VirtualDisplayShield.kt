package com.example.fcmheartbeat.service

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import com.example.fcmheartbeat.R
import com.example.fcmheartbeat.core.BindingState
import com.example.fcmheartbeat.core.Helper
import com.example.fcmheartbeat.core.HeartbeatRepository
import com.example.fcmheartbeat.core.describe

/**
 * Owns a tiny private virtual display so HyperOS's freezer skips GMS.
 *
 * Greezer's `checkBind` skips freezing any uid that has a bound client which "uses a virtual
 * display" (`VirtualDisplayAdapter.isUsingVirtualDisplayLocked`: some virtual display has
 * `ownerUid == client uid`). This app is bound to GMS, so owning one shields GMS from the
 * network-cutting screen-off freezes. See AGENTS.md "Virtual-display shield".
 *
 * The display is private (flags 0, no permission needed), 1x1 and has no surface, so nothing is
 * ever composited and it costs no battery.
 */
@MainThread
class VirtualDisplayShield(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var display: VirtualDisplay? = null

    /** A released display whose `onStopped` hasn't arrived yet. */
    private var stopping: VirtualDisplay? = null

    /** A start requested while [stopping] was still shutting down. */
    private var startPending = false

    private val stopTimeout = Runnable {
        Log.w(TAG, "Old virtual display did not report onStopped in time")
        onOldDisplayStopped()
    }

    /** True when the display is missing and the retry loop should try again. */
    val needsRetry: Boolean get() = display == null && !startPending

    fun start() {
        if (display != null) return
        publish(BindingState.Connecting)
        if (stopping != null) {
            // Restart: creating the new display now would race the old one's onStopped. Wait for it.
            Log.i(TAG, "Waiting for the old virtual display to stop")
            startPending = true
            return
        }
        try {
            val dm = context.getSystemService(DisplayManager::class.java)
                ?: throw IllegalStateException("DisplayManager unavailable")
            var created: VirtualDisplay? = null
            // Each display gets its own callback, so a late onStopped from an old instance is
            // never mistaken for the current one being stopped.
            val callback = object : VirtualDisplay.Callback() {
                override fun onStopped() = onDisplayStopped(created)
            }
            created = dm.createVirtualDisplay(NAME, SIZE, SIZE, DENSITY_DPI, null, 0, callback, handler)
                ?: throw IllegalStateException("createVirtualDisplay returned null")
            display = created
            Log.i(TAG, "Virtual display created: id=${created.display?.displayId}")
            publish(BindingState.Connected)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to create virtual display", e)
            display = null
            publish(BindingState.Error(e.describe()))
        }
    }

    fun release() {
        startPending = false
        display?.let { old ->
            display = null
            // Should a previous release still be pending, its onStopped is ignored from now on.
            stopping = old
            handler.removeCallbacks(stopTimeout)
            handler.postDelayed(stopTimeout, STOP_TIMEOUT_MILLIS)
            old.release()
        }
        HeartbeatRepository.update { it.copy(helpers = it.helpers - Helper.VirtualDisplay) }
    }

    private fun onDisplayStopped(instance: VirtualDisplay?) {
        when {
            instance == null -> Unit
            instance === stopping -> onOldDisplayStopped()
            instance === display -> {
                Log.w(TAG, "Virtual display was stopped by the system")
                instance.release()
                display = null
                publish(BindingState.Error(context.getString(R.string.helper_error_vd_stopped)))
            }
            // Otherwise an older released display (superseded by a newer release): ignore.
        }
    }

    private fun onOldDisplayStopped() {
        handler.removeCallbacks(stopTimeout)
        stopping = null
        Log.i(TAG, "Old virtual display stopped")
        if (startPending) {
            startPending = false
            start()
        }
    }

    private fun publish(state: BindingState) = HeartbeatRepository.update {
        if (it.helpers[Helper.VirtualDisplay] == state) it
        else it.copy(helpers = it.helpers + (Helper.VirtualDisplay to state))
    }

    private companion object {
        const val TAG = "VirtualDisplayShield"
        const val NAME = "FCMHeartbeat"
        const val SIZE = 1
        const val DENSITY_DPI = 160
        const val STOP_TIMEOUT_MILLIS = 2_000L
    }
}
