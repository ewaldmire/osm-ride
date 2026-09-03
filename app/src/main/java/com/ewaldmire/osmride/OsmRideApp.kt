package com.ewaldmire.osmride

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.ewaldmire.osmride.ble.HeartRateBleManager
import com.ewaldmire.osmride.ble.TrainerBleManager
import com.ewaldmire.osmride.ride.RideEngine
import com.ewaldmire.osmride.ride.RideForegroundService
import com.ewaldmire.osmride.route.RouteRepository
import org.maplibre.android.MapLibre

/**
 * Hand-rolled singleton container (no DI framework needed at this scope). The BLE managers and
 * route repository live for the app's whole process lifetime; [currentRideEngine] is created
 * fresh per ride and shared between [com.ewaldmire.osmride.ui.ride.RideViewModel] and
 * [RideForegroundService] so the ride keeps progressing while the screen is off.
 */
class OsmRideApp : Application() {

    val trainerBleManager: TrainerBleManager by lazy { TrainerBleManager(this) }
    val heartRateBleManager: HeartRateBleManager by lazy { HeartRateBleManager(this) }
    val routeRepository: RouteRepository by lazy { RouteRepository(this) }

    var currentRideEngine: RideEngine? = null

    override fun onCreate() {
        super.onCreate()

        MapLibre.getInstance(this)

        val channel = NotificationChannel(
            RideForegroundService.CHANNEL_ID,
            getString(R.string.ride_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
