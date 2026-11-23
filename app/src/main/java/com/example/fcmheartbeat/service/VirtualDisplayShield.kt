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

    /** True when the display is missing and the retry loop should try again. */
    val needsRetry: Boolean get() = display == null

    private val callback = object : VirtualDisplay.Callback() {
        override fun onStopped() {
            if (display == null) return
            Log.w(TAG, "Virtual display was stopped by the system")
            display?.release()
            display = null
            publish(BindingState.Error(context.getString(R.string.helper_error_vd_stopped)))
        }
    }

    fun start() {
        if (display != null) return
        publish(BindingState.Connecting)
        try {
            val dm = context.getSystemService(DisplayManager::class.java)
                ?: throw IllegalStateException("DisplayManager unavailable")
            display = dm.createVirtualDisplay(NAME, SIZE, SIZE, DENSITY_DPI, null, 0, callback, handler)
                ?: throw IllegalStateException("createVirtualDisplay returned null")
            Log.i(TAG, "Virtual display created: id=${display?.display?.displayId}")
            publish(BindingState.Connected)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to create virtual display", e)
            display = null
            publish(BindingState.Error(e.describe()))
        }
    }

    fun release() {
        display?.release()
        display = null
        HeartbeatRepository.update { it.copy(helpers = it.helpers - Helper.VirtualDisplay) }
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
    }
}
