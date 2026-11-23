package com.fcmheartbeat.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fcmheartbeat.R
import com.fcmheartbeat.core.BindingState
import com.fcmheartbeat.core.BindingSummary
import com.fcmheartbeat.core.GmsService
import com.fcmheartbeat.core.HeartbeatState
import com.fcmheartbeat.core.Helper
import com.fcmheartbeat.core.HelperSummary
import com.fcmheartbeat.core.Phase
import com.fcmheartbeat.core.PowerChecks
import com.fcmheartbeat.core.Status
import com.fcmheartbeat.ui.theme.FCMHeartbeatTheme
import java.util.Locale

/** Device settings the UI re-reads on every resume (no broadcasts exist for these). */
data class DeviceChecks(
    val unrestricted: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val appSettingsAvailable: Boolean = false,
) {
    companion object {
        fun read(context: Context) = DeviceChecks(
            unrestricted = PowerChecks.isUnrestricted(context),
            notificationsEnabled = PowerChecks.notificationsEnabled(context),
            appSettingsAvailable = PowerChecks.isAppSettingsPageAvailable(context),
        )
    }
}

class HeartbeatActions(
    val onRestart: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpenDiagnostics: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onAllowNotifications: () -> Unit = {},
)

@Composable
fun HeartbeatScreen(
    state: HeartbeatState,
    checks: DeviceChecks,
    actions: HeartbeatActions,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val background = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(
                        onClick = actions.onOpenDiagnostics,
                        enabled = state.gmsAvailable == true,
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_heartbeat),
                            contentDescription = stringResource(R.string.action_diagnostics),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = background,
                    scrolledContainerColor = background,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        HeartbeatContent(state, checks, actions, innerPadding)
    }
}

