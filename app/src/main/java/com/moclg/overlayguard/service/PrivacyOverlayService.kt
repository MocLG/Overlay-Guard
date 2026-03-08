package com.moclg.overlayguard.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.moclg.overlayguard.sensor.RollSensorListener

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

    /** Height of the overlay in pixels — adjustable via SharedPreferences. */
    var overlayHeightPx: Int = DEFAULT_OVERLAY_HEIGHT

    /** Current alpha of the overlay (0.0 = hidden, 1.0 = visible). */
    var overlayAlpha: Float = 0f
        set(value) {
            field = value
            overlayView?.alpha = value
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        loadPreferences()
        createOverlay()
        registerSensor()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: we don't inspect window content
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        unregisterSensor()
        removeOverlay()
        super.onDestroy()
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
