package com.ewaldmire.osmride.ui.routes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.ride.RideEngine
import com.ewaldmire.osmride.route.RouteSummary
import com.ewaldmire.osmride.route.RouteThumbnailGenerator
import com.ewaldmire.osmride.route.WaypointSimplifier
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RoutesListViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OsmRideApp
    private val repository = app.routeRepository

    val routes: StateFlow<List<RouteSummary>> = repository.routes

    /** Non-null while a ride is in progress (or just finished but not yet saved), for a
     * "resume ride" banner and to stop the user from starting a second concurrent ride. */
    val activeRideEngine: StateFlow<RideEngine?> = app.currentRideEngineFlow

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    fun importGpx(uri: Uri, displayNameHint: String?) {
        viewModelScope.launch {
            repository.importGpx(uri, displayNameHint)
                .onSuccess { summary -> generateThumbnail(summary) }
                .onFailure { _importError.value = it.message ?: "Could not import that GPX file" }
        }
    }

    private fun generateThumbnail(summary: RouteSummary) {
        viewModelScope.launch {
            val route = repository.loadRoute(summary.id) ?: return@launch
            val fileName = "${summary.id}_thumb.png"
            val destination = File(repository.directory, fileName)
            if (RouteThumbnailGenerator.generate(app, route, destination)) {
                repository.setThumbnail(summary.id, fileName)
            }
        }
    }

    fun clearImportError() {
        _importError.value = null
    }

    fun deleteRoute(id: String) {
        viewModelScope.launch { repository.deleteRoute(id) }
    }

    /**
     * Ensures [routeId] has a real waypoint list before opening the route creator - lazily
     * derives one from the route's dense track if it's a plain GPX import that's never been
     * opened for editing before (see WaypointSimplifier), then persists it so this only runs
     * once per route. Calls [onReady] with whether a derivation just happened, so the caller can
     * show a one-time "this may adjust the route" notice.
     */
    fun prepareEdit(routeId: String, onReady: (showDerivedHint: Boolean) -> Unit) {
        viewModelScope.launch {
            val summary = repository.getRouteSummary(routeId)
            var derivedNow = false
            if (summary != null && summary.waypoints == null) {
                val route = repository.loadRoute(routeId)
                if (route != null && route.points.size >= 2) {
                    repository.setWaypoints(routeId, WaypointSimplifier.deriveWaypoints(route.points))
                    derivedNow = true
                }
            }
            onReady(derivedNow)
        }
    }

    fun thumbnailFile(summary: RouteSummary): File? = repository.thumbnailFile(summary)

    fun routeFile(summary: RouteSummary): File = repository.routeFile(summary)
}
