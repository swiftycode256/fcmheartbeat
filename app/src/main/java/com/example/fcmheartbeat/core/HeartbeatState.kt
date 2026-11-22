package com.example.fcmheartbeat.core

import androidx.annotation.StringRes
import com.example.fcmheartbeat.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** GMS services the heartbeat service binds to. Actions verified with `pm query-services` (see AGENTS.md). */
enum class GmsService(@param:StringRes val label: Int, val action: String) {
    AdsId(R.string.binding_ads_id, "com.google.android.gms.ads.identifier.service.START"),
    Fcm(R.string.binding_fcm, "com.google.android.c2dm.intent.REGISTER"),
}

sealed interface BindingState {
    data object Connecting : BindingState
    data object Connected : BindingState
    data class Error(val message: String) : BindingState
}

/** Lifecycle of the foreground service itself, independent of binding health. */
enum class Phase { Idle, Running, Failed }

/** What the user sees as the overall status (UI status card + service notification title). */
enum class Status { Starting, Active, ErrorsDetected, FailedToStart }

/** Aggregate health of the bindings, used for the summary card and the binding alert. */
sealed interface BindingSummary {
    data object Pending : BindingSummary
    data object AllOk : BindingSummary
    data class SomeFailed(val errors: List<Pair<GmsService, String>>) : BindingSummary
    data class AllFailed(val errors: List<Pair<GmsService, String>>) : BindingSummary
}

data class HeartbeatState(
    val phase: Phase = Phase.Idle,
    /** Exception text when [phase] is [Phase.Failed]. Covers FGS start and wake lock failures. */
    val startError: String? = null,
    /** null until checked. Written by both the service and the UI (on resume). */
    val gmsAvailable: Boolean? = null,
    /** Empty when bindings were not created (service not running, or GMS unavailable). */
    val bindings: Map<GmsService, BindingState> = emptyMap(),
    /** True once any binding reached Connected or Error since the service started. */
    val bindingsSettled: Boolean = false,
    /** Wall-clock start of the current service run; part of the freeze alert's de-dup key. */
    val runStartedAtMillis: Long = 0L,
    /** Freeze-aware running time: elapsed time since start minus time spent frozen. */
    val runningMillis: Long = 0L,
    /** Total time the process was detected as frozen (or suspended) during this run. */
    val frozenMillis: Long = 0L,
) {
    val status: Status
        get() = when (phase) {
            Phase.Idle -> Status.Starting
            Phase.Failed -> Status.FailedToStart
            Phase.Running -> when {
                gmsAvailable == false -> Status.ErrorsDetected
                bindings.values.any { it is BindingState.Connected } -> Status.Active
                bindings.values.any { it is BindingState.Error } -> Status.ErrorsDetected
                !bindingsSettled -> Status.Starting
                // Every binding is reconnecting after having been up (e.g. GMS restarted).
                else -> Status.Active
            }
        }

    val bindingSummary: BindingSummary
        get() {
            if (bindings.isEmpty()) return BindingSummary.Pending
            val errors = GmsService.entries.mapNotNull { svc ->
                (bindings[svc] as? BindingState.Error)?.let { svc to it.message }
            }
            return when {
                errors.isEmpty() && bindings.values.all { it is BindingState.Connected } -> BindingSummary.AllOk
                errors.isEmpty() -> BindingSummary.Pending
                errors.size == bindings.size -> BindingSummary.AllFailed(errors)
                else -> BindingSummary.SomeFailed(errors)
            }
        }

    val freezeDetected: Boolean
        get() = phase == Phase.Running && frozenMillis > FREEZE_THRESHOLD_MILLIS

    companion object {
        const val FREEZE_THRESHOLD_MILLIS = 30_000L
    }
}

/** Process-wide source of truth. The service writes it; the UI and the alert notifier observe it. */
object HeartbeatRepository {
    private val _state = MutableStateFlow(HeartbeatState())
    val state: StateFlow<HeartbeatState> = _state.asStateFlow()

    fun update(transform: (HeartbeatState) -> HeartbeatState) = _state.update(transform)

    fun reportStartFailure(error: Throwable) = update {
        HeartbeatState(
            phase = Phase.Failed,
            startError = error.describe(),
            gmsAvailable = it.gmsAvailable,
        )
    }
}

/** "ClassName: message", which is more useful in a diagnostics app than the bare message. */
fun Throwable.describe(): String {
    val name = this::class.java.simpleName.ifEmpty { this::class.java.name }
    return message?.takeIf { it.isNotBlank() }?.let { "$name: $it" } ?: name
}
