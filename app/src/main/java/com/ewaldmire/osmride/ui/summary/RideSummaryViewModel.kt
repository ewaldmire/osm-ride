package com.ewaldmire.osmride.ui.summary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.ride.GpxWriter
import com.ewaldmire.osmride.ride.RideStats
import java.io.File

class RideSummaryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OsmRideApp
    private val engine = app.currentRideEngine

    val stats: RideStats = engine?.stats?.value ?: RideStats()
    val routeName: String = engine?.route?.name ?: "Ride"
    val hasTrackPoints: Boolean = (engine?.trackPointsSnapshot()?.size ?: 0) >= 2

    /** Writes the ride's GPX to the app cache dir (for FileProvider sharing) and returns it. */
    fun writeGpxFile(): File? {
        val e = engine ?: return null
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "osmride_${System.currentTimeMillis()}.gpx")
        file.writeText(GpxWriter.write(routeName, e.trackPointsSnapshot()))
        return file
    }

    fun discard() {
        app.currentRideEngine = null
    }
}
