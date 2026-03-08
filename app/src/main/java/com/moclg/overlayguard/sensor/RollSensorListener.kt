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

package com.moclg.overlayguard.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Listens to the rotation-vector sensor and derives the device roll angle.
 *
 * When |roll| exceeds [thresholdDegrees], the callback fires with alpha = 1.0;
 * otherwise alpha = 0.0.
 */
class RollSensorListener(
    private var thresholdDegrees: Float = DEFAULT_THRESHOLD,
    private val onAlphaChanged: (Float) -> Unit
) : SensorEventListener {

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    /** Update the threshold at runtime (from UI slider). */
    fun setThreshold(degrees: Float) {
        thresholdDegrees = degrees
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        // Derive rotation matrix from the rotation vector
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // orientationAngles: [azimuth, pitch, roll] in radians
        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        val rollRadians = orientationAngles[2]
        val rollDegrees = Math.toDegrees(rollRadians.toDouble()).toFloat()

        val alpha = if (abs(rollDegrees) > thresholdDegrees) 1.0f else 0.0f
        onAlphaChanged(alpha)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    companion object {
        const val DEFAULT_THRESHOLD = 25f // degrees
    }
}
