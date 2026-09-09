package com.ewaldmire.osmride.ride

import android.net.Uri
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.route.RouteThumbnailGenerator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng

/**
 * Imports a completed outdoor activity recorded by a bike computer/GPS watch/app (e.g. Garmin,
 * Wahoo, Strava export) from a .fit file - added to Profile's Activities feed the same way a live
 * indoor ride is, just with no associated in-app route (see RideHistoryRepository.importRide) and
 * whatever [com.ewaldmire.osmride.ride.ActivityType] the file's own `sport` field says it was
 * (not necessarily cycling).
 *
 * Shared by two entry points that need the exact same parse-GPX-save-thumbnail pipeline but
 * surface errors differently:
 * [ActivitiesViewModel][com.ewaldmire.osmride.ui.activities.ActivitiesViewModel]'s in-app
 * "Import" button (sets its own importError StateFlow, shown as a Snackbar already on screen) and
 * OsmRideNavHost's OS-Sharesheet/ACTION_SEND handling (MainActivity - launched fresh from outside
 * the app, so there's no ViewModel/Snackbar already in scope to report into; that caller shows a
 * Toast instead). Deliberately a plain suspend function, not tied to a ViewModel, so it works
 * from both contexts without fighting Compose Navigation's per-destination ViewModelStore
 * scoping.
 */
object FitFileImporter {
    /** Returns an error message on failure, or null on success. */
    suspend fun importFitFile(app: OsmRideApp, uri: Uri, displayName: String?): String? {
        val bytes = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: return "Could not open that file"

        val summary = withContext(Dispatchers.Default) { FitFileParser.parse(bytes) }
            ?: return "Could not read that .fit file"

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
        if (trackPoints.size < 2) return "That .fit file has no usable GPS track"

        val title = displayName?.substringBeforeLast(".")?.trim()?.takeIf { it.isNotEmpty() } ?: "Imported Ride"
        val gpxContent = GpxWriter.write(title, trackPoints)
        val record = app.rideHistoryRepository.importRide(
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
            activityType = summary.activityType,
        )
        generateThumbnail(app, record, trackPoints)
        return null
    }

    private suspend fun generateThumbnail(app: OsmRideApp, record: RideRecord, trackPoints: List<RecordedTrackPoint>) {
        val points = trackPoints.map { LatLng(it.lat, it.lon) }
        val fileName = "${record.id}_thumb.png"
        val destination = File(app.rideHistoryRepository.directory, fileName)
        if (RouteThumbnailGenerator.generate(app, points, destination)) {
            app.rideHistoryRepository.setThumbnail(record.id, fileName)
        }
    }
}
