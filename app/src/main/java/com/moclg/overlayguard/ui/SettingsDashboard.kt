/*
 * Copyright 2026 Luka Gejak (luka.gejak@linux.dev)
 *
 * This file is part of Overlay Guard.
 *
 * Overlay Guard is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Overlay Guard is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

package com.moclg.overlayguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moclg.overlayguard.core.BlackoutType
import com.moclg.overlayguard.core.ExecutionMode
import com.moclg.overlayguard.core.GuardConfig
import com.moclg.overlayguard.core.PollingPreset
import com.moclg.overlayguard.core.ThemeMode
import kotlin.math.roundToInt

private val LightScheme = lightColorScheme(
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    primary = Color(0xFF0B5CAD),
    secondary = Color(0xFF0E7C66),
    error = Color(0xFFD1495B),
    onBackground = Color(0xFF17202A),
    onSurface = Color(0xFF17202A),
    onSurfaceVariant = Color(0xFF6F7782),
    outlineVariant = Color(0xFFE7E9EE)
)

private val DarkScheme = darkColorScheme(
    background = Color(0xFF0F1419),
    surface = Color(0xFF171D23),
    primary = Color(0xFF74B9FF),
    secondary = Color(0xFF63D3B2),
    error = Color(0xFFFF7A8A),
    onBackground = Color(0xFFE8EDF2),
    onSurface = Color(0xFFE8EDF2),
    onSurfaceVariant = Color(0xFFA8B2BD),
    outlineVariant = Color(0xFF2D3640)
)

data class PermissionSnapshot(
    val cameraGranted: Boolean,
    val notificationsGranted: Boolean,
    val executionReady: Boolean,
    val writeSettingsGranted: Boolean,
    val batteryUnrestricted: Boolean,
    val rootAvailable: Boolean,
    val shizukuRunning: Boolean,
    val shizukuGranted: Boolean
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDashboard(
    config: GuardConfig,
    permissions: PermissionSnapshot,
    serviceRunning: Boolean,
    onServiceToggle: (Boolean) -> Unit,
    onConfigChange: (GuardConfig) -> Unit,
    onRequestCamera: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestExecution: () -> Unit,
    onRequestWriteSettings: () -> Unit,
    onRequestBattery: () -> Unit
) {
    val darkTheme = when (config.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Overlay Guard",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = if (serviceRunning) "Monitoring active" else "Monitoring stopped",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (serviceRunning) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Switch(
                        checked = serviceRunning,
                        onCheckedChange = onServiceToggle
                    )
                }
            }

            item {
                PermissionCard(
                    permissions = permissions,
                    mode = config.executionMode,
                    onRequestCamera = onRequestCamera,
                    onRequestNotifications = onRequestNotifications,
                    onRequestExecution = onRequestExecution,
                    onRequestWriteSettings = onRequestWriteSettings,
                    onRequestBattery = onRequestBattery
                )
            }

            item {
                SectionCard(title = "Appearance") {
                    ThemeMode.entries.forEach { mode ->
                        SelectableRow(
                            title = mode.label,
                            subtitle = when (mode) {
                                ThemeMode.SYSTEM -> "match Android light or dark theme"
                                ThemeMode.LIGHT -> "always use the light dashboard"
                                ThemeMode.DARK -> "always use the dark dashboard"
                            },
                            selected = config.themeMode == mode,
                            onClick = { onConfigChange(config.copy(themeMode = mode)) }
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Execution Mode") {
                    ExecutionMode.entries.forEach { mode ->
                        SelectableRow(
                            title = when (mode) {
                                ExecutionMode.ROOT -> "Root"
                                ExecutionMode.SHIZUKU -> "Shizuku"
                            },
                            subtitle = when (mode) {
                                ExecutionMode.ROOT -> "su shell and binder service calls"
                                ExecutionMode.SHIZUKU -> "privileged binder calls through Shizuku"
                            },
                            selected = config.executionMode == mode,
                            onClick = { onConfigChange(config.copy(executionMode = mode)) }
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Blackout Type") {
                    BlackoutType.entries.forEach { type ->
                        SelectableRow(
                            title = when (type) {
                                BlackoutType.ABSOLUTE_DIM -> "Surface Brightness Off (-1)"
                                BlackoutType.TRUE_EXTINGUISH -> "Surface Power Off"
                            },
                            subtitle = when (type) {
                                BlackoutType.ABSOLUTE_DIM -> "backlight off while touch/display pipeline stays alive"
                                BlackoutType.TRUE_EXTINGUISH -> "hardware display power mode off without keyguard sleep"
                            },
                            selected = config.blackoutType == type,
                            onClick = { onConfigChange(config.copy(blackoutType = type)) }
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Smart Polling") {
                    Text(
                        text = "Profile",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PollingPresetUiOrder.forEach { preset ->
                            FilterChip(
                                selected = config.pollingPreset == preset,
                                onClick = {
                                    onConfigChange(
                                        config.withPollingPreset(preset)
                                    )
                                },
                                label = { Text(preset.label) }
                            )
                        }
                    }
                    PollingSlider(
                        title = "Motion response",
                        valueText = "${motionResponsePercent(config.motionVarianceThreshold).roundToInt()}%",
                        value = motionResponsePercent(config.motionVarianceThreshold),
                        onValueChange = { response ->
                            onConfigChange(
                                config.withCustomMotionThreshold(
                                    thresholdFromMotionResponse(response)
                                )
                            )
                        },
                        valueRange = 0f..100f,
                        steps = 19,
                        startLabel = "Battery saver",
                        endLabel = "Responsive"
                    )
                    PollingSlider(
                        title = "Active check interval",
                        valueText = intervalLabel(config.dynamicIntervalMs),
                        value = config.dynamicIntervalMs.toFloat(),
                        onValueChange = { interval ->
                            onConfigChange(
                                config.withCustomDynamicInterval(
                                    interval.roundToInt()
                                        .toLong()
                                        .coerceIn(ACTIVE_INTERVAL_MIN_MS, ACTIVE_INTERVAL_MAX_MS)
                                )
                            )
                        },
                        valueRange = ACTIVE_INTERVAL_MIN_MS.toFloat()..ACTIVE_INTERVAL_MAX_MS.toFloat(),
                        steps = 28,
                        startLabel = "Faster",
                        endLabel = "Lower battery"
                    )
                    PollingSlider(
                        title = "Quiet check interval",
                        valueText = intervalLabel(config.quietIntervalMs),
                        value = config.quietIntervalMs.toFloat(),
                        onValueChange = { interval ->
                            onConfigChange(
                                config.withCustomQuietInterval(
                                    interval.roundToInt()
                                        .toLong()
                                        .coerceIn(QUIET_INTERVAL_MIN_MS, QUIET_INTERVAL_MAX_MS)
                                )
                            )
                        },
                        valueRange = QUIET_INTERVAL_MIN_MS.toFloat()..QUIET_INTERVAL_MAX_MS.toFloat(),
                        steps = 23,
                        startLabel = "Faster",
                        endLabel = "Lower battery"
                    )
                    Text(
                        text = "Desk pause ${pauseLabel(config.staticPauseAfterMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SectionCard(title = "Face Attention Gate") {
                    val failSafeAngle = config.attentionYawDegrees >= FAIL_SAFE_ATTENTION_DEGREES
                    PollingSlider(
                        title = "Secondary face angle",
                        valueText = if (failSafeAngle) {
                            "Any second face"
                        } else {
                            "${config.attentionYawDegrees.roundToInt()} deg"
                        },
                        value = config.attentionYawDegrees,
                        onValueChange = {
                            onConfigChange(config.copy(attentionYawDegrees = it))
                        },
                        valueRange = 30f..90f,
                        steps = 11,
                        startLabel = "Strict",
                        endLabel = "Fail-safe"
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(18.dp))
            }
        }
    }
}

@Composable
private fun PollingSlider(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    startLabel: String,
    endLabel: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = startLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = endLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val PollingPresetUiOrder = listOf(
    PollingPreset.BALANCED,
    PollingPreset.ECO,
    PollingPreset.AGGRESSIVE,
    PollingPreset.CUSTOM
)

private fun motionResponsePercent(threshold: Float): Float {
    val bounded = threshold.coerceIn(MOTION_THRESHOLD_MIN, MOTION_THRESHOLD_MAX)
    return ((MOTION_THRESHOLD_MAX - bounded) / (MOTION_THRESHOLD_MAX - MOTION_THRESHOLD_MIN)) *
        100f
}

private fun thresholdFromMotionResponse(responsePercent: Float): Float {
    val bounded = responsePercent.coerceIn(0f, 100f)
    return MOTION_THRESHOLD_MAX -
        (bounded / 100f) * (MOTION_THRESHOLD_MAX - MOTION_THRESHOLD_MIN)
}

private fun intervalLabel(intervalMs: Long): String {
    return if (intervalMs < 1_000L) {
        "${intervalMs} ms"
    } else {
        "%.2f s".format(intervalMs / 1_000f)
    }
}

private fun pauseLabel(pauseMs: Long): String {
    val minutes = pauseMs / 60_000L
    return if (minutes <= 1L) {
        "1 min"
    } else {
        "$minutes min"
    }
}

private const val MOTION_THRESHOLD_MIN = 0.010f
private const val MOTION_THRESHOLD_MAX = 0.080f
private const val ACTIVE_INTERVAL_MIN_MS = 50L
private const val ACTIVE_INTERVAL_MAX_MS = 1_500L
private const val QUIET_INTERVAL_MIN_MS = 100L
private const val QUIET_INTERVAL_MAX_MS = 2_500L
private const val FAIL_SAFE_ATTENTION_DEGREES = 85f

@Composable
private fun PermissionCard(
    permissions: PermissionSnapshot,
    mode: ExecutionMode,
    onRequestCamera: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestExecution: () -> Unit,
    onRequestWriteSettings: () -> Unit,
    onRequestBattery: () -> Unit
) {
    SectionCard(title = "Permission Manager") {
        PermissionRow(
            label = "Camera",
            granted = permissions.cameraGranted,
            buttonText = "Grant",
            onClick = onRequestCamera
        )
        PermissionRow(
            label = "Notifications",
            granted = permissions.notificationsGranted,
            buttonText = "Grant",
            onClick = onRequestNotifications
        )
        PermissionRow(
            label = when (mode) {
                ExecutionMode.ROOT -> "Root"
                ExecutionMode.SHIZUKU -> "Shizuku"
            },
            granted = permissions.executionReady,
            detail = when (mode) {
                ExecutionMode.ROOT -> if (permissions.rootAvailable) "su binary found" else "su unavailable"
                ExecutionMode.SHIZUKU -> if (permissions.shizukuRunning) {
                    if (permissions.shizukuGranted) "binder granted" else "permission required"
                } else {
                    "binder offline"
                }
            },
            buttonText = if (mode == ExecutionMode.SHIZUKU) "Bind" else "Check",
            onClick = onRequestExecution
        )
        PermissionRow(
            label = "Write Settings",
            granted = permissions.writeSettingsGranted,
            buttonText = "Open",
            onClick = onRequestWriteSettings
        )
        PermissionRow(
            label = "Battery Optimization",
            granted = permissions.batteryUnrestricted,
            buttonText = "Allow",
            onClick = onRequestBattery
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            content()
        }
    }
}

@Composable
private fun SelectableRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    buttonText: String,
    onClick: () -> Unit,
    detail: String? = null
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (granted) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                )
                Column(modifier = Modifier.padding(start = 10.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = detail ?: if (granted) "Ready" else "Required",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(
                onClick = onClick,
                enabled = !granted || buttonText == "Check"
            ) {
                Text(buttonText)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
