package com.fcmheartbeat.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Fallback scheme for devices without dynamic color (Android 11 and below).
val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

/** Status roles Material 3 doesn't define (success / warning), tuned for light and dark. */
@Immutable
data class StatusColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

val LightStatusColors = StatusColors(
    success = Color(0xFF188038),
    successContainer = Color(0xFFC9F0D2),
    onSuccessContainer = Color(0xFF062712),
    warning = Color(0xFFF2B600),
    warningContainer = Color(0xFFFFEDBE),
    onWarningContainer = Color(0xFF3A2C00),
)

val DarkStatusColors = StatusColors(
    success = Color(0xFF6DD58C),
    successContainer = Color(0xFF0F5223),
    onSuccessContainer = Color(0xFFC9F0D2),
    warning = Color(0xFFFFD25F),
    warningContainer = Color(0xFF574500),
    onWarningContainer = Color(0xFFFFEDBE),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }
