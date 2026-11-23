package com.fcmheartbeat.notify

import android.content.Context
import androidx.core.content.edit
import com.fcmheartbeat.R
import com.fcmheartbeat.core.BindingSummary
import com.fcmheartbeat.core.GmsService
import com.fcmheartbeat.core.HeartbeatState
import com.fcmheartbeat.core.HelperSummary
import com.fcmheartbeat.core.Phase
import kotlin.collections.iterator

/**
 * Turns state into error/warning notifications.
 *
 * Each alert type has a fixed notification ID. An alert is (re)posted only when its de-dup key
 * (type + message) differs from the last one posted. Keys are persisted so a crash/restart loop
 * doesn't repeat the same alert. A type is cleared (notification cancelled, key forgotten) only
 * when its condition is positively resolved, never just because the state is unknown, e.g. right
 * after the process restarts.
 */
object AlertNotifier {
    enum class Type(val notificationId: Int) {
        Freeze(10),
        GmsUnavailable(11),
        Binding(12), // "Unable to bind to services" and "Some services failed to bind" share an ID.
        StartFailure(13),
        Helper(14),
    }

    sealed interface Decision {
        data class Show(val key: String, val title: String, val text: String) : Decision
        data object Clear : Decision
        data object Keep : Decision
    }

    private const val PREFS = "alerts"

    fun decide(context: Context, state: HeartbeatState): Map<Type, Decision> = mapOf(
        Type.StartFailure to when {
            state.phase == Phase.Failed && state.startError != null -> Decision.Show(
                key = "start:${state.startError}",
                title = context.getString(R.string.msg_start_failed_title),
                text = state.startError,
            )
            state.phase == Phase.Running -> Decision.Clear
            else -> Decision.Keep
        },
        Type.GmsUnavailable to when {
            state.phase == Phase.Running && state.gmsAvailable == false -> Decision.Show(
                key = "gms",
                title = context.getString(R.string.msg_gms_title),
                text = context.getString(R.string.msg_gms_body),
            )
            state.gmsAvailable == true -> Decision.Clear
            else -> Decision.Keep
        },
        Type.Binding to when {
            state.phase != Phase.Running -> Decision.Keep
            state.gmsAvailable == false -> Decision.Clear // Covered by the GMS alert.
            else -> when (val summary = state.bindingSummary) {
                is BindingSummary.AllFailed -> Decision.Show(
                    key = "all:" + summary.errors.joinToString("|") { "${it.first}=${it.second}" },
                    title = context.getString(R.string.msg_bind_all_failed_title),
                    text = errorLines(context, summary.errors),
                )
                is BindingSummary.SomeFailed -> Decision.Show(
                    key = "some:" + summary.errors.joinToString("|") { "${it.first}=${it.second}" },
                    title = context.getString(R.string.msg_bind_some_failed_title),
                    text = errorLines(context, summary.errors) + "\n" +
                        context.getString(R.string.msg_bind_impact),
                )
                BindingSummary.AllOk -> Decision.Clear
                BindingSummary.Pending -> Decision.Keep
            }
        },
        Type.Helper to when {
            state.phase != Phase.Running -> Decision.Keep
            else -> when (val summary = state.helperSummary) {
                is HelperSummary.Failed -> Decision.Show(
                    key = "helper:" + summary.errors.joinToString("|") { "${it.first}=${it.second}" },
                    title = context.getString(R.string.msg_helpers_failed_title),
                    text = summary.errors.joinToString("\n") { (h, m) -> "${context.getString(h.label)}: $m" },
                )
                HelperSummary.AllOk -> Decision.Clear
                HelperSummary.Pending -> Decision.Keep
            }
        },
        // Never auto-cleared: the user should still see it after a restart. The key includes the
        // run start time, so this posts at most once per service run.
        Type.Freeze to if (state.freezeDetected) {
            Decision.Show(
                key = "freeze:${state.runStartedAtMillis}",
                title = context.getString(R.string.msg_freeze_title),
                text = context.getString(R.string.alert_freeze_body),
            )
        } else {
            Decision.Keep
        },
    )

    fun apply(context: Context, decisions: Map<Type, Decision>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for ((type, decision) in decisions) {
            val stored = prefs.getString(type.name, null)
            when (decision) {
                is Decision.Show -> if (decision.key != stored) {
                    Notifications.notifySafely(
                        context,
                        type.notificationId,
                        Notifications.buildAlert(context, decision.title, decision.text),
                    )
                    prefs.edit { putString(type.name, decision.key) }
                }
                Decision.Clear -> if (stored != null) {
                    Notifications.cancel(context, type.notificationId)
                    prefs.edit { remove(type.name) }
                }
                Decision.Keep -> Unit
            }
        }
    }

    fun errorLines(context: Context, errors: List<Pair<GmsService, String>>): String =
        errors.joinToString("\n") { (svc, message) -> "${context.getString(svc.label)}: $message" }
}
