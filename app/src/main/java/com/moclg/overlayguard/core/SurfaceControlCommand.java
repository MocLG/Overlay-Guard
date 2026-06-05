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

package com.moclg.overlayguard.core;

import android.os.Build;
import android.os.IBinder;

import java.lang.reflect.Method;

public final class SurfaceControlCommand {

    private static final int POWER_MODE_OFF = 0;
    private static final int POWER_MODE_NORMAL = 2;

    private SurfaceControlCommand() {
    }

    public static void main(String[] args) {
        try {
            if (args.length != 2) {
                throw new IllegalArgumentException("usage: power <0|2> or brightness <-1..1>");
            }
            exemptHiddenApis();
            String operation = args[0];
            if ("power".equals(operation)) {
                int mode = Integer.parseInt(args[1]);
                if (mode != POWER_MODE_OFF && mode != POWER_MODE_NORMAL) {
                    throw new IllegalArgumentException("power mode must be 0 or 2");
                }
                setDisplayPowerMode(primaryDisplayToken(), mode);
            } else if ("brightness".equals(operation)) {
                float brightness = Float.parseFloat(args[1]);
                setDisplayBrightness(primaryDisplayToken(), brightness);
            } else {
                throw new IllegalArgumentException("unknown operation: " + operation);
            }
            System.out.println("OK");
        } catch (Throwable throwable) {
            throwable.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void exemptHiddenApis() {
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime");
            Method setHiddenApiExemptions = vmRuntimeClass.getDeclaredMethod(
                    "setHiddenApiExemptions",
                    String[].class
            );
            Object runtime = getRuntime.invoke(null);
            setHiddenApiExemptions.invoke(runtime, (Object) new String[]{"L"});
        } catch (Throwable ignored) {
            // Best effort. Privileged app_process callers often do not need this.
        }
    }

    private static IBinder primaryDisplayToken() throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Class<?> displayControl = loadDisplayControlClass();
            long[] ids = (long[]) displayControl
                    .getMethod("getPhysicalDisplayIds")
                    .invoke(null);
            if (ids == null || ids.length == 0) {
                throw new IllegalStateException("No physical displays");
            }
            return (IBinder) displayControl
                    .getMethod("getPhysicalDisplayToken", long.class)
                    .invoke(null, ids[0]);
        }
        Class<?> surfaceControl = Class.forName("android.view.SurfaceControl");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return (IBinder) surfaceControl
                    .getMethod("getInternalDisplayToken")
                    .invoke(null);
        }
        return (IBinder) surfaceControl
                .getMethod("getBuiltInDisplay", int.class)
                .invoke(null, 0);
    }

    private static Class<?> loadDisplayControlClass() throws Exception {
        Class<?> classLoaderFactory = Class.forName("com.android.internal.os.ClassLoaderFactory");
        Method createClassLoader = classLoaderFactory.getDeclaredMethod(
                "createClassLoader",
                String.class,
                String.class,
                String.class,
                ClassLoader.class,
                int.class,
                boolean.class,
                String.class
        );
        ClassLoader classLoader = (ClassLoader) createClassLoader.invoke(
                null,
                "/system/framework/services.jar",
                null,
                null,
                ClassLoader.getSystemClassLoader(),
                0,
                true,
                null
        );
        Class<?> displayControl = classLoader.loadClass("com.android.server.display.DisplayControl");

        Method loadLibrary = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
        loadLibrary.setAccessible(true);
        loadLibrary.invoke(Runtime.getRuntime(), displayControl, "android_servers");
        return displayControl;
    }

    private static void setDisplayPowerMode(IBinder token, int mode) throws Exception {
        Class<?> surfaceControl = Class.forName("android.view.SurfaceControl");
        surfaceControl
                .getMethod("setDisplayPowerMode", IBinder.class, int.class)
                .invoke(null, token, mode);
    }

    private static void setDisplayBrightness(IBinder token, float brightness) throws Exception {
        Class<?> surfaceControl = Class.forName("android.view.SurfaceControl");
        surfaceControl
                .getMethod("setDisplayBrightness", IBinder.class, float.class)
                .invoke(null, token, brightness);
    }
}
