package com.moclg.overlayguard.util

import android.util.Log
import java.io.File

/**
 * Helper to detect root access and execute privileged commands.
 *
 * Uses an interactive su session (writing to stdin) which is more
 * compatible across Magisk, KernelSU, and legacy su implementations
 * than Runtime.exec(arrayOf("su", "-c", cmd)).
 */
object RootHelper {

    private const val TAG = "RootHelper"

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
     * Execute a list of commands in a single interactive su session.
     * Writes each command to su's stdin, then sends "exit\n".
     */
    private fun execAsRoot(vararg commands: String): Boolean {
        return try {
            val process = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()

            process.outputStream.bufferedWriter().use { writer ->
                for (cmd in commands) {
                    Log.d(TAG, "root> $cmd")
                    writer.write(cmd)
                    writer.newLine()
                    writer.flush()
                }
                writer.write("exit")
                writer.newLine()
                writer.flush()
            }

            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()
            Log.d(TAG, "su exit=$exitCode output=$output")
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "execAsRoot failed", e)
            false
        }
    }

    /**
     * Enable the accessibility service via root shell commands.
     */
    fun enableAccessibilityService(
        packageName: String,
        serviceClass: String
    ): Boolean {
        val component = "$packageName/$serviceClass"
        return execAsRoot(
            "settings put secure enabled_accessibility_services $component",
            "settings put secure accessibility_enabled 1"
        )
    }

    /**
     * Disable the accessibility service via root shell commands.
     */
    fun disableAccessibilityService(): Boolean {
        return execAsRoot(
            "settings put secure enabled_accessibility_services \"\"",
            "settings put secure accessibility_enabled 0"
        )
    }

    /**
     * Suppress heads-up (popup) notifications.
     *
     * Strategy (layered for Samsung One UI compatibility):
     *  1. AOSP heads_up flag in both global & system tables
     *  2. DND via zen_mode = 2 (ALARMS ONLY)
     *  3. DND via cmd notification — uses the NotificationManagerService
     *     binder directly, which Samsung actually honours
     *  4. Collapse the notification shade
     */
    fun suppressNotifications(): Boolean {
        return execAsRoot(
            "settings put global heads_up_notifications_enabled 0",
            "settings put system heads_up_notifications_enabled 0",
            "settings put global zen_mode 2",
            "cmd notification set_dnd on",
            "cmd statusbar collapse"
        )
    }

    /**
     * Restore heads-up notifications and disable DND.
     */
    fun restoreNotifications(): Boolean {
        return execAsRoot(
            "settings put global heads_up_notifications_enabled 1",
            "settings put system heads_up_notifications_enabled 1",
            "settings put global zen_mode 0",
            "cmd notification set_dnd off"
        )
    }

    /**
     * Instantly collapse the status bar panel, which kills any visible
     * heads-up notification popup on Samsung One UI.
     * Lightweight — designed to be called on every notification event.
     */
    fun collapseStatusBar(): Boolean {
        return execAsRoot("cmd statusbar collapse")
    }

    /**
     * Grant DND / notification policy access to a package via root.
     * This allows the app to call NotificationManager.setInterruptionFilter()
     * without the user having to manually grant it in settings.
     */
    fun grantDndAccess(packageName: String): Boolean {
        return execAsRoot(
            "cmd notification allow_dnd $packageName"
        )
    }
}
