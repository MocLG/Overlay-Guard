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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

class ShizukuHandler(
    private val packageName: String,
    private val apkPath: String
) : IExecutionHandler {

    override val mode: ExecutionMode = ExecutionMode.SHIZUKU

    private val shellMutex = Mutex()

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
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                ExecutionResult.failure("Shizuku connection failed", throwable = t)
            }
        }
    }

    override fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun executeShellCommand(command: String): ExecutionResult {
        return withContext(Dispatchers.IO) {
            shellMutex.withLock {
                var process: Process? = null
                try {
                    if (!isAvailable()) {
                        return@withLock ExecutionResult.failure("Shizuku is unavailable")
                    }
                    val started = newShizukuProcess(arrayOf("sh", "-c", command))
                        ?: return@withLock ExecutionResult.failure(
                            "Shizuku shell process API is unavailable"
                        )
                    process = started
                    drainProcess(started)
                } catch (e: CancellationException) {
                    runCatching { process?.destroy() }
                    throw e
                } catch (t: Throwable) {
                    /*
                     * Deliberately Throwable rather than Exception. Reflection against a
                     * Shizuku API that has been shrunk by R8, or removed upstream, surfaces
                     * as NoSuchMethodError / NoClassDefFoundError, which are Errors. Those
                     * previously escaped this handler and killed the process.
                     */
                    runCatching { process?.destroy() }
                    Log.e(TAG, "Shizuku shell command failed: $command", t)
                    ExecutionResult.failure("Shizuku shell command failed", throwable = t)
                }
            }
        }
    }

    /**
     * Reads stdout on the calling thread while a helper thread drains stderr.
     *
     * Shizuku documents that the streams of a remote process must be consumed from
     * different threads. Reading stdout to EOF first and only then reading stderr
     * deadlocks as soon as the remote process fills the stderr pipe buffer, which
     * SurfaceControlCommand does whenever it prints a stack trace before exiting.
     * RootHandler never hit this because it merges stderr into stdout.
     */
    private fun drainProcess(process: Process): ExecutionResult {
        val stderrBuffer = StringBuilder()
        val stderrThread = Thread({
            runCatching {
                process.errorStream.bufferedReader().use { reader ->
                    val text = reader.readText()
                    synchronized(stderrBuffer) { stderrBuffer.append(text) }
                }
            }
        }, "OverlayGuardShizukuErr").apply {
            isDaemon = true
            start()
        }

        val stdout = runCatching {
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")

        val exitCode = process.waitFor()
        stderrThread.join(STREAM_DRAIN_JOIN_MS)
        val stderr = synchronized(stderrBuffer) { stderrBuffer.toString() }

        val output = listOf(stdout.trimEnd(), stderr.trimEnd())
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n")
            .take(MAX_CAPTURED_OUTPUT)

        return if (exitCode == 0) {
            ExecutionResult.ok("Shizuku shell command completed", output, exitCode)
        } else {
            ExecutionResult.failure("Shizuku shell command failed", output, exitCode)
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
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Power binder transaction failed: $transactionCode", t)
                ExecutionResult.failure(
                    "Power binder transaction failed",
                    throwable = t
                )
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    private fun powerBinder(): IBinder? {
        return try {
            val raw = SystemServiceHelper.getSystemService("power") ?: return null
            ShizukuBinderWrapper(raw)
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to obtain the power binder via Shizuku", t)
            null
        }
    }

    private fun newShizukuProcess(command: Array<String>): Process? {
        val method = resolveNewProcessMethod() ?: return null
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
        private const val STREAM_DRAIN_JOIN_MS = 2_000L
        private const val MAX_CAPTURED_OUTPUT = 8 * 1024

        @Volatile
        private var newProcessMethod: Method? = null

        @Volatile
        private var newProcessResolved = false

        /**
         * Resolves [Shizuku.newProcess].
         *
         * The previous implementation scanned `Shizuku::class.java.methods`, which only
         * exposes *public* members. `newProcess` is declared `private static`, so the scan
         * never matched and every shell command under Shizuku returned
         * "Shizuku shell process API is unavailable" — the whole execution path was dead.
         * `getDeclaredMethod` plus `setAccessible` reaches it. Shizuku is bundled into this
         * APK, so no hidden-API policy applies here.
         *
         * `newProcess` is deprecated upstream and is scheduled for removal in Shizuku API
         * 14, hence the null return and the Error-tolerant callers: when it disappears the
         * app degrades to a logged failure instead of terminating.
         */
        private fun resolveNewProcessMethod(): Method? {
            newProcessMethod?.let { return it }
            if (newProcessResolved) return null
            return synchronized(this) {
                val cached = newProcessMethod
                if (cached != null || newProcessResolved) {
                    cached
                } else {
                    val resolved = try {
                        Shizuku::class.java.getDeclaredMethod(
                            "newProcess",
                            Array<String>::class.java,
                            Array<String>::class.java,
                            String::class.java
                        ).apply { isAccessible = true }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Shizuku.newProcess is unavailable in this Shizuku API", t)
                        null
                    }
                    newProcessMethod = resolved
                    newProcessResolved = true
                    resolved
                }
            }
        }

        fun isBinderReady(): Boolean {
            return try {
                Shizuku.pingBinder()
            } catch (_: Throwable) {
                false
            }
        }

        fun hasPermission(): Boolean {
            return try {
                Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
        }
    }
}
