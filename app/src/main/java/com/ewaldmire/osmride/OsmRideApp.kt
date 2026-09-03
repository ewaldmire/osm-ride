package com.ewaldmire.osmride

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.ewaldmire.osmride.ble.HeartRateBleManager
import com.ewaldmire.osmride.ble.TrainerBleManager
import com.ewaldmire.osmride.ride.RideEngine
import com.ewaldmire.osmride.ride.RideForegroundService
import com.ewaldmire.osmride.ride.RideHistoryRepository
import com.ewaldmire.osmride.route.RouteRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.MapLibre

/**
 * Hand-rolled singleton container (no DI framework needed at this scope). The BLE managers and
 * route repository live for the app's whole process lifetime. [currentRideEngine] is created
 * fresh per ride; [RideForegroundService] alone drives it (BLE samples + clock tick) so the ride
 * keeps progressing regardless of which screen is showing or whether the screen is off.
 * [com.ewaldmire.osmride.ui.ride.RideViewModel] only observes it, and reattaches to the existing
 * engine instead of creating a new one if the user navigates away mid-ride and back.
 */
class OsmRideApp : Application() {

    val trainerBleManager: TrainerBleManager by lazy { TrainerBleManager(this) }
    val heartRateBleManager: HeartRateBleManager by lazy { HeartRateBleManager(this) }
    val routeRepository: RouteRepository by lazy { RouteRepository(this) }
    val rideHistoryRepository: RideHistoryRepository by lazy { RideHistoryRepository(this) }

    private val _currentRideEngine = MutableStateFlow<RideEngine?>(null)
    val currentRideEngineFlow: StateFlow<RideEngine?> = _currentRideEngine.asStateFlow()
    var currentRideEngine: RideEngine?
        get() = _currentRideEngine.value
        set(value) {
            _currentRideEngine.value = value
        }

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
