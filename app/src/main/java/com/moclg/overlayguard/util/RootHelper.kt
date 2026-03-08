package com.moclg.overlayguard.util

import java.io.File

/**
 * Helper to detect root access and execute privileged commands.
 */
object RootHelper {

    /** Check if device has su binary available (Magisk / KernelSU). */
    fun isRooted(): Boolean {
        val paths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/adb/ksu/bin/su",  // KernelSU
            "/data/local/xbin/su",
            "/data/local/bin/su"
        )
        return paths.any { File(it).exists() }
    }

    /**
     * Enable the accessibility service via root shell commands.
     * Returns true on success, false on failure.
     */
    fun enableAccessibilityService(
        packageName: String,
        serviceClass: String
    ): Boolean {
        return try {
            val component = "$packageName/$serviceClass"
            val commands = arrayOf(
                "settings put secure enabled_accessibility_services $component",
                "settings put secure accessibility_enabled 1"
            )
            for (cmd in commands) {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                process.waitFor()
                if (process.exitValue() != 0) return false
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Disable the accessibility service via root shell commands.
     */
    fun disableAccessibilityService(): Boolean {
        return try {
            val commands = arrayOf(
                "settings put secure enabled_accessibility_services \"\"",
                "settings put secure accessibility_enabled 0"
            )
            for (cmd in commands) {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                process.waitFor()
                if (process.exitValue() != 0) return false
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
