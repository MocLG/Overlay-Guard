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

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class RootHandler(
    private val packageName: String
) : IExecutionHandler {

    override val mode: ExecutionMode = ExecutionMode.ROOT

    private val shellMutex = Mutex()
    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var stdout: BufferedReader? = null

    override suspend fun connect(): ExecutionResult {
        return executeShellCommand("id -u").let { result ->
            if (result.success && result.output.lineSequence().any { it.trim() == "0" }) {
                ExecutionResult.ok("Root shell connected", result.output)
            } else if (result.success) {
                ExecutionResult.failure("su did not grant uid 0", result.output, result.exitCode)
            } else {
                result
            }
        }
    }

    override fun isAvailable(): Boolean {
        return SU_PATHS.any { File(it).canExecute() || File(it).exists() }
    }

    override suspend fun executeShellCommand(command: String): ExecutionResult {
        return withContext(Dispatchers.IO) {
            shellMutex.withLock {
                try {
                    ensureShellLocked()
                    val writer = stdin ?: return@withLock ExecutionResult.failure("Root stdin unavailable")
                    val reader = stdout ?: return@withLock ExecutionResult.failure("Root stdout unavailable")
                    val marker = "__overlay_guard_${System.nanoTime()}__"
                    writer.write("($command) 2>&1; echo $marker:$?\n")
                    writer.flush()

                    val output = StringBuilder()
                    var exitCode: Int? = null
                    while (true) {
                        val line = reader.readLine()
                            ?: return@withLock ExecutionResult.failure(
                                message = "Root shell closed while running command",
                                output = output.toString()
                            )
                        if (line.startsWith("$marker:")) {
                            exitCode = line.substringAfter(':').toIntOrNull()
                            break
                        }
                        output.appendLine(line)
                    }

                    val text = output.toString().trimEnd()
                    if (exitCode == 0) {
                        Log.d(TAG, "root ok: $command")
                        ExecutionResult.ok("Root command completed", text, exitCode)
                    } else {
                        Log.w(TAG, "root failed($exitCode): $command\n$text")
                        ExecutionResult.failure("Root command failed", text, exitCode)
                    }
                } catch (e: Exception) {
                    close()
                    Log.e(TAG, "Root command crashed: $command", e)
                    ExecutionResult.failure("Root command crashed", throwable = e)
                }
            }
        }
    }

    override suspend fun goToSleep(uptimeMillis: Long): ExecutionResult {
        val code = goToSleepTransactionCode()
            ?: return unsupportedPowerTransactionResult()
        return executeShellCommand(
            "service call power $code i64 $uptimeMillis " +
                "i32 ${PowerBinderConstants.GO_TO_SLEEP_REASON_APPLICATION} " +
                "i32 ${PowerBinderConstants.GO_TO_SLEEP_FLAG_NO_DOZE}"
        )
    }

    override suspend fun wakeUp(uptimeMillis: Long, details: String): ExecutionResult {
        val code = wakeUpTransactionCode()
            ?: return unsupportedPowerTransactionResult()
        return executeShellCommand(
            "service call power $code i64 $uptimeMillis " +
                "i32 ${PowerBinderConstants.WAKE_REASON_APPLICATION} " +
                "s16 ${shellQuote(details)} s16 ${shellQuote(packageName)}"
        )
    }

    override fun close() {
        try {
            stdin?.write("exit\n")
            stdin?.flush()
        } catch (_: Exception) {
        }
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        stdin = null
        stdout = null
        process = null
    }

    private fun ensureShellLocked() {
        if (process?.isAlive == true && stdin != null && stdout != null) return
        val newProcess = ProcessBuilder("su")
            .redirectErrorStream(true)
            .start()
        process = newProcess
        stdin = BufferedWriter(OutputStreamWriter(newProcess.outputStream))
        stdout = BufferedReader(InputStreamReader(newProcess.inputStream))
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
            "Root power binder transactions are only mapped for Android 13 through 15"
        )
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    companion object {
        private const val TAG = "RootHandler"

        private val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/data/adb/ksu/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/su/bin/su"
        )

        fun hasSuBinary(): Boolean {
            return SU_PATHS.any { File(it).canExecute() || File(it).exists() }
        }
    }
}
