package com.moclg.overlayguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.Sensor
import android.hardware.SensorManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.moclg.overlayguard.sensor.RollSensorListener
import com.moclg.overlayguard.util.PersistentRootShell
import com.moclg.overlayguard.util.RootHelper

/**
 * AccessibilityService that draws a black overlay over the status bar region.
 *
 * The overlay uses TYPE_ACCESSIBILITY_OVERLAY which does not require
 * SYSTEM_ALERT_WINDOW permission and renders above all other content.
 */
class PrivacyOverlayService : AccessibilityService() {

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var sensorManager: SensorManager? = null
    private var rollSensorListener: RollSensorListener? = null
    private var notificationManager: NotificationManager? = null

    /** Persistent root shell — zero-latency command execution. */
    private val rootShell = PersistentRootShell()

    /** Screen width for computing the swipe midpoint. */
    private var screenCenterX: Int = 540

    /** Height of the overlay in pixels — adjustable via SharedPreferences. */
    var overlayHeightPx: Int = DEFAULT_OVERLAY_HEIGHT

    /** Tracks whether DND is currently engaged. */
    private var dndActive = false

    /** Current alpha of the overlay (0.0 = hidden, 1.0 = visible). */
    var overlayAlpha: Float = 0f
        set(value) {
            field = value
            overlayView?.alpha = value

            // Toggle DND when the black box appears / disappears
            if (value >= 1f && !dndActive) {
                dndActive = true
                enableDnd()
            } else if (value < 1f && dndActive) {
                dndActive = false
                disableDnd()
            }
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Get screen width for heads-up swipe dismissal
        val display = windowManager?.defaultDisplay
        val size = Point()
        display?.getRealSize(size)
        screenCenterX = size.x / 2

        loadPreferences()
        createOverlay()
        registerSensor()

        // Open persistent root shell so future commands execute instantly
        if (RootHelper.isRooted()) {
            Thread {
                rootShell.open()
            }.start()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (overlayAlpha < 1f) return
        if (event?.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            swipeAwayHeadsUp()
        }
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        unregisterSensor()
        removeOverlay()
        // Restore DND
        if (dndActive) {
            disableDnd()
        }
        // Close the persistent root shell
        rootShell.close()
        super.onDestroy()
    }

    // ──────────────────────────────────────────────
    //  DND management
    // ──────────────────────────────────────────────

    private fun enableDnd() {
        // 1. Android API — guaranteed reliable if permission granted
        try {
            val nm = notificationManager
            if (nm != null && nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
                Log.d(TAG, "DND enabled via NotificationManager API")
            }
        } catch (e: Exception) {
            Log.w(TAG, "NotificationManager DND failed", e)
        }

        // 2. Root fallback — via persistent shell (instant)
        if (rootShell.isAlive) {
            rootShell.exec(
                "settings put global heads_up_notifications_enabled 0",
                "settings put system heads_up_notifications_enabled 0",
                "settings put global zen_mode 2",
                "cmd notification set_dnd on"
            )
        }

        // 3. Swipe away any existing heads-up popup immediately
        swipeAwayHeadsUp()
    }

    private fun disableDnd() {
        try {
            val nm = notificationManager
            if (nm != null && nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                Log.d(TAG, "DND disabled via NotificationManager API")
            }
        } catch (e: Exception) {
            Log.w(TAG, "NotificationManager DND restore failed", e)
        }

        if (rootShell.isAlive) {
            rootShell.exec(
                "settings put global heads_up_notifications_enabled 1",
                "settings put system heads_up_notifications_enabled 1",
                "settings put global zen_mode 0",
                "cmd notification set_dnd off"
            )
        }
    }

    /**
     * Physically swipe away any visible heads-up notification popup.
     *
     * Samsung One UI renders heads-up notifications in a separate HUN window
     * that sits ABOVE TYPE_ACCESSIBILITY_OVERLAY. collapsePanels() only
     * collapses the notification shade — it does NOT dismiss heads-up.
     *
     * The only reliable way to dismiss a Samsung heads-up is to simulate
     * the same upward swipe gesture the user would make. `input swipe`
     * injects at the input dispatcher level and hits whatever window is
     * at those coordinates, including the HUN window our overlay can't
     * cover.
     *
     * The swipe runs via the persistent root shell (no spawn delay).
     * Duration = 30ms → near-instant visual dismissal.
     */
    private fun swipeAwayHeadsUp() {
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        if (rootShell.isAlive) {
            // Swipe upward from head-up area (y=80) to off-screen (y=-100)
            // at the horizontal center of the display. 30ms duration.
            rootShell.exec("input swipe $screenCenterX 80 $screenCenterX -100 30")
        } else {
            // Rootless fallback: use AccessibilityService.dispatchGesture()
            // to simulate the same upward swipe. Works without root as long
            // as the accessibility service is enabled.
            swipeViaGesture()
        }
    }

    /**
     * Dispatch an upward swipe gesture via the AccessibilityService API.
     * This does not require root — the service itself injects the touch
     * events at the framework level.
     */
    private fun swipeViaGesture() {
        val path = Path().apply {
            moveTo(screenCenterX.toFloat(), 80f)
            lineTo(screenCenterX.toFloat(), 0f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 30L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    // ──────────────────────────────────────────────
    //  Overlay lifecycle
    // ──────────────────────────────────────────────

    private fun createOverlay() {
        if (overlayView != null) return

        val view = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = overlayAlpha
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayHeightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0

            // Cover the display cutout / notch area
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

            // PRIVATE_FLAG_IS_SCREEN_DECOR (0x00400000) tells the WM this
            // window is a screen decoration — nothing (including status-bar
            // icons and heads-up notifications) will render above it.
            // Requires a rooted / privileged context to take effect.
            try {
                val field = WindowManager.LayoutParams::class.java
                    .getDeclaredField("privateFlags")
                field.isAccessible = true
                val currentFlags = field.getInt(this)
                field.setInt(this, currentFlags or PRIVATE_FLAG_IS_SCREEN_DECOR)
            } catch (e: Exception) {
                Log.w(TAG, "Could not set PRIVATE_FLAG_IS_SCREEN_DECOR", e)
            }
        }

        windowManager?.addView(view, params)
        overlayView = view
    }

    private fun removeOverlay() {
        overlayView?.let {
            windowManager?.removeView(it)
            overlayView = null
        }
    }

    /**
     * Updates the overlay height at runtime. Called when the user changes the
     * height slider in the dashboard.
     */
    fun updateOverlayHeight(heightPx: Int) {
        overlayHeightPx = heightPx
        overlayView?.let {
            val params = it.layoutParams as WindowManager.LayoutParams
            params.height = heightPx
            windowManager?.updateViewLayout(it, params)
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        overlayHeightPx = prefs.getInt(KEY_OVERLAY_HEIGHT, DEFAULT_OVERLAY_HEIGHT)
        val threshold = prefs.getFloat(KEY_THRESHOLD, RollSensorListener.DEFAULT_THRESHOLD)
        rollSensorListener?.setThreshold(threshold)
    }

    // ──────────────────────────────────────────────
    //  Sensor lifecycle
    // ──────────────────────────────────────────────

    private fun registerSensor() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val threshold = prefs.getFloat(KEY_THRESHOLD, RollSensorListener.DEFAULT_THRESHOLD)

        val listener = RollSensorListener(threshold) { alpha ->
            overlayAlpha = alpha
        }
        rollSensorListener = listener

        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        rotationSensor?.let {
            sensorManager?.registerListener(
                listener, it, SensorManager.SENSOR_DELAY_UI
            )
        }
    }

    private fun unregisterSensor() {
        rollSensorListener?.let { sensorManager?.unregisterListener(it) }
        rollSensorListener = null
    }

    /**
     * Updates the roll-threshold at runtime. Called when the user changes
     * the sensitivity slider in the dashboard.
     */
    fun updateThreshold(degrees: Float) {
        rollSensorListener?.setThreshold(degrees)
    }

    companion object {
        private const val TAG = "OverlayGuard"

        /**
         * PRIVATE_FLAG_IS_SCREEN_DECOR — marks the window as a screen
         * decoration so the WM renders it above all other layers including
         * the status bar and notification shade.
         * Value from AOSP WindowManager.LayoutParams (hidden API).
         */
        private const val PRIVATE_FLAG_IS_SCREEN_DECOR = 0x00400000

        const val PREFS_NAME = "overlay_guard_prefs"
        const val KEY_OVERLAY_HEIGHT = "overlay_height"
        const val KEY_THRESHOLD = "threshold_degrees"
        const val DEFAULT_OVERLAY_HEIGHT = 150

        /** Singleton reference so the UI can communicate with the running service. */
        var instance: PrivacyOverlayService? = null
            private set
    }

    init {
        // Not ideal — will be replaced with a proper binding/broadcast in a future milestone
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
