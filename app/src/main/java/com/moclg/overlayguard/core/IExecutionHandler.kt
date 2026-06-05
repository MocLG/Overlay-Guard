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

package com.moclg.overlayguard.core

import java.io.Closeable

enum class SettingsNamespace(val shellName: String) {
    SYSTEM("system"),
    SECURE("secure"),
    GLOBAL("global")
}

data class ExecutionResult(
    val success: Boolean,
    val message: String,
    val output: String = "",
    val exitCode: Int? = null,
    val throwable: Throwable? = null
) {
    companion object {
        fun ok(message: String, output: String = "", exitCode: Int? = 0): ExecutionResult {
            return ExecutionResult(
                success = true,
                message = message,
                output = output,
                exitCode = exitCode
            )
        }

        fun failure(
            message: String,
            output: String = "",
            exitCode: Int? = null,
            throwable: Throwable? = null
        ): ExecutionResult {
            return ExecutionResult(
                success = false,
                message = message,
                output = output,
                exitCode = exitCode,
                throwable = throwable
            )
        }
    }
}

interface IExecutionHandler : Closeable {
    val mode: ExecutionMode

    suspend fun connect(): ExecutionResult

    fun isAvailable(): Boolean

    suspend fun executeShellCommand(command: String): ExecutionResult

    suspend fun putSystemSetting(
        namespace: SettingsNamespace,
        key: String,
        value: String
    ): ExecutionResult {
        return executeShellCommand("settings put ${namespace.shellName} $key $value")
    }

    suspend fun goToSleep(uptimeMillis: Long): ExecutionResult

    suspend fun wakeUp(uptimeMillis: Long, details: String): ExecutionResult

    suspend fun setDisplayPowerMode(mode: DisplayPowerMode): ExecutionResult

    suspend fun setSurfaceBrightness(brightness: Float): ExecutionResult
}

enum class DisplayPowerMode(val rawValue: Int) {
    OFF(0),
    NORMAL(2)
}

object PowerBinderConstants {
    const val DESCRIPTOR = "android.os.IPowerManager"

    const val GO_TO_SLEEP_REASON_APPLICATION = 0
    const val GO_TO_SLEEP_FLAG_NO_DOZE = 1
    const val WAKE_REASON_APPLICATION = 2

    /*
     * Android 13 through Android 15 order in IPowerManager.aidl:
     * userActivity = FIRST_CALL_TRANSACTION + 10,
     * wakeUp = FIRST_CALL_TRANSACTION + 11,
     * goToSleep = FIRST_CALL_TRANSACTION + 12.
     * Binder FIRST_CALL_TRANSACTION is 1, so service call codes are 12 and 13.
     */
    const val TRANSACTION_WAKE_UP_API_33_TO_35 = 12
    const val TRANSACTION_GO_TO_SLEEP_API_33_TO_35 = 13
}
