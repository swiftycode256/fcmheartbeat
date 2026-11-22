package com.example.fcmheartbeat

import android.app.Application
import com.example.fcmheartbeat.core.HeartbeatRepository
import com.example.fcmheartbeat.notify.AlertNotifier
import com.example.fcmheartbeat.notify.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class HeartbeatApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        // Process-wide observer so alerts work whether or not the service is running (e.g. when
        // the start itself failed).
        appScope.launch {
            HeartbeatRepository.state
                .map { AlertNotifier.decide(this@HeartbeatApp, it) }
                .distinctUntilChanged()
                .collect { AlertNotifier.apply(this@HeartbeatApp, it) }
        }
    }
}
