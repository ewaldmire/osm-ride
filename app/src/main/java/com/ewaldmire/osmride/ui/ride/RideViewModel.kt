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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    private var tickerJob: Job? = null

    fun loadRoute(routeId: String) {
        if (loadedRouteId == routeId) return
        loadedRouteId = routeId
        viewModelScope.launch {
            val loaded = routeRepository.loadRoute(routeId) ?: return@launch
            _route.value = loaded
            val newEngine = RideEngine(loaded)
            engine = newEngine
            app.currentRideEngine = newEngine
            _stats.value = newEngine.stats.value

            viewModelScope.launch { newEngine.stats.collect { _stats.value = it } }
            viewModelScope.launch { trainerManager.samples.collect { newEngine.onTrainerSample(it) } }
            viewModelScope.launch { hrManager.samples.collect { newEngine.onHeartRateSample(it) } }
        }
    }

    fun start() {
        engine?.start()
        if (tickerJob == null) {
            tickerJob = viewModelScope.launch {
                while (isActive) {
                    delay(1000)
                    engine?.onClockTick()
                }
            }
        }
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, Intent(context, RideForegroundService::class.java))
    }

    fun pause() {
        engine?.pause()
    }

    fun finishManually() {
        engine?.finishManually()
    }

    override fun onCleared() {
        super.onCleared()
        tickerJob?.cancel()
        val context = getApplication<Application>()
        context.stopService(Intent(context, RideForegroundService::class.java))
    }
}
