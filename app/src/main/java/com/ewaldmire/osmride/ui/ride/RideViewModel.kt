package com.ewaldmire.osmride.ui.ride

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.ble.BleConnectionState
import com.ewaldmire.osmride.ride.RideEngine
import com.ewaldmire.osmride.ride.RideForegroundService
import com.ewaldmire.osmride.ride.RideStats
import com.ewaldmire.osmride.route.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Thin, reattachable observer of the active [RideEngine] - [RideForegroundService] is what
 * actually drives it (BLE samples + clock tick), so this ViewModel being torn down and recreated
 * by navigation (e.g. the user backs out to fix a Bluetooth connection, then returns) doesn't
 * lose or restart the ride: [loadRoute] reattaches to the existing engine for the same route
 * instead of creating a new one.
 */
class RideViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OsmRideApp
    private val routeRepository = app.routeRepository
    private val trainerManager = app.trainerBleManager
    private val hrManager = app.heartRateBleManager

    private val _route = MutableStateFlow<Route?>(null)
    val route: StateFlow<Route?> = _route.asStateFlow()

    private val _stats = MutableStateFlow(RideStats())
    val stats: StateFlow<RideStats> = _stats.asStateFlow()

    val trainerConnectionState: StateFlow<BleConnectionState> = trainerManager.connectionState
    val heartRateConnectionState: StateFlow<BleConnectionState> = hrManager.connectionState

    private var engine: RideEngine? = null
    private var loadedRouteId: String? = null

    fun loadRoute(routeId: String) {
        if (loadedRouteId == routeId) return
        loadedRouteId = routeId
        viewModelScope.launch {
            val existing = app.currentRideEngine
            val activeEngine = if (existing != null && existing.route.id == routeId) {
                // Ride already in progress for this route - reattach instead of restarting.
                // The service is idempotent-safe to (re)start: it only subscribes its drive
                // loop once per service lifetime, so this just confirms it's still running.
                val context = getApplication<Application>()
                ContextCompat.startForegroundService(context, Intent(context, RideForegroundService::class.java))
                existing
            } else {
                val loaded = routeRepository.loadRoute(routeId) ?: return@launch
                val newEngine = RideEngine(loaded)
                app.currentRideEngine = newEngine
                newEngine
            }

            engine = activeEngine
            _route.value = activeEngine.route
            _stats.value = activeEngine.stats.value
            viewModelScope.launch { activeEngine.stats.collect { _stats.value = it } }
        }
    }

    fun start() {
        engine?.start()
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, Intent(context, RideForegroundService::class.java))
    }

    fun pause() {
        engine?.pause()
    }

    fun finishManually() {
        engine?.finishManually()
    }
}
