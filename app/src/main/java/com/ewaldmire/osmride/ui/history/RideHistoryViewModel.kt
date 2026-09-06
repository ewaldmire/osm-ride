package com.ewaldmire.osmride.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.ride.RideRecord
import java.io.File
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RideHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as OsmRideApp).rideHistoryRepository
    private val routeRepository = (application as OsmRideApp).routeRepository

    val rides: StateFlow<List<RideRecord>> = repository.rides

    fun gpxFile(record: RideRecord): File = repository.gpxFile(record)

    /** Reuses the route's own thumbnail rather than generating a separate one for history - null
     * when the ride predates this field, or its route was since deleted. */
    fun thumbnailFile(record: RideRecord): File? =
        record.routeId?.let { routeRepository.getRouteSummary(it) }?.let { routeRepository.thumbnailFile(it) }

    fun deleteRide(id: String) {
        viewModelScope.launch { repository.deleteRide(id) }
    }

    fun updateRide(id: String, title: String, notes: String) {
        viewModelScope.launch { repository.updateRide(id, title, notes) }
    }
}
