package com.carex.poc

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log

/**
 * Data class representing live GPS location fix information.
 */
data class GpsData(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val accuracy: Float = 0f,
    val speed: Float = 0f,
    val hasFix: Boolean = false,
    val provider: String = "None",
    val timestamp: Long = 0L
)

/**
 * Pure-Kotlin location manager wrapper using Android framework LocationManager.
 */
class GpsLocationManager {

    companion object {
        private const val TAG = "GpsLocationManager"
        private const val MIN_TIME_MS = 2000L // update every 2 seconds
        private const val MIN_DISTANCE_M = 1f  // or 1 meter change
    }

    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null

    /**
     * Start listening for GPS and Network location updates.
     */
    @SuppressLint("MissingPermission")
    fun startListening(context: Context, onLocationUpdate: (GpsData) -> Unit) {
        stopListening()

        try {
            val mgr = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (mgr == null) {
                Log.w(TAG, "LocationManager unavailable")
                return
            }
            locationManager = mgr

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val gpsData = GpsData(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        altitude = location.altitude,
                        accuracy = location.accuracy,
                        speed = location.speed,
                        hasFix = true,
                        provider = location.provider ?: "GPS",
                        timestamp = location.time
                    )
                    onLocationUpdate(gpsData)
                }

                @Deprecated("Deprecated in API 29")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            locationListener = listener

            // Request updates from GPS_PROVIDER if enabled
            if (mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mgr.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    listener
                )
            }

            // Request updates from NETWORK_PROVIDER if enabled
            if (mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mgr.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    listener
                )
            }

            // Also check for last known location to give instant fix
            val lastGps = mgr.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = mgr.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val bestLast = when {
                lastGps != null && lastNet != null -> if (lastGps.time > lastNet.time) lastGps else lastNet
                lastGps != null -> lastGps
                else -> lastNet
            }

            bestLast?.let { loc ->
                onLocationUpdate(
                    GpsData(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        altitude = loc.altitude,
                        accuracy = loc.accuracy,
                        speed = loc.speed,
                        hasFix = true,
                        provider = loc.provider ?: "LastKnown",
                        timestamp = loc.time
                    )
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start location listening: ${e.message}", e)
        }
    }

    /**
     * Stop location updates.
     */
    fun stopListening() {
        try {
            locationListener?.let { listener ->
                locationManager?.removeUpdates(listener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping location updates: ${e.message}", e)
        } finally {
            locationListener = null
            locationManager = null
        }
    }
}
