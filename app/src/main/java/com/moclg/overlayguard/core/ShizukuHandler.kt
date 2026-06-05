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

import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedReader
import java.io.InputStreamReader

class ShizukuHandler(
    private val packageName: String,
    private val apkPath: String
) : IExecutionHandler {

    override val mode: ExecutionMode = ExecutionMode.SHIZUKU

    override suspend fun connect(): ExecutionResult {
        return withContext(Dispatchers.IO) {
            try {
                if (!Shizuku.pingBinder()) {
                    return@withContext ExecutionResult.failure("Shizuku binder is not running")
                }
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    return@withContext ExecutionResult.failure("Shizuku permission is not granted")
                }
                val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
                ExecutionResult.ok("Shizuku connected as uid $uid")
            } catch (e: Exception) {
                ExecutionResult.failure("Shizuku connection failed", throwable = e)
            }
        }
    }

    override fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun executeShellCommand(command: String): ExecutionResult {
        return withContext(Dispatchers.IO) {
            try {
                if (!isAvailable()) {
                    return@withContext ExecutionResult.failure("Shizuku is unavailable")
                }
                val process = newShizukuProcess(arrayOf("sh", "-c", command))
                    ?: return@withContext ExecutionResult.failure(
                        "Shizuku shell process API is unavailable"
                    )
                val stdout = BufferedReader(InputStreamReader(process.inputStream)).use {
                    it.readText()
                }
                val stderr = BufferedReader(InputStreamReader(process.errorStream)).use {
                    it.readText()
                }
                val exitCode = process.waitFor()
                val output = listOf(stdout.trimEnd(), stderr.trimEnd())
                    .filter { it.isNotBlank() }
                    .joinToString(separator = "\n")
                if (exitCode == 0) {
                    ExecutionResult.ok("Shizuku shell command completed", output, exitCode)
                } else {
                    ExecutionResult.failure("Shizuku shell command failed", output, exitCode)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku shell command crashed: $command", e)
                ExecutionResult.failure("Shizuku shell command crashed", throwable = e)
            }
        }
    }

    override suspend fun goToSleep(uptimeMillis: Long): ExecutionResult {
        val code = goToSleepTransactionCode()
            ?: return unsupportedPowerTransactionResult()
        return transactPower(code) { data ->
            data.writeLong(uptimeMillis)
            data.writeInt(PowerBinderConstants.GO_TO_SLEEP_REASON_APPLICATION)
            data.writeInt(PowerBinderConstants.GO_TO_SLEEP_FLAG_NO_DOZE)
        }
    }

    override suspend fun wakeUp(uptimeMillis: Long, details: String): ExecutionResult {
        val code = wakeUpTransactionCode()
            ?: return unsupportedPowerTransactionResult()
        return transactPower(code) { data ->
            data.writeLong(uptimeMillis)
            data.writeInt(PowerBinderConstants.WAKE_REASON_APPLICATION)
            data.writeString(details)
            data.writeString(packageName)
        }
    }

    override suspend fun setDisplayPowerMode(mode: DisplayPowerMode): ExecutionResult {
        return executeSurfaceControlCommand("power", mode.rawValue.toString())
    }

    override suspend fun setSurfaceBrightness(brightness: Float): ExecutionResult {
        return executeSurfaceControlCommand("brightness", brightness.toString())
    }

    override fun close() {
        // Shizuku owns the remote binder. Nothing is retained locally.
    }

    private suspend fun transactPower(
        transactionCode: Int,
        writeArgs: (Parcel) -> Unit
    ): ExecutionResult {
        return withContext(Dispatchers.IO) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                if (!isAvailable()) {
                    return@withContext ExecutionResult.failure("Shizuku is unavailable")
                }
                val binder = powerBinder()
                    ?: return@withContext ExecutionResult.failure("Power binder unavailable")

                data.writeInterfaceToken(PowerBinderConstants.DESCRIPTOR)
                writeArgs(data)
                val transacted = binder.transact(transactionCode, data, reply, 0)
                if (!transacted) {
                    return@withContext ExecutionResult.failure(
                        "Power binder rejected transaction $transactionCode"
                    )
                }
                reply.readException()
                ExecutionResult.ok("Power binder transaction $transactionCode completed")
            } catch (e: Exception) {
                Log.e(TAG, "Power binder transaction failed: $transactionCode", e)
                ExecutionResult.failure(
                    "Power binder transaction failed",
                    throwable = e
                )
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    private fun powerBinder(): IBinder? {
        val raw = SystemServiceHelper.getSystemService("power") ?: return null
        return ShizukuBinderWrapper(raw)
    }

    private fun newShizukuProcess(command: Array<String>): Process? {
        val method = Shizuku::class.java.methods.firstOrNull { method ->
            method.name == "newProcess" &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes[0].isArray &&
                method.parameterTypes[1].isArray &&
                method.parameterTypes[2] == String::class.java
        } ?: return null
        @Suppress("UNCHECKED_CAST")
        return method.invoke(null, command, null, null) as? Process
    }

    private fun wakeUpTransactionCode(): Int? {
        return when (Build.VERSION.SDK_INT) {
            in Build.VERSION_CODES.TIRAMISU..35 ->
                PowerBinderConstants.TRANSACTION_WAKE_UP_API_33_TO_35
            else -> null
        }
    }

    private fun goToSleepTransactionCode(): Int? {
        return when (Build.VERSION.SDK_INT) {
            in Build.VERSION_CODES.TIRAMISU..35 ->
                PowerBinderConstants.TRANSACTION_GO_TO_SLEEP_API_33_TO_35
            else -> null
        }
    }

    private fun unsupportedPowerTransactionResult(): ExecutionResult {
        return ExecutionResult.failure(
            "Shizuku power binder transactions are only mapped for Android 13 through 15"
        )
    }

    private suspend fun executeSurfaceControlCommand(
        operation: String,
        value: String
    ): ExecutionResult {
        return executeShellCommand(
            "CLASSPATH=${shellQuote(apkPath)} app_process /system/bin " +
                "com.moclg.overlayguard.core.SurfaceControlCommand " +
                "${shellQuote(operation)} ${shellQuote(value)}"
        )
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    companion object {
        private const val TAG = "ShizukuHandler"

        fun isBinderReady(): Boolean {
            return try {
                Shizuku.pingBinder()
            } catch (_: Exception) {
                false
            }
        }

        fun hasPermission(): Boolean {
            return try {
                Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
        }
    }
}
