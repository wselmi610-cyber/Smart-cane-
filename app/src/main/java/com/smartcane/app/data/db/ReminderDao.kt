package com.smartcane.app.data.db

import androidx.room.*
import com.smartcane.app.data.model.Reminder
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: Reminder): Long

    @Update
    suspend fun update(reminder: Reminder)

    @Delete
    suspend fun delete(reminder: Reminder)

    @Query("SELECT * FROM reminders WHERE isActive = 1 ORDER BY hour, minute")
    fun getAllActive(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE isActive = 1")
    suspend fun getAllActiveSync(): List<Reminder>

    @Query("UPDATE reminders SET isDone = 1, isActive = 0 WHERE id = :id")
    suspend fun markDone(id: Int)

    @Query("UPDATE reminders SET isActive = 0 WHERE id = :id")
    suspend fun deactivate(id: Int)
}