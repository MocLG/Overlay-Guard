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

package com.moclg.overlayguard.core

import android.content.Context

enum class ExecutionMode {
    ROOT,
    SHIZUKU
}

enum class BlackoutType {
    ABSOLUTE_DIM,
    TRUE_EXTINGUISH
}

enum class PollingPreset(
    val label: String,
    val dynamicIntervalMs: Long,
    val quietIntervalMs: Long,
    val staticPauseAfterMs: Long,
    val varianceThreshold: Float
) {
    AGGRESSIVE("Aggressive", 150L, 300L, 300_000L, 0.020f),
    BALANCED("Balanced", 350L, 650L, 600_000L, 0.035f),
    ECO("Eco", 700L, 1_200L, 900_000L, 0.055f),
    CUSTOM("Custom", 350L, 650L, 600_000L, 0.035f)
}

enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark")
}

data class GuardConfig(
    val executionMode: ExecutionMode = ExecutionMode.ROOT,
    val blackoutType: BlackoutType = BlackoutType.TRUE_EXTINGUISH,
    val pollingPreset: PollingPreset = PollingPreset.BALANCED,
    val motionVarianceThreshold: Float = PollingPreset.BALANCED.varianceThreshold,
    val dynamicIntervalMs: Long = PollingPreset.BALANCED.dynamicIntervalMs,
    val quietIntervalMs: Long = PollingPreset.BALANCED.quietIntervalMs,
    val staticPauseAfterMs: Long = PollingPreset.BALANCED.staticPauseAfterMs,
    val attentionYawDegrees: Float = 90f,
    val themeMode: ThemeMode = ThemeMode.SYSTEM
) {
    fun withPollingPreset(preset: PollingPreset): GuardConfig {
        if (preset == PollingPreset.CUSTOM) {
            return copy(pollingPreset = PollingPreset.CUSTOM)
        }
        return copy(
            pollingPreset = preset,
            motionVarianceThreshold = preset.varianceThreshold,
            dynamicIntervalMs = preset.dynamicIntervalMs,
            quietIntervalMs = preset.quietIntervalMs,
            staticPauseAfterMs = preset.staticPauseAfterMs
        )
    }

    fun withCustomMotionThreshold(threshold: Float): GuardConfig {
        return copy(
            pollingPreset = PollingPreset.CUSTOM,
            motionVarianceThreshold = threshold
        )
    }

    fun withCustomDynamicInterval(intervalMs: Long): GuardConfig {
        return copy(
            pollingPreset = PollingPreset.CUSTOM,
            dynamicIntervalMs = intervalMs
        )
    }

    fun withCustomQuietInterval(intervalMs: Long): GuardConfig {
        return copy(
            pollingPreset = PollingPreset.CUSTOM,
            quietIntervalMs = intervalMs
        )
    }
}

object GuardPreferences {
    const val PREFS_NAME = "overlay_guard_prefs"
    const val KEY_SERVICE_ENABLED = "service_enabled"
    const val KEY_EXECUTION_MODE = "execution_mode"
    const val KEY_BLACKOUT_TYPE = "blackout_type"
    const val KEY_POLLING_PRESET = "polling_preset"
    const val KEY_MOTION_THRESHOLD = "motion_threshold"
    const val KEY_DYNAMIC_INTERVAL_MS = "dynamic_interval_ms"
    const val KEY_QUIET_INTERVAL_MS = "quiet_interval_ms"
    const val KEY_STATIC_PAUSE_AFTER_MS = "static_pause_after_ms"
    const val KEY_ATTENTION_YAW = "attention_yaw_degrees"
    const val KEY_THEME_MODE = "theme_mode"

    fun load(context: Context): GuardConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val preset = enumValueOrDefault(
            prefs.getString(KEY_POLLING_PRESET, null),
            PollingPreset.BALANCED
        )
        return GuardConfig(
            executionMode = enumValueOrDefault(
                prefs.getString(KEY_EXECUTION_MODE, null),
                ExecutionMode.ROOT
            ),
            blackoutType = enumValueOrDefault(
                prefs.getString(KEY_BLACKOUT_TYPE, null),
                BlackoutType.TRUE_EXTINGUISH
            ),
            pollingPreset = preset,
            motionVarianceThreshold = prefs.getFloat(
                KEY_MOTION_THRESHOLD,
                preset.varianceThreshold
            ),
            dynamicIntervalMs = prefs.getLong(
                KEY_DYNAMIC_INTERVAL_MS,
                preset.dynamicIntervalMs
            ),
            quietIntervalMs = prefs.getLong(
                KEY_QUIET_INTERVAL_MS,
                preset.quietIntervalMs
            ),
            staticPauseAfterMs = prefs.getLong(
                KEY_STATIC_PAUSE_AFTER_MS,
                preset.staticPauseAfterMs
            ),
            attentionYawDegrees = prefs.getFloat(KEY_ATTENTION_YAW, 90f),
            themeMode = enumValueOrDefault(
                prefs.getString(KEY_THEME_MODE, null),
                ThemeMode.SYSTEM
            )
        )
    }

    fun save(context: Context, config: GuardConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EXECUTION_MODE, config.executionMode.name)
            .putString(KEY_BLACKOUT_TYPE, config.blackoutType.name)
            .putString(KEY_POLLING_PRESET, config.pollingPreset.name)
            .putFloat(KEY_MOTION_THRESHOLD, config.motionVarianceThreshold)
            .putLong(KEY_DYNAMIC_INTERVAL_MS, config.dynamicIntervalMs)
            .putLong(KEY_QUIET_INTERVAL_MS, config.quietIntervalMs)
            .putLong(KEY_STATIC_PAUSE_AFTER_MS, config.staticPauseAfterMs)
            .putFloat(KEY_ATTENTION_YAW, config.attentionYawDegrees)
            .putString(KEY_THEME_MODE, config.themeMode.name)
            .apply()
    }

    fun setServiceEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SERVICE_ENABLED, enabled)
            .apply()
    }

    fun isServiceEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SERVICE_ENABLED, false)
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        raw: String?,
        default: T
    ): T {
        return raw?.let { value ->
            enumValues<T>().firstOrNull { it.name == value }
        } ?: default
    }
}
