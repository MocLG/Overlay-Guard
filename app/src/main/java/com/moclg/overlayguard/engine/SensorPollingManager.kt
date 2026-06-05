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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.moclg.overlayguard.core.GuardConfig
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.sqrt

data class SamplingDirective(
    val motionState: MotionState,
    val intervalMs: Long,
    val pauseCamera: Boolean,
    val variance: Float
)

enum class MotionState {
    STATIC,
    DYNAMIC
}

class SensorPollingManager(
    context: Context,
    private var config: GuardConfig,
    private val onDirective: (SamplingDirective) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val fluxWindow = ArrayDeque<Float>()
    private val gravityEstimate = FloatArray(3)

    private var started = false
    private var lastMagnitudeG: Float? = null
    private var lastEvaluationMs = 0L
    private var staticSinceMs: Long? = null
    private var lastDirective: SamplingDirective? = null

    fun start() {
        if (started) return
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) {
            Log.w(TAG, "Accelerometer unavailable; forcing dynamic sampling")
            dispatch(
                SamplingDirective(
                    motionState = MotionState.DYNAMIC,
                    intervalMs = config.dynamicIntervalMs,
                    pauseCamera = false,
                    variance = Float.MAX_VALUE
                )
            )
            return
        }
        started = sensorManager.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        Log.i(TAG, "Accelerometer telemetry started=$started")
    }

    fun stop() {
        if (!started) return
        sensorManager.unregisterListener(this)
        started = false
        fluxWindow.clear()
        lastMagnitudeG = null
        staticSinceMs = null
        lastDirective = null
    }

    fun updateConfig(newConfig: GuardConfig) {
        config = newConfig
        lastDirective = null
        evaluate(SystemClock.elapsedRealtime(), force = true)
    }

    fun trimTransientState() {
        while (fluxWindow.size > TRIMMED_WINDOW_SIZE) {
            fluxWindow.removeFirst()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val alpha = LOW_PASS_ALPHA
        gravityEstimate[0] = alpha * gravityEstimate[0] + (1f - alpha) * event.values[0]
        gravityEstimate[1] = alpha * gravityEstimate[1] + (1f - alpha) * event.values[1]
        gravityEstimate[2] = alpha * gravityEstimate[2] + (1f - alpha) * event.values[2]

        val magnitudeG = sqrt(
            gravityEstimate[0] * gravityEstimate[0] +
                gravityEstimate[1] * gravityEstimate[1] +
                gravityEstimate[2] * gravityEstimate[2]
        ) / SensorManager.GRAVITY_EARTH

        val previous = lastMagnitudeG
        if (previous != null) {
            fluxWindow.addLast(abs(magnitudeG - previous))
            while (fluxWindow.size > WINDOW_SIZE) {
                fluxWindow.removeFirst()
            }
        }
        lastMagnitudeG = magnitudeG

        val now = SystemClock.elapsedRealtime()
        evaluate(now, force = false)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            Log.d(TAG, "Accelerometer accuracy=$accuracy")
        }
    }

    private fun evaluate(now: Long, force: Boolean) {
        if (!force && now - lastEvaluationMs < EVALUATION_INTERVAL_MS) return
        lastEvaluationMs = now

        val variance = fluxVariance()
        val isStatic = variance < config.motionVarianceThreshold
        if (isStatic) {
            if (staticSinceMs == null) {
                staticSinceMs = now
            }
        } else {
            staticSinceMs = null
        }

        val staticDuration = staticSinceMs?.let { now - it } ?: 0L
        val directive = if (isStatic) {
            SamplingDirective(
                motionState = MotionState.STATIC,
                intervalMs = config.quietIntervalMs,
                pauseCamera = staticDuration >= config.staticPauseAfterMs,
                variance = variance
            )
        } else {
            SamplingDirective(
                motionState = MotionState.DYNAMIC,
                intervalMs = config.dynamicIntervalMs,
                pauseCamera = false,
                variance = variance
            )
        }
        dispatch(directive)
    }

    private fun dispatch(directive: SamplingDirective) {
        val previous = lastDirective
        if (previous?.motionState == directive.motionState &&
            previous.intervalMs == directive.intervalMs &&
            previous.pauseCamera == directive.pauseCamera
        ) {
            return
        }
        lastDirective = directive
        Log.d(
            TAG,
            "Sampling directive state=${directive.motionState} " +
                "interval=${directive.intervalMs} pause=${directive.pauseCamera} " +
                "variance=${directive.variance}"
        )
        onDirective(directive)
    }

    private fun fluxVariance(): Float {
        if (fluxWindow.size < MIN_VARIANCE_SAMPLES) return Float.MAX_VALUE
        val values = fluxWindow.toList()
        val mean = values.sum() / values.size
        return values.sumOf { flux ->
            val delta = flux - mean
            (delta * delta).toDouble()
        }.toFloat() / values.size
    }

    companion object {
        private const val TAG = "SensorPollingManager"
        private const val LOW_PASS_ALPHA = 0.82f
        private const val WINDOW_SIZE = 24
        private const val TRIMMED_WINDOW_SIZE = 6
        private const val MIN_VARIANCE_SAMPLES = 8
        private const val EVALUATION_INTERVAL_MS = 1_000L
    }
}
