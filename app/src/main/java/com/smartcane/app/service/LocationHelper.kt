package com.smartcane.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class LocationHelper(private val context: Context) {

    @SuppressLint("MissingPermission")
    suspend fun getLastKnownLocation(): Location? {
        return suspendCancellableCoroutine { continuation ->
            val fusedClient = LocationServices
                .getFusedLocationProviderClient(context)

            fusedClient.lastLocation
                .addOnSuccessListener { location ->
                    continuation.resume(location)
                }
                .addOnFailureListener {
                    // Fallback to GPS provider
                    val locationManager = context
                        .getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val fallback = locationManager
                        .getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    continuation.resume(fallback)
                }
        }
    }
}