# Overlay Guard ProGuard Rules
-keepattributes *Annotation*

# SurfaceControlCommand is never referenced from Kotlin. It is launched by name from a
# shell command:
#   CLASSPATH=<apk> app_process /system/bin com.moclg.overlayguard.core.SurfaceControlCommand
# R8 has no way to see that reference, so in a minified release build the class was
# renamed or removed outright and every blackout attempt died with ClassNotFoundException.
-keep class com.moclg.overlayguard.core.SurfaceControlCommand {
    public static void main(java.lang.String[]);
}

# ShizukuHandler reaches Shizuku.newProcess reflectively because it is declared private.
# R8 treats an unreferenced private method as dead code and strips it, which would break
# the Shizuku execution path again in release builds only.
-keep class rikka.shizuku.Shizuku {
    private static rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}

# The remote process wrapper is returned through reflection and its streams are used
# directly, so keep it and the Shizuku binder plumbing intact.
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }
-keep class rikka.shizuku.ShizukuBinderWrapper { *; }
-keep class rikka.shizuku.SystemServiceHelper { *; }
-keep class rikka.shizuku.ShizukuProvider { *; }
-keep interface moe.shizuku.server.** { *; }
-keep class moe.shizuku.server.** { *; }

# Reflective platform lookups performed inside SurfaceControlCommand.
-dontwarn android.view.SurfaceControl
-dontwarn com.android.internal.os.ClassLoaderFactory
-dontwarn com.android.server.display.DisplayControl
