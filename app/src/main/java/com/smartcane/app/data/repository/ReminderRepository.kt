package com.smartcane.app.data.repository

import com.smartcane.app.data.db.ReminderDao
import com.smartcane.app.data.model.Reminder
import kotlinx.coroutines.flow.Flow

class ReminderRepository(private val reminderDao: ReminderDao) {

    fun getAllActive(): Flow<List<Reminder>> = reminderDao.getAllActive()

    suspend fun getAllActiveSync(): List<Reminder> = reminderDao.getAllActiveSync()

    suspend fun insert(reminder: Reminder): Long = reminderDao.insert(reminder)

    suspend fun update(reminder: Reminder) = reminderDao.update(reminder)

    suspend fun delete(reminder: Reminder) = reminderDao.delete(reminder)

    suspend fun markDone(id: Int) = reminderDao.markDone(id)

    suspend fun deactivate(id: Int) = reminderDao.deactivate(id)
}
