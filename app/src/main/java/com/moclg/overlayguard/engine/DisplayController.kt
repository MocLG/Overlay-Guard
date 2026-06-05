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

package com.moclg.overlayguard.engine

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.moclg.overlayguard.core.BlackoutType
import com.moclg.overlayguard.core.DisplayPowerMode
import com.moclg.overlayguard.core.ExecutionResult
import com.moclg.overlayguard.core.IExecutionHandler
import com.moclg.overlayguard.core.SettingsNamespace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DisplayController(
    private val context: Context,
    private val executionHandler: IExecutionHandler,
    private var blackoutType: BlackoutType
) {

    private val resolver = context.contentResolver
    private var state: DisplayState = DisplayState.CLEAR
    private var savedBrightness: SavedBrightness? = null

    suspend fun updateBlackoutType(type: BlackoutType) {
        if (blackoutType == type) return
        if (state == DisplayState.BLANKED) {
            restore()
        }
        blackoutType = type
    }

    suspend fun blank() {
        if (state == DisplayState.BLANKED) return
        val result = when (blackoutType) {
            BlackoutType.ABSOLUTE_DIM -> forceSurfaceBrightnessOff()
            BlackoutType.TRUE_EXTINGUISH -> executionHandler.setDisplayPowerMode(
                DisplayPowerMode.OFF
            )
        }
        if (result.success) {
            state = DisplayState.BLANKED
            Log.i(TAG, "Display blanked using $blackoutType")
        } else {
            Log.w(TAG, "Display blank failed: ${result.message} ${result.output}")
        }
    }

    suspend fun restore() {
        if (state == DisplayState.CLEAR) return
        val result = when (blackoutType) {
            BlackoutType.ABSOLUTE_DIM -> restoreBrightness()
            BlackoutType.TRUE_EXTINGUISH -> executionHandler.setDisplayPowerMode(
                DisplayPowerMode.NORMAL
            )
        }
        if (result.success) {
            state = DisplayState.CLEAR
            Log.i(TAG, "Display restored from $blackoutType")
        } else {
            Log.w(TAG, "Display restore failed: ${result.message} ${result.output}")
        }
    }

    suspend fun close() {
        restore()
    }

    private suspend fun forceAbsoluteDim(): ExecutionResult {
        return withContext(Dispatchers.IO) {
            savedBrightness = readBrightness()

            if (Settings.System.canWrite(context)) {
                val modeWritten = Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                val intWritten = Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    0
                )
                val floatWritten = Settings.System.putFloat(
                    resolver,
                    SCREEN_BRIGHTNESS_FLOAT,
                    -1.0f
                )
                if (modeWritten && intWritten) {
                    return@withContext ExecutionResult.ok(
                        "Brightness forced to panel minimum",
                        "floatWritten=$floatWritten"
                    )
                }
            }

            val shellResult = executionHandler.executeShellCommand(
                "settings put system ${Settings.System.SCREEN_BRIGHTNESS_MODE} " +
                    "${Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL}; " +
                    "settings put system ${Settings.System.SCREEN_BRIGHTNESS} 0; " +
                    "settings put system $SCREEN_BRIGHTNESS_FLOAT -1.0"
            )
            if (shellResult.success) {
                shellResult
            } else {
                executionHandler.putSystemSetting(
                    SettingsNamespace.SYSTEM,
                    Settings.System.SCREEN_BRIGHTNESS,
                    "0"
                )
            }
        }
    }

    private suspend fun forceSurfaceBrightnessOff(): ExecutionResult {
        return withContext(Dispatchers.IO) {
            savedBrightness = readBrightness()
            val manualModeResult = executionHandler.executeShellCommand(
                "settings put system ${Settings.System.SCREEN_BRIGHTNESS_MODE} " +
                    "${Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL}"
            )
            if (!manualModeResult.success) {
                Log.w(TAG, "Unable to force manual brightness mode: ${manualModeResult.output}")
            }

            val result = executionHandler.setSurfaceBrightness(SURFACE_BRIGHTNESS_OFF)
            if (result.success) {
                result
            } else {
                Log.w(TAG, "Surface brightness off failed; falling back to settings dim")
                forceAbsoluteDim()
            }
        }
    }

    private suspend fun restoreBrightness(): ExecutionResult {
        return withContext(Dispatchers.IO) {
            val original = savedBrightness
            val mode = original?.mode ?: Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            val brightness = original?.brightness ?: DEFAULT_RESTORE_BRIGHTNESS
            val surfaceResult = executionHandler.setSurfaceBrightness(SURFACE_BRIGHTNESS_MIN)
            if (!surfaceResult.success) {
                Log.w(TAG, "Surface brightness restore nudge failed: ${surfaceResult.output}")
            }

            if (Settings.System.canWrite(context)) {
                val brightnessOk = Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    brightness
                )
                val modeOk = Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    mode
                )
                val floatOk = original?.brightnessFloat?.let { value ->
                    Settings.System.putFloat(resolver, SCREEN_BRIGHTNESS_FLOAT, value)
                } ?: Settings.System.putString(resolver, SCREEN_BRIGHTNESS_FLOAT, null)
                if (brightnessOk && modeOk) {
                    savedBrightness = null
                    return@withContext ExecutionResult.ok(
                        "Brightness restored",
                        "floatRestored=$floatOk"
                    )
                }
            }

            val floatCommand = original?.brightnessFloat?.let { value ->
                "settings put system $SCREEN_BRIGHTNESS_FLOAT $value"
            } ?: "settings delete system $SCREEN_BRIGHTNESS_FLOAT"
            val result = executionHandler.executeShellCommand(
                "settings put system ${Settings.System.SCREEN_BRIGHTNESS} $brightness; " +
                    "settings put system ${Settings.System.SCREEN_BRIGHTNESS_MODE} $mode; " +
                    floatCommand
            )
            if (result.success) {
                savedBrightness = null
            }
            result
        }
    }

    private fun readBrightness(): SavedBrightness {
        val brightness = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()
        val mode = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        }.getOrNull()
        val brightnessFloat = runCatching {
            Settings.System.getFloat(resolver, SCREEN_BRIGHTNESS_FLOAT)
        }.getOrNull()
        return SavedBrightness(
            brightness = brightness,
            mode = mode,
            brightnessFloat = brightnessFloat
        )
    }

    private enum class DisplayState {
        CLEAR,
        BLANKED
    }

    private data class SavedBrightness(
        val brightness: Int?,
        val mode: Int?,
        val brightnessFloat: Float?
    )

    companion object {
        private const val TAG = "DisplayController"
        private const val SCREEN_BRIGHTNESS_FLOAT = "screen_brightness_float"
        private const val DEFAULT_RESTORE_BRIGHTNESS = 128
        private const val SURFACE_BRIGHTNESS_OFF = -1.0f
        private const val SURFACE_BRIGHTNESS_MIN = 0.0f
    }
}
