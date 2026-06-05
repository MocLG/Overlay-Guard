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

package com.moclg.overlayguard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.moclg.overlayguard.core.GuardPreferences

class OverlayGuardReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_USER_PRESENT) {
            return
        }
        if (!GuardPreferences.isServiceEnabled(context)) {
            Log.d(TAG, "Ignoring $action because monitoring is disabled")
            return
        }

        val serviceIntent = Intent(context, OverlayGuardService::class.java).apply {
            this.action = OverlayGuardService.ACTION_RESTART
        }
        runCatching {
            ContextCompat.startForegroundService(context, serviceIntent)
        }.onFailure { error ->
            Log.w(TAG, "Unable to restart Overlay Guard from $action", error)
        }
    }

    companion object {
        private const val TAG = "OverlayGuardReceiver"
    }
}
