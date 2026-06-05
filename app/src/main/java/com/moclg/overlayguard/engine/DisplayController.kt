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
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.moclg.overlayguard.core.BlackoutType
import com.moclg.overlayguard.core.DisplayPowerMode
import com.moclg.overlayguard.core.ExecutionResult
import com.moclg.overlayguard.core.IExecutionHandler
import com.moclg.overlayguard.core.SettingsNamespace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DisplayController(
    private val context: Context,
    private val executionHandler: IExecutionHandler,
    private var blackoutType: BlackoutType
) {

    private val resolver = context.contentResolver
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val displayMutex = Mutex()
    private var state: DisplayState = DisplayState.CLEAR
    private var savedBrightness: SavedBrightness? = null
    private var blankWakeLock: PowerManager.WakeLock? = null

    suspend fun updateBlackoutType(type: BlackoutType) {
        displayMutex.withLock {
            if (blackoutType == type) return
            if (state == DisplayState.BLANKED) {
                restoreLocked()
            }
            if (state == DisplayState.BLANKED) return
            blackoutType = type
        }
    }

    suspend fun blank() {
        displayMutex.withLock {
            if (state == DisplayState.BLANKED) {
                refreshBlankWakeLock()
                return
            }
            acquireBlankWakeLock()
            val result = when (blackoutType) {
                BlackoutType.ABSOLUTE_DIM -> forceSurfaceBrightnessOff()
                BlackoutType.TRUE_EXTINGUISH -> forceSurfacePowerOff()
            }
            if (result.success) {
                state = DisplayState.BLANKED
                Log.i(TAG, "Display blanked using $blackoutType")
            } else {
                releaseBlankWakeLock()
                Log.w(TAG, "Display blank failed: ${result.message} ${result.output}")
            }
        }
    }

    suspend fun restore() {
        displayMutex.withLock {
            restoreLocked()
        }
    }

    suspend fun close() {
        displayMutex.withLock {
            restoreLocked()
            releaseBlankWakeLock()
        }
    }

    private suspend fun restoreLocked() {
        if (state == DisplayState.CLEAR) return
        val result = when (blackoutType) {
            BlackoutType.ABSOLUTE_DIM -> restoreBrightness()
            BlackoutType.TRUE_EXTINGUISH -> restoreFromSurfacePowerOff()
        }
        if (result.success) {
            state = DisplayState.CLEAR
            releaseBlankWakeLock()
            Log.i(TAG, "Display restored from $blackoutType")
        } else {
            Log.w(TAG, "Display restore failed: ${result.message} ${result.output}")
        }
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

            val result = setSurfaceBrightnessWithRetry(SURFACE_BRIGHTNESS_OFF)
            if (result.success) {
                result
            } else {
                Log.w(TAG, "Surface brightness off failed; falling back to settings dim")
                forceAbsoluteDim()
            }
        }
    }

    private suspend fun forceSurfacePowerOff(): ExecutionResult {
        return withContext(Dispatchers.IO) {
            savedBrightness = readBrightness()
            val manualModeResult = executionHandler.executeShellCommand(
                "settings put system ${Settings.System.SCREEN_BRIGHTNESS_MODE} " +
                    "${Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL}"
            )
            if (!manualModeResult.success) {
                Log.w(TAG, "Unable to force manual brightness mode: ${manualModeResult.output}")
            }

            val brightnessResult = setSurfaceBrightnessWithRetry(SURFACE_BRIGHTNESS_OFF)
            if (!brightnessResult.success) {
                Log.w(TAG, "Pre-black surface brightness failed: ${brightnessResult.output}")
            }

            val powerResult = setDisplayPowerModeWithRetry(DisplayPowerMode.OFF)
            if (powerResult.success) {
                powerResult
            } else {
                Log.w(TAG, "Surface power-off failed; restoring brightness state")
                restoreBrightness()
                powerResult
            }
        }
    }

    private suspend fun restoreFromSurfacePowerOff(): ExecutionResult {
        return withContext(Dispatchers.IO) {
            val powerResult = setDisplayPowerModeWithRetry(DisplayPowerMode.NORMAL)
            val brightnessResult = restoreBrightness()
            if (!brightnessResult.success) {
                Log.w(TAG, "Brightness restore after power-on failed: ${brightnessResult.output}")
            }
            powerResult
        }
    }

    private suspend fun restoreBrightness(): ExecutionResult {
        return withContext(Dispatchers.IO) {
            val original = savedBrightness
            val mode = original?.mode ?: Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            val brightness = original?.brightness ?: DEFAULT_RESTORE_BRIGHTNESS
            val surfaceResult = setSurfaceBrightnessWithRetry(SURFACE_BRIGHTNESS_MIN)
            if (!surfaceResult.success) {
                Log.w(TAG, "Surface brightness restore nudge failed: ${surfaceResult.output}")
            }
            val firstBrightness = if (brightness < BRIGHTNESS_NUDGE_DELTA + 1) {
                brightness + BRIGHTNESS_NUDGE_DELTA
            } else {
                brightness - BRIGHTNESS_NUDGE_DELTA
            }.coerceIn(MIN_SETTINGS_BRIGHTNESS, MAX_SETTINGS_BRIGHTNESS)

            if (Settings.System.canWrite(context)) {
                Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    firstBrightness
                )
                delay(BRIGHTNESS_RESTORE_NUDGE_MS)
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
            val nudgeResult = executionHandler.executeShellCommand(
                "settings put system ${Settings.System.SCREEN_BRIGHTNESS} $firstBrightness"
            )
            if (!nudgeResult.success) {
                Log.w(TAG, "Brightness restore nudge setting failed: ${nudgeResult.output}")
            }
            delay(BRIGHTNESS_RESTORE_NUDGE_MS)
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

    private suspend fun setSurfaceBrightnessWithRetry(brightness: Float): ExecutionResult {
        var lastResult = ExecutionResult.failure(
            "Surface brightness command not attempted"
        )
        repeat(SURFACE_COMMAND_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                delay(SURFACE_COMMAND_RETRY_DELAY_MS)
            }
            val result = executionHandler.setSurfaceBrightness(brightness)
            if (result.success) {
                return result
            }
            lastResult = result
        }
        return lastResult
    }

    private suspend fun setDisplayPowerModeWithRetry(mode: DisplayPowerMode): ExecutionResult {
        var lastResult = ExecutionResult.failure(
            "Display power command not attempted"
        )
        repeat(SURFACE_COMMAND_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                delay(SURFACE_COMMAND_RETRY_DELAY_MS)
            }
            val result = executionHandler.setDisplayPowerMode(mode)
            if (result.success) {
                return result
            }
            lastResult = result
        }
        return lastResult
    }

    private fun acquireBlankWakeLock() {
        val lock = blankWakeLock ?: powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            setReferenceCounted(false)
            blankWakeLock = this
        }
        if (!lock.isHeld) {
            lock.acquire(BLANK_WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun refreshBlankWakeLock() {
        val lock = blankWakeLock
        if (lock == null || !lock.isHeld) {
            acquireBlankWakeLock()
        } else {
            lock.acquire(BLANK_WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseBlankWakeLock() {
        val lock = blankWakeLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
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
        private const val SURFACE_COMMAND_ATTEMPTS = 3
        private const val SURFACE_COMMAND_RETRY_DELAY_MS = 35L
        private const val BRIGHTNESS_RESTORE_NUDGE_MS = 25L
        private const val BRIGHTNESS_NUDGE_DELTA = 5
        private const val MIN_SETTINGS_BRIGHTNESS = 0
        private const val MAX_SETTINGS_BRIGHTNESS = 255
        private const val BLANK_WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val WAKE_LOCK_TAG = "OverlayGuard:DisplayBlank"
    }
}
