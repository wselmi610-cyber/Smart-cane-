package com.smartcane.app.data.db

import androidx.room.*
import com.smartcane.app.data.model.TripHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface TripHistoryDao {

    @Query("SELECT * FROM trip_history ORDER BY timestamp DESC")
    fun getAllTrips(): Flow<List<TripHistory>>

    @Query("SELECT * FROM trip_history ORDER BY timestamp DESC LIMIT 10")
    suspend fun getRecentTrips(): List<TripHistory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripHistory)

    @Delete
    suspend fun deleteTrip(trip: TripHistory)

    @Query("DELETE FROM trip_history")
    suspend fun deleteAllTrips()
}