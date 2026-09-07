package com.ewaldmire.osmride.ui.summary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.ride.GpxWriter
import com.ewaldmire.osmride.ride.RecordedTrackPoint
import com.ewaldmire.osmride.ride.RideRecord
import com.ewaldmire.osmride.ride.RideStats
import com.ewaldmire.osmride.route.RouteThumbnailGenerator
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

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
            val trackPoints = e.trackPointsSnapshot()
            viewModelScope.launch {
                val gpxContent = GpxWriter.write(routeName, trackPoints)
                val record = historyRepository.saveRide(routeName, e.route.id, stats, gpxContent)
                _savedRecord.value = record
                generateThumbnail(record, trackPoints)
            }
        }
    }

    fun gpxFileToShare(): File? = savedRecord.value?.let { historyRepository.gpxFile(it) }

    // A snapshot of this ride's own recorded track, not the route's planning thumbnail - those
    // can differ if the rider stopped early or deviated. Recomputed on every call (like
    // RideHistoryViewModel's) rather than cached, since it changes once generateThumbnail finishes.
    fun thumbnailFile(record: RideRecord): File? = historyRepository.thumbnailFile(record)

    private fun generateThumbnail(record: RideRecord, trackPoints: List<RecordedTrackPoint>) {
        viewModelScope.launch {
            val points = trackPoints.map { LatLng(it.lat, it.lon) }
            val fileName = "${record.id}_thumb.png"
            val destination = File(historyRepository.directory, fileName)
            if (RouteThumbnailGenerator.generate(app, points, destination)) {
                historyRepository.setThumbnail(record.id, fileName)
                _savedRecord.value = _savedRecord.value?.copy(thumbnailFileName = fileName)
            }
        }
    }

    fun saveTitleAndNotes(title: String, notes: String) {
        val id = savedRecord.value?.id ?: return
        val resolvedTitle = title.ifBlank { routeName }
        viewModelScope.launch {
            historyRepository.updateRide(id, resolvedTitle, notes)
            _savedRecord.value = _savedRecord.value?.copy(title = resolvedTitle, notes = notes)
        }
    }

    fun clearActiveRideEngine() {
        app.currentRideEngine = null
    }
}
