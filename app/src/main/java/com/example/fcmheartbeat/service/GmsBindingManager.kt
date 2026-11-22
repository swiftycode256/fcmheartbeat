package com.example.fcmheartbeat.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.annotation.MainThread
import com.example.fcmheartbeat.R
import com.example.fcmheartbeat.core.BindingState
import com.example.fcmheartbeat.core.GMS_PACKAGE
import com.example.fcmheartbeat.core.Gms
import com.example.fcmheartbeat.core.GmsService
import com.example.fcmheartbeat.core.HeartbeatRepository
import com.example.fcmheartbeat.core.describe

/**
 * Holds the bindings to GMS services. The bindings raise the GMS process to our
 * (foreground service) priority, which is the whole point of the app.
 *
 * Main-thread confined: ServiceConnection callbacks arrive on the main thread, and every
 * caller (service commands, retry loop) runs there too.
 */
@MainThread
class GmsBindingManager(private val context: Context) {

    private val connections = mutableMapOf<GmsService, Connection>()

    /** True when something is not bound and the retry loop should try again. */
    val needsRetry: Boolean
        get() = connections.size < GmsService.entries.size

    /** Re-check GMS, then bind every service that has no live connection. Safe to call often. */
    fun refresh() {
        val available = Gms.isAvailable(context)
        HeartbeatRepository.update { it.copy(gmsAvailable = available) }
        if (!available) {
            unbindAll()
            return
        }
        for (service in GmsService.entries) {
            if (service !in connections) bind(service)
        }
    }

    fun unbindAll() {
        connections.values.forEach { safeUnbind(it) }
        connections.clear()
        HeartbeatRepository.update { it.copy(bindings = emptyMap(), bindingsSettled = false) }
    }

    private fun bind(service: GmsService) {
        val intent = Intent(service.action).setPackage(GMS_PACKAGE)
        val connection = Connection(service)
        try {
            if (context.bindService(intent, connection, BIND_FLAGS)) {
                connections[service] = connection
                publish(service, BindingState.Connecting)
            } else {
                // Per the docs, release the connection even when the bind was rejected.
                safeUnbind(connection)
                publish(service, BindingState.Error(context.getString(R.string.binding_error_not_found)))
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "bindService($service) failed", e)
            publish(service, BindingState.Error(e.describe()))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "bindService($service) failed", e)
            publish(service, BindingState.Error(e.describe()))
        }
    }

    private fun publish(service: GmsService, state: BindingState) {
        HeartbeatRepository.update {
            it.copy(
                bindings = it.bindings + (service to state),
                bindingsSettled = it.bindingsSettled || state != BindingState.Connecting,
            )
        }
    }

    private fun safeUnbind(connection: ServiceConnection) {
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // Not registered (e.g. the bind was rejected outright).
        }
    }

    private inner class Connection(val service: GmsService) : ServiceConnection {
        /** Ignore callbacks from a connection that was replaced or torn down. */
        private val isCurrent get() = connections[service] === this

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (isCurrent) publish(service, BindingState.Connected)
        }

        /** The service returned no interface. The binding still holds (and still elevates GMS). */
        override fun onNullBinding(name: ComponentName?) {
            if (isCurrent) publish(service, BindingState.Connected)
        }

        /** GMS process died; the system re-binds on its own once it restarts. */
        override fun onServiceDisconnected(name: ComponentName?) {
            if (isCurrent) publish(service, BindingState.Connecting)
        }

        /** The system gave up on this binding (e.g. GMS updated/disabled). Re-create it now. */
        override fun onBindingDied(name: ComponentName?) {
            if (!isCurrent) return
            Log.i(TAG, "Binding to $service died; re-binding")
            safeUnbind(this)
            connections.remove(service)
            refresh()
        }
    }

    private companion object {
        const val TAG = "GmsBindingManager"
        const val BIND_FLAGS = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
    }
}
