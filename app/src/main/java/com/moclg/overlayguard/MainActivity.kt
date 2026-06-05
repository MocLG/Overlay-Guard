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

package com.moclg.overlayguard

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.moclg.overlayguard.core.ExecutionMode
import com.moclg.overlayguard.core.GuardConfig
import com.moclg.overlayguard.core.GuardPreferences
import com.moclg.overlayguard.core.RootHandler
import com.moclg.overlayguard.core.ShizukuHandler
import com.moclg.overlayguard.service.OverlayGuardService
import com.moclg.overlayguard.ui.PermissionSnapshot
import com.moclg.overlayguard.ui.SettingsDashboard
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private val configState = mutableStateOf(GuardConfig())
    private val permissionsState = mutableStateOf(emptyPermissionSnapshot())
    private val serviceRunningState = mutableStateOf(false)
    private var startAfterCameraPermission = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && startAfterCameraPermission) {
            startAfterCameraPermission = false
            setMonitoringEnabled(true)
        } else {
            startAfterCameraPermission = false
            if (!granted) {
                GuardPreferences.setServiceEnabled(this, false)
                serviceRunningState.value = false
            }
            refreshState()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshState()
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            runOnUiThread {
                refreshState()
                refreshRunningServiceConfig()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        }
        refreshState()

        setContent {
            SettingsDashboard(
                config = configState.value,
                permissions = permissionsState.value,
                serviceRunning = serviceRunningState.value,
                onServiceToggle = ::setMonitoringEnabled,
                onConfigChange = ::persistConfig,
                onRequestCamera = ::requestCameraPermission,
                onRequestNotifications = ::requestNotificationPermission,
                onRequestExecution = ::requestExecutionBinding,
                onRequestWriteSettings = ::requestWriteSettings,
                onRequestBattery = ::requestBatteryOptimizationExemption
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onDestroy() {
        runCatching {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        }
        super.onDestroy()
    }

    private fun setMonitoringEnabled(enabled: Boolean) {
        if (enabled) {
            if (!hasCameraPermission()) {
                startAfterCameraPermission = true
                serviceRunningState.value = false
                GuardPreferences.setServiceEnabled(this, false)
                requestCameraPermission()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasNotificationPermission()
            ) {
                requestNotificationPermission()
            }
            serviceRunningState.value = true
            GuardPreferences.setServiceEnabled(this, true)
            startGuardService()
        } else {
            startAfterCameraPermission = false
            serviceRunningState.value = false
            GuardPreferences.setServiceEnabled(this, false)
            stopGuardService()
        }
        refreshState()
    }

    private fun persistConfig(config: GuardConfig) {
        configState.value = config
        GuardPreferences.save(this, config)
        refreshState()
        refreshRunningServiceConfig()
    }

    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestExecutionBinding() {
        when (configState.value.executionMode) {
            ExecutionMode.ROOT -> {
                val message = if (RootHandler.hasSuBinary()) {
                    "su binary found. Start monitoring to request root."
                } else {
                    "No su binary found on this device."
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }

            ExecutionMode.SHIZUKU -> requestShizukuPermission()
        }
        refreshState()
    }

    private fun requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Shizuku is not running.", Toast.LENGTH_SHORT).show()
                openShizuku()
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku permission already granted.", Toast.LENGTH_SHORT)
                    .show()
                return
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Toast.makeText(this, "Shizuku permission was denied.", Toast.LENGTH_SHORT).show()
                return
            }
            Shizuku.requestPermission(REQUEST_SHIZUKU_PERMISSION)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to request Shizuku permission.", Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun requestWriteSettings() {
        val uri = Uri.parse("package:$packageName")
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, uri)
        startActivity(intent)
    }

    private fun requestBatteryOptimizationExemption() {
        if (isBatteryUnrestricted()) {
            refreshState()
            return
        }
        val uri = Uri.parse("package:$packageName")
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri)
        runCatching { startActivity(intent) }
            .onFailure {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
    }

    private fun startGuardService() {
        val intent = Intent(this, OverlayGuardService::class.java).apply {
            action = OverlayGuardService.ACTION_START
        }
        runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.onFailure { error ->
            GuardPreferences.setServiceEnabled(this, false)
            serviceRunningState.value = false
            Toast.makeText(
                this,
                "Unable to start monitoring: ${error.javaClass.simpleName}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun stopGuardService() {
        val intent = Intent(this, OverlayGuardService::class.java).apply {
            action = OverlayGuardService.ACTION_STOP
        }
        runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.onFailure {
            stopService(Intent(this, OverlayGuardService::class.java))
        }
    }

    private fun refreshRunningServiceConfig() {
        if (!GuardPreferences.isServiceEnabled(this)) return
        val intent = Intent(this, OverlayGuardService::class.java).apply {
            action = OverlayGuardService.ACTION_REFRESH_CONFIG
        }
        runCatching {
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun refreshState() {
        val config = GuardPreferences.load(this)
        configState.value = config

        val rootAvailable = RootHandler.hasSuBinary()
        val shizukuRunning = ShizukuHandler.isBinderReady()
        val shizukuGranted = ShizukuHandler.hasPermission()
        val executionReady = when (config.executionMode) {
            ExecutionMode.ROOT -> rootAvailable
            ExecutionMode.SHIZUKU -> shizukuRunning && shizukuGranted
        }

        permissionsState.value = PermissionSnapshot(
            cameraGranted = hasCameraPermission(),
            notificationsGranted = hasNotificationPermission(),
            executionReady = executionReady,
            writeSettingsGranted = Settings.System.canWrite(this),
            batteryUnrestricted = isBatteryUnrestricted(),
            rootAvailable = rootAvailable,
            shizukuRunning = shizukuRunning,
            shizukuGranted = shizukuGranted
        )
        serviceRunningState.value = GuardPreferences.isServiceEnabled(this) ||
            OverlayGuardService.instance != null
    }

    private fun openShizuku() {
        val intent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        try {
            if (intent != null) {
                startActivity(intent)
            } else {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://shizuku.rikka.app/download/")
                    )
                )
            }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Install and start Shizuku first.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isBatteryUnrestricted(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    companion object {
        private const val REQUEST_SHIZUKU_PERMISSION = 40_013
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        private fun emptyPermissionSnapshot(): PermissionSnapshot {
            return PermissionSnapshot(
                cameraGranted = false,
                notificationsGranted = false,
                executionReady = false,
                writeSettingsGranted = false,
                batteryUnrestricted = false,
                rootAvailable = false,
                shizukuRunning = false,
                shizukuGranted = false
            )
        }
    }
}
