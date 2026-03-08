package com.moclg.overlayguard.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Stub AccessibilityService — overlay logic added in Milestone 2.
 */
class PrivacyOverlayService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: we don't inspect window content
    }

    override fun onInterrupt() {
        // No-op
    }
}
