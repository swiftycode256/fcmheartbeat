package com.fcmheartbeat.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import com.fcmheartbeat.R
import com.fcmheartbeat.core.BindingState
import com.fcmheartbeat.core.GMS_PACKAGE
import com.fcmheartbeat.core.Gms
import com.fcmheartbeat.core.GmsService
import com.fcmheartbeat.core.HeartbeatRepository
import com.fcmheartbeat.core.describe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Holds the bindings to GMS services. The bindings raise the GMS process to our
 * (foreground service) priority.
 *
 * On HyperOS, Greezer freezes GMS on screen-off regardless of bindings, which kills the FCM
 * socket (see AGENTS.md). A synchronous binder call into a frozen process makes Greezer thaw it
 * (`sync_binder2`) and re-freeze it later without the network restrictions, so [ping] is called
 * periodically to keep GMS reachable. A failed ping marks that binding as Error until a later
 * ping succeeds.
 *
 * Main-thread confined: ServiceConnection callbacks arrive on the main thread, and every
 * caller (service commands, retry and ping loops) runs there too. Pings themselves run on IO
 * threads because a call into a frozen process may block.
 */
@MainThread
class GmsBindingManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {

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

    /**
     * Pings every connection that exposes a binder (in practice the Ads ID service; the FCM
     * proxy returns a null binding). Connections with a ping still in flight are skipped.
     */
    suspend fun ping() {
        for (connection in connections.values.toList()) {
            val binder = connection.binder ?: continue
            if (connection.pingInFlight) continue
            val failure = pingWithTimeout(connection, binder)
            if (!connection.isCurrent || connection.binder !== binder) continue
            val current = HeartbeatRepository.state.value.bindings[connection.service]
            when {
                failure != null -> {
                    if (current != BindingState.Error(failure)) {
                        Log.w(TAG, "Ping to ${connection.service} failed: $failure")
                    }
                    publish(connection.service, BindingState.Error(failure))
                }
                current is BindingState.Error -> {
                    Log.i(TAG, "Ping to ${connection.service} succeeded again")
                    publish(connection.service, BindingState.Connected)
                }
            }
        }
    }

    /** Returns null on success, or a user-facing failure message. */
    private suspend fun pingWithTimeout(connection: Connection, binder: IBinder): String? {
        connection.pingInFlight = true
        val result = CompletableDeferred<Boolean>()
        val started = SystemClock.elapsedRealtime()
        scope.launch(Dispatchers.IO) {
            val alive = try {
                binder.pingBinder()
            } catch (e: Exception) {
                Log.w(TAG, "pingBinder threw", e)
                false
            }
            val took = SystemClock.elapsedRealtime() - started
            if (took > SLOW_PING_LOG_MILLIS) Log.i(TAG, "Ping to ${connection.service} took ${took}ms (alive=$alive)")
            result.complete(alive)
            scope.launch(Dispatchers.Main) { connection.pingInFlight = false }
        }
        val alive = withTimeoutOrNull(PING_TIMEOUT_MILLIS) { result.await() }
        return when (alive) {
            true -> null
            false -> context.getString(R.string.binding_error_ping_failed)
            null -> context.getString(R.string.binding_error_ping_timeout, PING_TIMEOUT_MILLIS / 1000)
        }
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
            if (it.bindings[service] == state) return@update it
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
        /** Remote binder for pinging; null until connected, or when the service returns none. */
        var binder: IBinder? = null
        var pingInFlight = false

        /** Ignore callbacks from a connection that was replaced or torn down. */
        val isCurrent get() = connections[service] === this

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (!isCurrent) return
            this.binder = binder
            publish(service, BindingState.Connected)
        }

        /** The service returned no interface. The binding still holds (and still elevates GMS). */
        override fun onNullBinding(name: ComponentName?) {
            if (!isCurrent) return
            binder = null
            publish(service, BindingState.Connected)
        }

        /** GMS process died; the system re-binds on its own once it restarts. */
        override fun onServiceDisconnected(name: ComponentName?) {
            if (!isCurrent) return
            binder = null
            publish(service, BindingState.Connecting)
        }

        /** The system gave up on this binding (e.g. GMS updated/disabled). Re-create it now. */
        override fun onBindingDied(name: ComponentName?) {
            if (!isCurrent) return
            Log.i(TAG, "Binding to $service died; re-binding")
            binder = null
            safeUnbind(this)
            connections.remove(service)
            refresh()
        }
    }

    companion object {
        private const val TAG = "GmsBindingManager"
        private const val BIND_FLAGS = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT

        /**
         * Below Greezer's 5 s quick-freeze timeout (`fz_timeout=5000` in `dumpsys greezer`), so
         * GMS is thawed again before (or right as) it gets re-frozen.
         */
        const val PING_INTERVAL_MILLIS = 4_000L
        private const val PING_TIMEOUT_MILLIS = 2_000L
        private const val SLOW_PING_LOG_MILLIS = 200L
    }
}
