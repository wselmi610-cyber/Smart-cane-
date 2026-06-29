package com.smartcane.app.managers

import android.content.Context
import android.view.accessibility.AccessibilityManager

class TalkBackDetector(private val context: Context) {

    private val accessibilityManager =
        context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as AccessibilityManager

    fun isTalkBackActive(): Boolean {
        if (!accessibilityManager.isEnabled) return false

        // Only return true for ACTUAL TalkBack — not our own service
        val enabledServices = accessibilityManager
            .getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN
            )

        return enabledServices.any { service ->
            val id = service.resolveInfo.serviceInfo.packageName
            // Only Google TalkBack or Samsung TalkBack count
            // NOT our own CaneAccessibilityService
            (id == "com.google.android.marvin.talkback" ||
                    id == "com.samsung.accessibility" ||
                    id == "com.samsung.android.accessibility.talkback") &&
                    id != context.packageName  // Never count our own app
        }
    }
}