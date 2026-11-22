package com.example.fcmheartbeat.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.fcmheartbeat.R
import com.example.fcmheartbeat.core.BindingState
import com.example.fcmheartbeat.core.Status
import com.example.fcmheartbeat.notify.labelRes
import com.example.fcmheartbeat.ui.theme.FCMHeartbeatTheme

val CardShape = RoundedCornerShape(28.dp)
private val SegmentOuter = 24.dp
private val SegmentInner = 6.dp

@Composable
fun cardContainerColor(): Color = MaterialTheme.colorScheme.surfaceBright

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(start = 20.dp, bottom = 12.dp)
            .semantics { heading() },
    )
}

@Composable
fun statusColor(status: Status): Color = when (status) {
    Status.Starting -> MaterialTheme.colorScheme.outline
    Status.Active -> FCMHeartbeatTheme.statusColors.success
    Status.ErrorsDetected -> FCMHeartbeatTheme.statusColors.warning
    Status.FailedToStart -> MaterialTheme.colorScheme.error
}

/** Status dot on a soft cookie-shaped halo that slowly turns while the service is active. */
@Composable
fun StatusIndicator(status: Status, modifier: Modifier = Modifier) {
    val color by animateColorAsState(statusColor(status), label = "statusColor")
    val transition = rememberInfiniteTransition(label = "statusIndicator")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing)),
        label = "rotation",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.30f,
        animationSpec = infiniteRepeatable(tween(1_400), RepeatMode.Reverse),
        label = "pulse",
    )
    val active = status == Status.Active
    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .rotate(if (active) rotation else 0f)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(color.copy(alpha = if (active) pulse else 0.18f)),
        )
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
fun StatusCard(
    status: Status,
    runningTime: String?,
    onRestart: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(shape = CardShape, color = cardContainerColor(), modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 20.dp, top = 20.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusIndicator(status)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                AnimatedContent(targetState = status, label = "statusLabel") { s ->
                    Text(
                        text = stringResource(s.labelRes),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (runningTime != null) {
                    val description = stringResource(R.string.running_time_description, runningTime)
                    Text(
                        text = runningTime,
                        modifier = Modifier.semantics { contentDescription = description },
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            AnimatedContent(
                targetState = status,
                // Only animate when the action itself changes (not between Active and Errors detected).
                contentKey = {
                    when (it) {
                        Status.Starting -> 0
                        Status.FailedToStart -> 1
                        else -> 2
                    }
                },
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.9f)) },
                label = "statusAction",
            ) { s ->
                when (s) {
                    Status.Starting -> LoadingIndicator(Modifier.size(48.dp))
                    Status.FailedToStart -> Button(onClick = onRetry, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.action_retry))
                    }
                    else -> Button(onClick = onRestart, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.action_restart))
                    }
                }
            }
        }
    }
}

enum class Tone { Info, Success, Warning, Error }

@Composable
private fun toneColors(tone: Tone): Pair<Color, Color> = when (tone) {
    Tone.Info -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurface
    Tone.Success -> FCMHeartbeatTheme.statusColors.let { it.successContainer to it.onSuccessContainer }
    Tone.Warning -> FCMHeartbeatTheme.statusColors.let { it.warningContainer to it.onWarningContainer }
    Tone.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
}

@DrawableRes
private fun toneIcon(tone: Tone): Int = when (tone) {
    Tone.Info -> R.drawable.ic_info
    Tone.Success -> R.drawable.ic_check_circle
    Tone.Warning -> R.drawable.ic_warning
    Tone.Error -> R.drawable.ic_error
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageCard(
    tone: Tone,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val (container, content) = toneColors(tone)
    Surface(shape = CardShape, color = container, contentColor = content, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(toneIcon(tone)), contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (body != null) {
                Spacer(Modifier.height(8.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 36.dp))
            }
            if (actions != null) {
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(start = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = { actions() },
                )
            }
        }
    }
}

/** Grouped list: rounded outer corners, tight inner corners, small gaps between rows. */
@Composable
fun BindingsList(rows: List<Pair<String, BindingState>>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, (label, state) ->
            val shape = RoundedCornerShape(
                topStart = if (index == 0) SegmentOuter else SegmentInner,
                topEnd = if (index == 0) SegmentOuter else SegmentInner,
                bottomStart = if (index == rows.lastIndex) SegmentOuter else SegmentInner,
                bottomEnd = if (index == rows.lastIndex) SegmentOuter else SegmentInner,
            )
            Surface(shape = shape, color = cardContainerColor(), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(horizontal = 20.dp).height(64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    BindingStateLabel(state)
                }
            }
        }
    }
}

@Composable
private fun BindingStateLabel(state: BindingState) {
    AnimatedContent(targetState = state::class, label = "bindingState") { kind ->
        when (kind) {
            BindingState.Connected::class -> Text(
                stringResource(R.string.binding_connected),
                style = MaterialTheme.typography.labelLarge,
                color = FCMHeartbeatTheme.statusColors.success,
            )
            BindingState.Error::class -> Text(
                stringResource(R.string.binding_error),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
            else -> {
                val description = stringResource(R.string.binding_connecting)
                LoadingIndicator(Modifier.size(32.dp).semantics { contentDescription = description })
            }
        }
    }
}

@Composable
fun RowScope.CardAction(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.filledTonalButtonColors(),
    ) { Text(text) }
}
