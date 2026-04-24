package com.smartcane.app.managers

import android.content.Context

class AppStateManager(context: Context) {

    private val prefs = context.getSharedPreferences(
        "smartcane_state", Context.MODE_PRIVATE
    )

    // ── Persistent (survives app restart) ────────────────────────────
    companion object {
        private const val KEY_TOTAL_OPENS = "total_app_opens"
    }

    fun getTotalOpens(): Int = prefs.getInt(KEY_TOTAL_OPENS, 0)

    fun incrementTotalOpens() {
        prefs.edit().putInt(KEY_TOTAL_OPENS, getTotalOpens() + 1).apply()
    }

    fun isFirstEverOpen(): Boolean = getTotalOpens() == 0

    // ── Session counters (reset every app launch) ─────────────────────
    // These live in memory only — gone when app closes
    private val sessionVisits = mutableMapOf<String, Int>()

    fun getVisitCount(screen: String): Int =
        sessionVisits.getOrDefault(screen, 0)

    fun recordVisit(screen: String) {
        sessionVisits[screen] = getVisitCount(screen) + 1
    }

    // Convenience screen name constants
    object Screen {
        const val HOME = "home"
        const val NAVIGATION = "navigation"
        const val FAMILY = "family"
        const val HISTORY = "history"
        const val BATTERY = "battery"
    }
}