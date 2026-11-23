package com.fcmheartbeat.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.fcmheartbeat.core.HeartbeatRepository
import com.fcmheartbeat.core.HeartbeatState
import com.fcmheartbeat.core.Phase
import com.fcmheartbeat.core.Status
import com.fcmheartbeat.core.describe
import com.fcmheartbeat.notify.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * `specialUse` foreground service. It holds a partial wake lock and binds to GMS services so
 * the system keeps GMS (and its FCM connection) at foreground-service priority.
 *
 * Startup order: startForeground → wake lock → GMS check + bindings. See AGENTS.md.
 */
class HeartbeatService : LifecycleService() {

    private lateinit var bindings: GmsBindingManager
    private lateinit var shield: VirtualDisplayShield
    private var wakeLock: PowerManager.WakeLock? = null
    private var runJobs: List<Job> = emptyList()
    private val running get() = runJobs.isNotEmpty()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate pid=${Process.myPid()}")
        bindings = GmsBindingManager(this, lifecycleScope)
        shield = VirtualDisplayShield(this)
        // Keep the ongoing notification title in sync with the overall status.
        lifecycleScope.launch {
            HeartbeatRepository.state.map { it.status }.distinctUntilChanged().collect { status ->
                if (running) Notifications.updateStatus(this@HeartbeatService, status)
            }
        }
        // HyperOS's swipe-kill restart (SwipeUpClean → "restart of crashed service") recreates the
        // service but never delivers onStartCommand, which left it foreground but doing nothing.
        // Start from here as well; a later onStartCommand then only retries.
        if (promoteToForeground()) initialize()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "onStartCommand action=${intent?.action} startId=$startId running=$running")
        // Every startForegroundService() call must be answered with startForeground(), even when
        // the service is already running, or the system crashes the app.
        if (!promoteToForeground()) return START_NOT_STICKY

        when {
            intent?.action == ACTION_RESTART -> {
                teardown()
                initialize()
            }
            !running -> initialize() // First start, or a START_STICKY restart (null intent).
            else -> { // Already running: retry failed bindings/helpers right away.
                bindings.refresh()
                shield.start()
            }
        }
        return if (running) START_STICKY else START_NOT_STICKY
    }

    private fun promoteToForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            Notifications.ID_STATUS,
            Notifications.buildStatus(
                this,
                if (running) HeartbeatRepository.state.value.status else Status.Starting,
            ),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException, SecurityException, ...
        fail(e)
        false
    }

    private fun initialize() {
        Log.i(TAG, "initialize: acquiring wake lock")
        try {
            acquireWakeLock()
        } catch (e: Exception) {
            fail(e)
            return
        }

        val startElapsed = SystemClock.elapsedRealtime()
        HeartbeatRepository.update {
            HeartbeatState(
                phase = Phase.Running,
                gmsAvailable = it.gmsAvailable,
                runStartedAtMillis = System.currentTimeMillis(),
            )
        }
        runJobs = listOf(startTicker(startElapsed), startRetryLoop(), startPingLoop())
        Log.i(TAG, "initialize: wake lock held, starting helpers")
        shield.start()
        bindings.refresh()
        Notifications.updateStatus(this, HeartbeatRepository.state.value.status)
        Log.i(TAG, "initialize: done, status=${HeartbeatRepository.state.value.status}")
    }

    @SuppressLint("WakelockTimeout") // Held for the lifetime of the service by design.
    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
            ?: throw IllegalStateException("PowerManager unavailable")
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /**
     * Freeze detection by measuring the gap between ticks. Counting ticks would drift: each
     * delay(1000) overshoots slightly, which would add up to false freeze warnings over a day.
     * elapsedRealtime() includes deep sleep, so a suspend despite the wake lock also counts.
     */
    private fun startTicker(startElapsed: Long) = lifecycleScope.launch(Dispatchers.Default) {
        var last = startElapsed
        while (isActive) {
            delay(TICK_MILLIS)
            val now = SystemClock.elapsedRealtime()
            val gap = now - last
            last = now
            val frozen = if (gap > FREEZE_GAP_MILLIS) gap - TICK_MILLIS else 0L
            if (frozen > 0) Log.w(TAG, "Process was frozen for ${frozen}ms")
            HeartbeatRepository.update {
                if (it.phase != Phase.Running) return@update it
                val totalFrozen = it.frozenMillis + frozen
                it.copy(frozenMillis = totalFrozen, runningMillis = now - startElapsed - totalFrozen)
            }
        }
    }

    /** Keeps GMS thawed under HyperOS Greezer and surfaces an unresponsive binding. */
    private fun startPingLoop() = lifecycleScope.launch {
        while (isActive) {
            delay(GmsBindingManager.PING_INTERVAL_MILLIS)
            bindings.ping()
        }
    }

    private fun startRetryLoop() = lifecycleScope.launch {
        while (isActive) {
            delay(RETRY_MILLIS)
            if (bindings.needsRetry) bindings.refresh()
            if (shield.needsRetry) shield.start()
        }
    }

    private fun teardown() {
        runJobs.forEach { it.cancel() }
        runJobs = emptyList()
        bindings.unbindAll()
        shield.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun fail(error: Throwable) {
        Log.e(TAG, "Unable to start foreground service", error)
        teardown()
        HeartbeatRepository.update {
            HeartbeatState(
                phase = Phase.Failed,
                startError = error.describe(),
                gmsAvailable = it.gmsAvailable
            )
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Swiping the app away on HyperOS kills the service. Schedule an exact alarm that starts it
     * again. Broadcasts from exact alarms are allowed to start foreground services from the
     * background. If the system force-stopped the app, the alarm is dropped too (see AGENTS.md).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (running) scheduleRestart(this)
    }

    override fun onDestroy() {
        val wasRunning = running
        teardown()
        if (wasRunning) {
            HeartbeatRepository.update { HeartbeatState(gmsAvailable = it.gmsAvailable) }
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.fcmheartbeat.action.START"
        const val ACTION_RESTART = "com.fcmheartbeat.action.RESTART"

        private const val TAG = "HeartbeatService"
        private const val WAKE_LOCK_TAG = "FCMHeartbeat::Heartbeat"
        private const val TICK_MILLIS = 1_000L
        private const val FREEZE_GAP_MILLIS = 2_000L
        private const val RETRY_MILLIS = 15_000L
        private const val RESTART_DELAY_MILLIS = 1_000L

        fun scheduleRestart(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, StartReceiver::class.java).setAction(StartReceiver.ACTION_RESTART_ALARM),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val triggerAt = SystemClock.elapsedRealtime() + RESTART_DELAY_MILLIS
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            try {
                if (canExact) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Unable to schedule restart alarm", e)
            }
        }
    }
}
