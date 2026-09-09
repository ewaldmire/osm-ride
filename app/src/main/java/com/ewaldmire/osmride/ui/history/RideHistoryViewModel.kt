package com.ewaldmire.osmride.ui.history

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.ride.FitFileParser
import com.ewaldmire.osmride.ride.GpxWriter
import com.ewaldmire.osmride.ride.RecordedTrackPoint
import com.ewaldmire.osmride.ride.RideRecord
import com.ewaldmire.osmride.route.RouteThumbnailGenerator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng

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

    /** Imports a completed outdoor ride recorded by a bike computer/GPS watch/app (e.g. Garmin,
     * Wahoo, Strava export) as a .fit file - added to history the same way a live indoor ride is,
     * just with no associated in-app route (see RideHistoryRepository.importRide). */
    fun importFitFile(uri: Uri, displayName: String?) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (bytes == null) {
                _importError.value = "Could not open that file"
                return@launch
            }
            val summary = withContext(Dispatchers.Default) { FitFileParser.parse(bytes) }
            if (summary == null) {
                _importError.value = "Could not read that .fit file"
                return@launch
            }

            val trackPoints = summary.points.mapNotNull { p ->
                val lat = p.lat ?: return@mapNotNull null
                val lon = p.lon ?: return@mapNotNull null
                RecordedTrackPoint(
                    timestampMillis = p.timestampMillis,
                    lat = lat,
                    lon = lon,
                    elevationMeters = p.elevationMeters,
                    heartRateBpm = p.heartRateBpm,
                    cadenceRpm = p.cadenceRpm,
                )
            }
            if (trackPoints.size < 2) {
                _importError.value = "That .fit file has no usable GPS track"
                return@launch
            }

            val title = displayName?.substringBeforeLast(".")?.trim()?.takeIf { it.isNotEmpty() } ?: "Imported Ride"
            val gpxContent = GpxWriter.write(title, trackPoints)
            val record = repository.importRide(
                title = title,
                completedAtEpochMillis = summary.endEpochMillis,
                distanceMeters = summary.distanceMeters,
                durationSeconds = summary.durationSeconds,
                avgSpeedMps = summary.avgSpeedMps,
                avgPowerWatts = summary.avgPowerWatts,
                avgCadenceRpm = summary.avgCadenceRpm,
                avgHeartRateBpm = summary.avgHeartRateBpm,
                estimatedKilocalories = summary.totalCalories,
                gpxContent = gpxContent,
            )
            generateThumbnail(record, trackPoints)
        }
    }

    private fun generateThumbnail(record: RideRecord, trackPoints: List<RecordedTrackPoint>) {
        viewModelScope.launch {
            val points = trackPoints.map { LatLng(it.lat, it.lon) }
            val fileName = "${record.id}_thumb.png"
            val destination = File(repository.directory, fileName)
            if (RouteThumbnailGenerator.generate(app, points, destination)) {
                repository.setThumbnail(record.id, fileName)
            }
        }
    }

    fun clearImportError() {
        _importError.value = null
    }
}