@Composable
private fun HeartbeatContent(
    state: HeartbeatState,
    checks: DeviceChecks,
    actions: HeartbeatActions,
    contentPadding: PaddingValues,
) {
    val status = state.status
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
    ) {
        SectionHeader(stringResource(R.string.section_status), Modifier.padding(top = 4.dp))
        StatusCard(
            status = status,
            runningTime = if (status == Status.Active || status == Status.ErrorsDetected) {
                formatRunningTime(state.runningMillis)
            } else {
                null
            },
            onRestart = actions.onRestart,
            onRetry = actions.onRetry,
        )

        Revealed(state.phase == Phase.Failed && state.startError != null) {
            MessageCard(
                tone = Tone.Error,
                title = stringResource(R.string.msg_start_failed_title),
                body = state.startError,
            )
        }
        Revealed(!checks.notificationsEnabled) {
            MessageCard(
                tone = Tone.Info,
                title = stringResource(R.string.msg_notifications_title),
                body = stringResource(R.string.msg_notifications_body),
                actions = { CardAction(stringResource(R.string.action_allow), actions.onAllowNotifications) },
            )
        }
        Revealed(!checks.unrestricted) {
            MessageCard(
                tone = Tone.Info,
                title = stringResource(R.string.msg_power_title),
                body = stringResource(R.string.msg_power_body),
                actions = if (checks.appSettingsAvailable) {
                    { CardAction(stringResource(R.string.action_app_settings), actions.onOpenAppSettings) }
                } else {
                    null
                },
            )
        }
        Revealed(state.freezeDetected) {
            MessageCard(
                tone = Tone.Warning,
                title = stringResource(R.string.msg_freeze_title),
                body = stringResource(R.string.msg_freeze_body, formatShortDuration(state.frozenMillis)),
            )
        }

        SectionHeader(stringResource(R.string.section_bindings), Modifier.padding(top = 20.dp))
        when {
            state.gmsAvailable == false -> MessageCard(
                tone = Tone.Error,
                title = stringResource(R.string.msg_gms_title),
                body = stringResource(R.string.msg_gms_body),
            )
            state.phase == Phase.Failed -> MessageCard(
                tone = Tone.Info,
                title = stringResource(R.string.msg_not_created_title),
                body = stringResource(R.string.msg_not_created_body),
            )
            else -> {
                BindingsList(
                    GmsService.entries.map { svc ->
                        stringResource(svc.label) to (state.bindings[svc] ?: BindingState.Connecting)
                    }
                )
                val summary = if (state.phase == Phase.Running) state.bindingSummary else BindingSummary.Pending
                Revealed(summary is BindingSummary.AllOk) {
                    MessageCard(tone = Tone.Success, title = stringResource(R.string.msg_all_ok_title))
                }
                Revealed(summary is BindingSummary.SomeFailed) {
                    val errors = (summary as? BindingSummary.SomeFailed)?.errors.orEmpty()
                    MessageCard(
                        tone = Tone.Warning,
                        title = stringResource(R.string.msg_bind_some_failed_title),
                        body = errorLines(errors) + "\n" + stringResource(R.string.msg_bind_impact),
                    )
                }
                Revealed(summary is BindingSummary.AllFailed) {
                    val errors = (summary as? BindingSummary.AllFailed)?.errors.orEmpty()
                    MessageCard(
                        tone = Tone.Error,
                        title = stringResource(R.string.msg_bind_all_failed_title),
                        body = errorLines(errors),
                    )
                }
            }
        }

        SectionHeader(stringResource(R.string.section_helpers), Modifier.padding(top = 20.dp))
        if (state.phase == Phase.Failed) {
            MessageCard(
                tone = Tone.Info,
                title = stringResource(R.string.msg_helpers_not_started_title),
                body = stringResource(R.string.msg_not_created_body),
            )
        } else {
            BindingsList(
                Helper.entries.map { h -> stringResource(h.label) to (state.helpers[h] ?: BindingState.Connecting) },
                connectedLabel = R.string.helper_active,
            )
            val summary = if (state.phase == Phase.Running) state.helperSummary else HelperSummary.Pending
            Revealed(summary is HelperSummary.AllOk) {
                MessageCard(tone = Tone.Success, title = stringResource(R.string.msg_helpers_ok_title))
            }
            Revealed(summary is HelperSummary.Failed) {
                val errors = (summary as? HelperSummary.Failed)?.errors.orEmpty()
                MessageCard(
                    tone = Tone.Error,
                    title = stringResource(R.string.msg_helpers_failed_title),
                    body = errors.map { (h, message) -> stringResource(h.label) + ": " + message }.joinToString("\n"),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ColumnScope.Revealed(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        // Spacing lives inside the animated block so the gap collapses together with the card.
        Box(Modifier.padding(top = 12.dp)) { content() }
    }
}

@Composable
private fun errorLines(errors: List<Pair<GmsService, String>>): String =
    errors.map { (svc, message) -> stringResource(svc.label) + ": " + message }.joinToString("\n")

fun formatRunningTime(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0) / 1000
    return String.format(
        Locale.ROOT,
        "%02d:%02d:%02d",
        totalSeconds / 3600,
        (totalSeconds % 3600) / 60,
        totalSeconds % 60,
    )
}

fun formatShortDuration(millis: Long): String {
    val s = millis / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}

// region Previews

private val previewActive = HeartbeatState(
    phase = Phase.Running,
    gmsAvailable = true,
    bindings = mapOf(
        GmsService.AdsId to BindingState.Connected,
        GmsService.Fcm to BindingState.Connected
    ),
    bindingsSettled = true,
    helpers = mapOf(Helper.VirtualDisplay to BindingState.Connected),
    runningMillis = 7_427_000,
)

@Preview(name = "All OK", heightDp = 700)
@Preview(name = "All OK (dark)", heightDp = 700, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewAllOk() = FCMHeartbeatTheme(dynamicColor = false) {
    HeartbeatScreen(previewActive, DeviceChecks(), HeartbeatActions())
}

@Preview(name = "With warnings", heightDp = 1100)
@Composable
private fun PreviewWarnings() = FCMHeartbeatTheme(dynamicColor = false) {
    HeartbeatScreen(
        previewActive.copy(
            bindings = mapOf(
                GmsService.AdsId to BindingState.Connected,
                GmsService.Fcm to BindingState.Error("Service not found or access denied"),
            ),
            frozenMillis = 45_000,
        ),
        DeviceChecks(unrestricted = false, appSettingsAvailable = true),
        HeartbeatActions(),
    )
}

@Preview(name = "Failed to start", heightDp = 700)
@Composable
private fun PreviewFailed() = FCMHeartbeatTheme(dynamicColor = false) {
    HeartbeatScreen(
        HeartbeatState(
            phase = Phase.Failed,
            startError = "ForegroundServiceStartNotAllowedException: startForegroundService() not allowed",
            gmsAvailable = true,
        ),
        DeviceChecks(),
        HeartbeatActions(),
    )
}

// endregion
