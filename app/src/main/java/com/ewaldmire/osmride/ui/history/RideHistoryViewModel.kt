package com.ewaldmire.osmride.ui.history

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.ride.FitFileImporter
import com.ewaldmire.osmride.ride.RideRecord
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RideHistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OsmRideApp
    private val repository = app.rideHistoryRepository

    val rides: StateFlow<List<RideRecord>> = repository.rides

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    fun gpxFile(record: RideRecord): File = repository.gpxFile(record)

    /** This ride's own recorded track, not the route's planning thumbnail - null when the ride
     * predates this field, or generation hasn't finished yet (see RideSummaryViewModel). */
    fun thumbnailFile(record: RideRecord): File? = repository.thumbnailFile(record)

    fun deleteRide(id: String) {
        viewModelScope.launch { repository.deleteRide(id) }
    }

    fun updateRide(id: String, title: String, notes: String) {
        viewModelScope.launch { repository.updateRide(id, title, notes) }
    }

    /** Imports a completed outdoor ride from a .fit file, chosen via the in-app file picker (the
     * OS Sharesheet path - "Share" from another app straight into OSM Ride - goes through
     * OsmRideNavHost/MainActivity instead, since it isn't tied to this screen already being
     * open; both funnel into the same FitFileImporter). */
    fun importFitFile(uri: Uri, displayName: String?) {
        viewModelScope.launch {
            val error = FitFileImporter.importFitFile(app, uri, displayName)
            if (error != null) _importError.value = error
        }
    }

    fun clearImportError() {
        _importError.value = null
    }
}
