package com.smartcane.app.data.repository

import com.smartcane.app.data.db.TripHistoryDao
import com.smartcane.app.data.model.TripHistory
import kotlinx.coroutines.flow.Flow

class TripHistoryRepository(private val tripHistoryDao: TripHistoryDao) {

    val allTrips: Flow<List<TripHistory>> = tripHistoryDao.getAllTrips()

    suspend fun getRecentTrips(): List<TripHistory> =
        tripHistoryDao.getRecentTrips()

    suspend fun saveTrip(destination: String, durationMinutes: Int = 0) {
        tripHistoryDao.insertTrip(
            TripHistory(
                destination = destination,
                timestamp = System.currentTimeMillis(),
                durationMinutes = durationMinutes
            )
        )
    }

    suspend fun deleteTrip(trip: TripHistory) {
        tripHistoryDao.deleteTrip(trip)
    }

    suspend fun clearAllTrips() {
        tripHistoryDao.deleteAllTrips()
    }
}