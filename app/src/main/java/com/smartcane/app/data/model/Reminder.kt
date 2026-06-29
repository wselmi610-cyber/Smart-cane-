package com.smartcane.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val task: String,        // ← make sure it says task
    val hour: Int,
    val minute: Int,
    val isActive: Boolean = true,
    val isDone: Boolean = false,
    val repeatAfterMinutes: Int = 15
)