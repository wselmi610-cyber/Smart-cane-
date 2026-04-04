package com.smartcane.app.managers

import android.content.Context
import android.view.accessibility.AccessibilityManager

class TalkBackDetector(private val context: Context) {

    private val accessibilityManager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    fun isTalkBackActive(): Boolean {
        if (!accessibilityManager.isEnabled) return false
        return accessibilityManager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN
        ).isNotEmpty()
    }
}