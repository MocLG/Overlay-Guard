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
    AGGRESSIVE("Aggressive", 750L, 4_000L, 45_000L, 0.055f),
    BALANCED("Balanced", 1_250L, 7_000L, 60_000L, 0.035f),
    ECO("Eco", 1_500L, 12_000L, 90_000L, 0.020f)
}

data class GuardConfig(
    val executionMode: ExecutionMode = ExecutionMode.ROOT,
    val blackoutType: BlackoutType = BlackoutType.TRUE_EXTINGUISH,
    val pollingPreset: PollingPreset = PollingPreset.BALANCED,
    val motionVarianceThreshold: Float = PollingPreset.BALANCED.varianceThreshold,
    val attentionYawDegrees: Float = 45f
)

object GuardPreferences {
    const val PREFS_NAME = "overlay_guard_prefs"
    const val KEY_SERVICE_ENABLED = "service_enabled"
    const val KEY_EXECUTION_MODE = "execution_mode"
    const val KEY_BLACKOUT_TYPE = "blackout_type"
    const val KEY_POLLING_PRESET = "polling_preset"
    const val KEY_MOTION_THRESHOLD = "motion_threshold"
    const val KEY_ATTENTION_YAW = "attention_yaw_degrees"

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
            attentionYawDegrees = prefs.getFloat(KEY_ATTENTION_YAW, 45f)
        )
    }

    fun save(context: Context, config: GuardConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EXECUTION_MODE, config.executionMode.name)
            .putString(KEY_BLACKOUT_TYPE, config.blackoutType.name)
            .putString(KEY_POLLING_PRESET, config.pollingPreset.name)
            .putFloat(KEY_MOTION_THRESHOLD, config.motionVarianceThreshold)
            .putFloat(KEY_ATTENTION_YAW, config.attentionYawDegrees)
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
