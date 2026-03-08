package com.moclg.overlayguard

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.moclg.overlayguard.sensor.RollSensorListener
import com.moclg.overlayguard.service.PrivacyOverlayService
import com.moclg.overlayguard.service.PrivacyOverlayService.Companion.DEFAULT_OVERLAY_HEIGHT
import com.moclg.overlayguard.service.PrivacyOverlayService.Companion.KEY_OVERLAY_HEIGHT
import com.moclg.overlayguard.service.PrivacyOverlayService.Companion.KEY_THRESHOLD
import com.moclg.overlayguard.service.PrivacyOverlayService.Companion.PREFS_NAME
import com.moclg.overlayguard.ui.DashboardScreen
import com.moclg.overlayguard.util.RootHelper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rooted = RootHelper.isRooted()

        // Auto-grant DND access via root so the service can toggle it
        if (rooted) {
            grantDndAccessViaRoot()
        }

        setContent {
            DashboardScreen(
                isServiceEnabled = isAccessibilityServiceEnabled(),
                overlayHeight = prefs.getInt(KEY_OVERLAY_HEIGHT, DEFAULT_OVERLAY_HEIGHT),
                thresholdDegrees = prefs.getFloat(KEY_THRESHOLD, RollSensorListener.DEFAULT_THRESHOLD),
                isRooted = rooted,
                onToggleService = { enable -> toggleService(enable, rooted) },
                onHeightChanged = { height ->
                    prefs.edit().putInt(KEY_OVERLAY_HEIGHT, height).apply()
                    PrivacyOverlayService.instance?.updateOverlayHeight(height)
                },
                onThresholdChanged = { degrees ->
                    prefs.edit().putFloat(KEY_THRESHOLD, degrees).apply()
                    PrivacyOverlayService.instance?.updateThreshold(degrees)
                },
                onOpenAccessibilitySettings = {
                    // If DND access not granted, send to that settings page
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    if (!nm.isNotificationPolicyAccessGranted) {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    } else {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
            )
        }
    }

    private fun toggleService(enable: Boolean, rooted: Boolean) {
        if (rooted) {
            val success = if (enable) {
                RootHelper.enableAccessibilityService(
                    packageName,
                    "com.moclg.overlayguard.service.PrivacyOverlayService"
                )
            } else {
                RootHelper.disableAccessibilityService()
            }
            if (!success) {
                Toast.makeText(this, "Root command failed", Toast.LENGTH_SHORT).show()
            }
        } else {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    /**
     * Grant DND / notification policy access via root command.
     * This allows NotificationManager.setInterruptionFilter() to work
     * without the user manually granting it.
     */
    private fun grantDndAccessViaRoot() {
        Thread {
            RootHelper.grantDndAccess("com.moclg.overlayguard")
        }.start()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = "$packageName/com.moclg.overlayguard.service.PrivacyOverlayService"
        return enabledServices.contains(component)
    }
}
