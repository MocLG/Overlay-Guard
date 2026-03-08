package com.moclg.overlayguard

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
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = "$packageName/com.moclg.overlayguard.service.PrivacyOverlayService"
        return enabledServices.contains(component)
    }
}
