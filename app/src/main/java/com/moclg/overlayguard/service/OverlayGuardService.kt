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

package com.moclg.overlayguard.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.moclg.overlayguard.MainActivity
import com.moclg.overlayguard.R
import com.moclg.overlayguard.core.ExecutionMode
import com.moclg.overlayguard.core.GuardConfig
import com.moclg.overlayguard.core.GuardPreferences
import com.moclg.overlayguard.core.IExecutionHandler
import com.moclg.overlayguard.core.RootHandler
import com.moclg.overlayguard.core.ShizukuHandler
import com.moclg.overlayguard.engine.CameraVisionEngine
import com.moclg.overlayguard.engine.DisplayController
import com.moclg.overlayguard.engine.SensorPollingManager
import com.moclg.overlayguard.engine.VisionDecision
import com.moclg.overlayguard.engine.VisionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class OverlayGuardService : LifecycleService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var config: GuardConfig? = null
    private var executionHandler: IExecutionHandler? = null
    private var displayController: DisplayController? = null
    private var visionEngine: CameraVisionEngine? = null
    private var sensorPollingManager: SensorPollingManager? = null
    private var guardStarted = false
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        Log.i(TAG, "OverlayGuardService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                GuardPreferences.setServiceEnabled(this, false)
                serviceScope.launch {
                    stopGuard()
                    ServiceCompat.stopForeground(
                        this@OverlayGuardService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE
                    )
                    stopSelf(startId)
                }
            }

            ACTION_REFRESH_CONFIG -> {
                startForegroundIfNeeded()
                refreshRuntimeConfig()
            }

            ACTION_START,
            ACTION_RESTART -> {
                GuardPreferences.setServiceEnabled(this, true)
                startForegroundIfNeeded()
                startGuard()
            }
        }
        return START_STICKY
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            Log.w(TAG, "Memory pressure level=$level; trimming non-essential caches")
            visionEngine?.trimTransientState()
            sensorPollingManager?.trimTransientState()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "OverlayGuardService destroying")
        runBlocking(Dispatchers.IO) {
            runCatching { displayController?.close() }
            runCatching { executionHandler?.close() }
        }
        visionEngine?.stop()
        sensorPollingManager?.stop()
        serviceScope.cancel()
        instance = null
        guardStarted = false
        foregroundStarted = false
        super.onDestroy()
    }

    private fun startGuard() {
        if (guardStarted) {
            refreshRuntimeConfig()
            return
        }
        val loaded = GuardPreferences.load(this)
        config = loaded
        val handler = createExecutionHandler(loaded.executionMode)
        executionHandler = handler
        displayController = DisplayController(
            context = applicationContext,
            executionHandler = handler,
            blackoutType = loaded.blackoutType
        )
        serviceScope.launch(Dispatchers.IO) {
            val result = handler.connect()
            if (result.success) {
                Log.i(TAG, result.message)
            } else {
                Log.w(TAG, "Execution handler connection failed: ${result.message}")
            }
        }

        if (hasCameraPermission()) {
            visionEngine = CameraVisionEngine(
                context = applicationContext,
                config = loaded,
                onResult = ::handleVisionResult
            ).also { it.start(this) }
        } else {
            Log.w(TAG, "Camera permission missing; vision engine not started")
        }

        sensorPollingManager = SensorPollingManager(
            context = applicationContext,
            config = loaded
        ) { directive ->
            visionEngine?.updateSampling(
                intervalMs = directive.intervalMs,
                pauseCamera = directive.pauseCamera
            )
        }.also { it.start() }

        guardStarted = true
        Log.i(TAG, "Overlay Guard monitoring started")
    }

    private fun refreshRuntimeConfig() {
        val previous = config
        val loaded = GuardPreferences.load(this)
        config = loaded

        if (previous == null) {
            if (!guardStarted) startGuard()
            return
        }

        val currentHandler = executionHandler
        if (currentHandler == null || currentHandler.mode != loaded.executionMode) {
            serviceScope.launch {
                runCatching { displayController?.restore() }
                currentHandler?.close()
                val replacement = createExecutionHandler(loaded.executionMode)
                executionHandler = replacement
                displayController = DisplayController(
                    context = applicationContext,
                    executionHandler = replacement,
                    blackoutType = loaded.blackoutType
                )
                serviceScope.launch(Dispatchers.IO) {
                    val result = replacement.connect()
                    if (!result.success) {
                        Log.w(TAG, "Execution handler reconnect failed: ${result.message}")
                    }
                }
            }
        } else {
            serviceScope.launch {
                displayController?.updateBlackoutType(loaded.blackoutType)
            }
        }

        visionEngine?.updateConfig(loaded)
        sensorPollingManager?.updateConfig(loaded)
        if (visionEngine == null && hasCameraPermission()) {
            visionEngine = CameraVisionEngine(
                context = applicationContext,
                config = loaded,
                onResult = ::handleVisionResult
            ).also { it.start(this) }
        }
    }

    private suspend fun stopGuard() {
        guardStarted = false
        sensorPollingManager?.stop()
        sensorPollingManager = null
        visionEngine?.stop()
        visionEngine = null
        displayController?.close()
        displayController = null
        executionHandler?.close()
        executionHandler = null
        Log.i(TAG, "Overlay Guard monitoring stopped")
    }

    private fun handleVisionResult(result: VisionResult) {
        when (result.decision) {
            VisionDecision.INTRUDER -> serviceScope.launch {
                displayController?.blank()
            }

            VisionDecision.CLEAR -> serviceScope.launch {
                displayController?.restore()
            }

            VisionDecision.UNKNOWN -> {
                Log.d(TAG, "Vision state unknown: ${result.reason}")
            }
        }
    }

    private fun createExecutionHandler(mode: ExecutionMode): IExecutionHandler {
        return when (mode) {
            ExecutionMode.ROOT -> RootHandler(packageName)
            ExecutionMode.SHIZUKU -> ShizukuHandler(packageName)
        }
    }

    private fun startForegroundIfNeeded() {
        val notification = buildNotification()
        val typeMask = foregroundServiceTypeMask()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, typeMask)
            foregroundStarted = true
        } catch (e: SecurityException) {
            Log.e(TAG, "Typed foreground start failed; retrying without type mask", e)
            startForeground(NOTIFICATION_ID, notification)
            foregroundStarted = true
        }
    }

    private fun foregroundServiceTypeMask(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val camera = if (hasCameraPermission()) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0
        }
        val specialUse = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return camera or specialUse
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, OverlayGuardService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this,
            1,
            stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_monitoring))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(0, getString(R.string.notification_stop), stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "OverlayGuardService"
        private const val NOTIFICATION_ID = 42
        private const val NOTIFICATION_CHANNEL_ID = "overlay_guard_monitor"

        const val ACTION_START = "com.moclg.overlayguard.action.START"
        const val ACTION_STOP = "com.moclg.overlayguard.action.STOP"
        const val ACTION_RESTART = "com.moclg.overlayguard.action.RESTART"
        const val ACTION_REFRESH_CONFIG = "com.moclg.overlayguard.action.REFRESH_CONFIG"

        var instance: OverlayGuardService? = null
            private set
    }
}
