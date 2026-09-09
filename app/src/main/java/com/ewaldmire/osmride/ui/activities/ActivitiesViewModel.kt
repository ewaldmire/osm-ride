package com.ewaldmire.osmride.ui.activities

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.ride.FitFileImporter
import com.ewaldmire.osmride.ride.RideRecord
import com.ewaldmire.osmride.strength.StrengthWorkoutEntry
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ActivitiesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OsmRideApp
    private val rideRepository = app.rideHistoryRepository
    private val strengthRepository = app.strengthWorkoutRepository

    /** Newest first within each; merge/sort into one feed happens in the screen (see
     * [mergeActivities]) so this stays simple StateFlow passthrough. */
    val rides: StateFlow<List<RideRecord>> = rideRepository.rides
    val strengthEntries: StateFlow<List<StrengthWorkoutEntry>> = strengthRepository.entries

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    fun gpxFile(record: RideRecord): File = rideRepository.gpxFile(record)

    /** This ride's own recorded track, not the route's planning thumbnail - null when the ride
     * predates this field, or generation hasn't finished yet. */
    fun thumbnailFile(record: RideRecord): File? = rideRepository.thumbnailFile(record)

    fun deleteRide(id: String) {
        viewModelScope.launch { rideRepository.deleteRide(id) }
    }

    fun updateRide(id: String, title: String, notes: String) {
        viewModelScope.launch { rideRepository.updateRide(id, title, notes) }
    }

    fun deleteStrengthEntry(id: String) {
        viewModelScope.launch { strengthRepository.deleteEntry(id) }
    }

    /** Imports a completed outdoor activity from a .fit file, chosen via the in-app file picker
     * (the OS Sharesheet path goes through OsmRideNavHost/MainActivity instead, since it isn't
     * tied to this screen already being open; both funnel into the same FitFileImporter). */
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
