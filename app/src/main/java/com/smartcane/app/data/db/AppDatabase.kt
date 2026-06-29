package com.smartcane.app.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import com.smartcane.app.data.model.Contact
import com.smartcane.app.data.model.Reminder
import com.smartcane.app.data.model.TripHistory

@Database(
    entities = [Contact::class, TripHistory::class, Reminder::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun tripHistoryDao(): TripHistoryDao
    abstract fun reminderDao(): ReminderDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smartcane_db"
                )
                    .fallbackToDestructiveMigrationFrom(1) // ← replace deprecated call
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}