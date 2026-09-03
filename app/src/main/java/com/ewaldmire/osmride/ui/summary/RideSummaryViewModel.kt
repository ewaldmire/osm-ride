package com.ewaldmire.osmride.ui.summary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.ride.GpxWriter
import com.ewaldmire.osmride.ride.RideRecord
import com.ewaldmire.osmride.ride.RideStats
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RideSummaryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OsmRideApp
    private val engine = app.currentRideEngine
    private val historyRepository = app.rideHistoryRepository

    val stats: RideStats = engine?.stats?.value ?: RideStats()
    val routeName: String = engine?.route?.name ?: "Ride"
    val hasTrackPoints: Boolean = (engine?.trackPointsSnapshot()?.size ?: 0) >= 2

    private val _savedRecord = MutableStateFlow<RideRecord?>(null)
    val savedRecord: StateFlow<RideRecord?> = _savedRecord.asStateFlow()

    init {
        // Rides are saved to history as soon as they're completed, independent of whether the
        // user chooses to export/share them - matches how Strava/Garmin etc. treat a finished
        // activity as saved by default.
        val e = engine
        if (e != null && hasTrackPoints) {
            viewModelScope.launch {
                val gpxContent = GpxWriter.write(routeName, e.trackPointsSnapshot())
                _savedRecord.value = historyRepository.saveRide(routeName, stats, gpxContent)
            }
        }
    }

    fun gpxFileToShare(): File? = savedRecord.value?.let { historyRepository.gpxFile(it) }

    fun clearActiveRideEngine() {
        app.currentRideEngine = null
    }
}
