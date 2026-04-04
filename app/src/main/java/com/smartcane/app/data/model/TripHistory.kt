package com.smartcane.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trip_history")
data class TripHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val destination: String,
    val timestamp: Long,       // System.currentTimeMillis()
    val durationMinutes: Int   // 0 if unknown
)