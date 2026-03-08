/*
 * Copyright 2026 Luka Gejak (luka.gejak@linux.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.moclg.overlayguard.util

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Maintains a persistent interactive root (su) shell for the lifetime
 * of the service. Commands are written to stdin and executed instantly
 * with zero process-spawn overhead.
 */
class PersistentRootShell {

    companion object {
        private const val TAG = "PersistentRootShell"
    }

    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var stdout: BufferedReader? = null

    val isAlive: Boolean
        get() = process?.isAlive == true

    /**
     * Open a persistent su session.
     * Call once when the service starts.
     */
    fun open(): Boolean {
        return try {
            val p = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            process = p
            stdin = BufferedWriter(OutputStreamWriter(p.outputStream))
            stdout = BufferedReader(InputStreamReader(p.inputStream))
            // Verify shell is alive with a no-op
            exec("echo __shell_ready__")
            Log.d(TAG, "Persistent root shell opened")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open persistent root shell", e)
            false
        }
    }

    /**
     * Execute a command in the persistent shell.
     * This writes directly to the existing su process stdin — no spawn delay.
     */
    @Synchronized
    fun exec(command: String): Boolean {
        return try {
            val w = stdin ?: return false
            w.write(command)
            w.newLine()
            w.flush()
            Log.d(TAG, "exec> $command")
            true
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $command", e)
            false
        }
    }

    /**
     * Execute multiple commands sequentially.
     */
    @Synchronized
    fun exec(vararg commands: String): Boolean {
        return try {
            val w = stdin ?: return false
            for (cmd in commands) {
                w.write(cmd)
                w.newLine()
                Log.d(TAG, "exec> $cmd")
            }
            w.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "exec batch failed", e)
            false
        }
    }

    /**
     * Close the persistent shell. Call when the service stops.
     */
    fun close() {
        try {
            stdin?.write("exit\n")
            stdin?.flush()
            stdin?.close()
            process?.waitFor()
            Log.d(TAG, "Persistent root shell closed")
        } catch (_: Exception) {
        } finally {
            stdin = null
            stdout = null
            process = null
        }
    }
}
