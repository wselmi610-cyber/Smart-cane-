package com.smartcane.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class CaneAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CaneAccessibility"
        var instance: CaneAccessibilityService? = null

        fun openApp(intent: Intent): Boolean {
            return try {
                instance?.startActivity(intent)
                Log.d(TAG, "✅ App opened via AccessibilityService")
                true
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed: ${e.message}")
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "✅ AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}