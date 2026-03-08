/*
 * Copyright 2026 Luka Gejak (luka.gejak@linux.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
