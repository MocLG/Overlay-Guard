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
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.moclg.overlayguard.core.GuardConfig
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

data class VisionResult(
    val timestampMs: Long,
    val faceCount: Int,
    val attentiveIntruderCount: Int,
    val decision: VisionDecision,
    val reason: String
)

enum class VisionDecision {
    CLEAR,
    INTRUDER,
    UNKNOWN
}

class CameraVisionEngine(
    private val context: Context,
    private var config: GuardConfig,
    private val onResult: (VisionResult) -> Unit
) {

    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OverlayGuardVision").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val analyzerBusy = AtomicBoolean(false)
    private val recentResults = ArrayDeque<VisionResult>()

    private var detector: FaceDetector? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var samplingIntervalMs: Long = config.dynamicIntervalMs
    private var pausedForStaticDevice = false
    private var lastAnalyzedAtMs = 0L
    private var lastIntruderDetectedAtMs = 0L
    private var started = false

    fun start(owner: LifecycleOwner) {
        lifecycleOwner = owner
        if (detector == null) {
            detector = FaceDetection.getClient(faceDetectorOptions())
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    cameraProvider = future.get()
                    started = true
                    if (!pausedForStaticDevice) {
                        bindAnalyzer()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Camera provider initialization failed", e)
                    emitUnknown("camera_provider_failed")
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    fun stop() {
        started = false
        cameraProvider?.unbindAll()
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        detector?.close()
        detector = null
        analyzerExecutor.shutdownNow()
    }

    fun updateConfig(newConfig: GuardConfig) {
        config = newConfig
        samplingIntervalMs = min(
            samplingIntervalMs,
            newConfig.dynamicIntervalMs
        )
    }

    fun updateSampling(intervalMs: Long, pauseCamera: Boolean) {
        samplingIntervalMs = intervalMs
        if (pausedForStaticDevice == pauseCamera) return
        pausedForStaticDevice = pauseCamera
        ContextCompat.getMainExecutor(context).execute {
            if (!started) return@execute
            if (pauseCamera) {
                imageAnalysis?.clearAnalyzer()
                imageAnalysis?.let { cameraProvider?.unbind(it) }
                Log.i(TAG, "Camera analysis paused for static device state")
            } else {
                bindAnalyzer()
                Log.i(TAG, "Camera analysis resumed for dynamic device state")
            }
        }
    }

    fun trimTransientState() {
        synchronized(recentResults) {
            recentResults.clear()
        }
    }

    private fun bindAnalyzer() {
        val owner = lifecycleOwner ?: return
        val provider = cameraProvider ?: return
        if (pausedForStaticDevice) return

        @Suppress("DEPRECATION")
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(960, 720))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { imageAnalysis ->
                imageAnalysis.setAnalyzer(analyzerExecutor, ::analyze)
            }

        try {
            imageAnalysis?.let { provider.unbind(it) }
            imageAnalysis = analysis
            provider.bindToLifecycle(
                owner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                analysis
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to bind front camera analysis", e)
            emitUnknown("camera_bind_failed")
        }
    }

    private fun analyze(imageProxy: ImageProxy) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (pausedForStaticDevice || now - lastAnalyzedAtMs < samplingIntervalMs) {
            imageProxy.close()
            return
        }
        if (!analyzerBusy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        lastAnalyzedAtMs = now

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            analyzerBusy.set(false)
            imageProxy.close()
            emitUnknown("empty_image")
            return
        }

        val input = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )
        detector?.process(input)
            ?.addOnSuccessListener(analyzerExecutor) { faces ->
                val result = evaluateFaces(
                    faces = faces,
                    frameWidth = imageProxy.width,
                    frameHeight = imageProxy.height,
                    timestampMs = now
                )
                recordAndEmit(result)
            }
            ?.addOnFailureListener(analyzerExecutor) { error ->
                Log.w(TAG, "Face detection failed", error)
                emitUnknown("mlkit_failure")
            }
            ?.addOnCompleteListener(analyzerExecutor) {
                analyzerBusy.set(false)
                imageProxy.close()
            } ?: run {
            analyzerBusy.set(false)
            imageProxy.close()
            emitUnknown("detector_unavailable")
        }
    }

    private fun evaluateFaces(
        faces: List<Face>,
        frameWidth: Int,
        frameHeight: Int,
        timestampMs: Long
    ): VisionResult {
        if (faces.isEmpty()) {
            return VisionResult(
                timestampMs = timestampMs,
                faceCount = 0,
                attentiveIntruderCount = 0,
                decision = VisionDecision.UNKNOWN,
                reason = "no_faces"
            )
        }
        if (faces.size == 1) {
            return applyIntruderHold(
                VisionResult(
                    timestampMs = timestampMs,
                    faceCount = 1,
                    attentiveIntruderCount = 0,
                    decision = VisionDecision.CLEAR,
                    reason = "single_face"
                )
            )
        }

        val primary = faces.maxBy { face ->
            face.boundingBox.width().coerceAtLeast(0) *
                face.boundingBox.height().coerceAtLeast(0)
        }
        val secondaryFaces = faces.filterNot { it === primary }
        val attentiveIntruders = secondaryFaces.count { face ->
            isAttentiveSecondaryFace(face, frameWidth, frameHeight)
        }

        val result = if (attentiveIntruders > 0) {
            VisionResult(
                timestampMs = timestampMs,
                faceCount = faces.size,
                attentiveIntruderCount = attentiveIntruders,
                decision = VisionDecision.INTRUDER,
                reason = "secondary_face_looking_at_display"
            )
        } else {
            VisionResult(
                timestampMs = timestampMs,
                faceCount = faces.size,
                attentiveIntruderCount = 0,
                decision = VisionDecision.CLEAR,
                reason = "secondary_faces_not_attentive"
            )
        }
        return applyIntruderHold(result)
    }

    private fun isAttentiveSecondaryFace(
        face: Face,
        frameWidth: Int,
        frameHeight: Int
    ): Boolean {
        val box = face.boundingBox
        val width = frameWidth.coerceAtLeast(1).toFloat()
        val height = frameHeight.coerceAtLeast(1).toFloat()
        val boxWidth = box.width().coerceAtLeast(1).toFloat()
        val boxHeight = box.height().coerceAtLeast(1).toFloat()
        val areaRatio = (boxWidth * boxHeight) / (width * height)
        if (areaRatio < MIN_SECONDARY_FACE_AREA_RATIO) {
            return false
        }

        val configuredYawLimit = config.attentionYawDegrees.coerceIn(
            MIN_ATTENTION_YAW,
            MAX_ATTENTION_YAW
        )
        val yawLimit = min(
            configuredYawLimit + SECONDARY_YAW_TOLERANCE_DEGREES,
            MAX_ATTENTION_YAW
        )
        if (yawLimit >= FAIL_SAFE_YAW_BYPASS_DEGREES) {
            return true
        }

        if (abs(face.headEulerAngleY) > yawLimit) {
            return false
        }

        return true
    }

    private fun applyIntruderHold(result: VisionResult): VisionResult {
        if (result.decision == VisionDecision.INTRUDER) {
            lastIntruderDetectedAtMs = result.timestampMs
            return result
        }

        val recentIntruder = lastIntruderDetectedAtMs > 0L &&
            result.timestampMs - lastIntruderDetectedAtMs <= INTRUDER_HOLD_MS
        if (result.decision == VisionDecision.CLEAR && recentIntruder) {
            return result.copy(
                attentiveIntruderCount = result.attentiveIntruderCount.coerceAtLeast(1),
                decision = VisionDecision.INTRUDER,
                reason = "${result.reason}_intruder_hold"
            )
        }

        return result
    }

    private fun recordAndEmit(result: VisionResult) {
        synchronized(recentResults) {
            recentResults.addLast(result)
            while (recentResults.size > MAX_RECENT_RESULTS) {
                recentResults.removeFirst()
            }
        }
        onResult(result)
    }

    private fun emitUnknown(reason: String) {
        recordAndEmit(
            VisionResult(
                timestampMs = android.os.SystemClock.elapsedRealtime(),
                faceCount = 0,
                attentiveIntruderCount = 0,
                decision = VisionDecision.UNKNOWN,
                reason = reason
            )
        )
    }

    private fun faceDetectorOptions(): FaceDetectorOptions {
        /*
         * ML Kit Face Detection is an on-device detector, but it does not expose
         * public NNAPI/GPU delegate selection. All inference work is kept off the
         * main thread and CameraX drops stale frames before they reach the model.
         */
        return FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.025f)
            .enableTracking()
            .build()
    }

    companion object {
        private const val TAG = "CameraVisionEngine"
        private const val MAX_RECENT_RESULTS = 32
        private const val MIN_SECONDARY_FACE_AREA_RATIO = 0.0015f
        private const val MIN_ATTENTION_YAW = 30f
        private const val MAX_ATTENTION_YAW = 90f
        private const val SECONDARY_YAW_TOLERANCE_DEGREES = 20f
        private const val FAIL_SAFE_YAW_BYPASS_DEGREES = 85f
        private const val INTRUDER_HOLD_MS = 2_500L
    }
}
