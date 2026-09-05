package com.ewaldmire.osmride.ui.routecreator

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.route.BRouterClient
import com.ewaldmire.osmride.route.GpxParser
import com.ewaldmire.osmride.route.ParsedGpx
import com.ewaldmire.osmride.route.RouteSummary
import com.ewaldmire.osmride.route.RouteThumbnailGenerator
import com.ewaldmire.osmride.route.RouteWaypoint
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the route creator: tapped waypoints are re-routed onto roads via [BRouterClient] after
 * every add/undo/clear, and [save] persists the resulting GPX (only possible once routing has
 * succeeded at least once for the current waypoints).
 */
class RouteCreatorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OsmRideApp
    private val routeRepository = app.routeRepository

    private var existingId: String? = null

    private val _name = MutableStateFlow("New Route")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _waypoints = MutableStateFlow<List<RouteWaypoint>>(emptyList())
    val waypoints: StateFlow<List<RouteWaypoint>> = _waypoints.asStateFlow()

    private val _previewGpx = MutableStateFlow<ParsedGpx?>(null)
    val previewGpx: StateFlow<ParsedGpx?> = _previewGpx.asStateFlow()

    private var rawGpxText: String? = null

    private val _isRouting = MutableStateFlow(false)
    val isRouting: StateFlow<Boolean> = _isRouting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _saved = MutableStateFlow<String?>(null)
    val saved: StateFlow<String?> = _saved.asStateFlow()

    fun loadForEdit(routeId: String) {
        if (existingId == routeId) return
        existingId = routeId
        val summary = routeRepository.getRouteSummary(routeId) ?: return
        _name.value = summary.name
        val loadedWaypoints = summary.waypoints ?: emptyList()
        _waypoints.value = loadedWaypoints
        if (loadedWaypoints.size >= 2) routeCurrentWaypoints()
    }

    fun updateName(newName: String) {
        _name.value = newName
    }

    fun addWaypoint(lat: Double, lon: Double) {
        _waypoints.value = _waypoints.value + RouteWaypoint(lat, lon)
        routeCurrentWaypoints()
    }

    fun undoLastWaypoint() {
        if (_waypoints.value.isEmpty()) return
        _waypoints.value = _waypoints.value.dropLast(1)
        routeCurrentWaypoints()
    }

    fun clearWaypoints() {
        _waypoints.value = emptyList()
        _previewGpx.value = null
        rawGpxText = null
    }

    private fun routeCurrentWaypoints() {
        val current = _waypoints.value
        if (current.size < 2) {
            _previewGpx.value = null
            rawGpxText = null
            return
        }
        viewModelScope.launch {
            _isRouting.value = true
            _error.value = null
            BRouterClient.route(current)
                .onSuccess { gpxText ->
                    rawGpxText = gpxText
                    _previewGpx.value = withContext(Dispatchers.Default) {
                        gpxText.byteInputStream().use { GpxParser.parse(it) }
                    }
                }
                .onFailure {
                    rawGpxText = null
                    _previewGpx.value = null
                    _error.value = "Couldn't route those waypoints: ${it.message}"
                }
            _isRouting.value = false
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun save() {
        val gpxText = rawGpxText ?: return
        viewModelScope.launch {
            routeRepository.saveCreatedRoute(existingId, _name.value, gpxText, _waypoints.value)
                .onSuccess { summary ->
                    _saved.value = summary.id
                    generateThumbnail(summary)
                }
                .onFailure { _error.value = "Couldn't save route: ${it.message}" }
        }
    }

    private fun generateThumbnail(summary: RouteSummary) {
        // Re-editing a route's waypoints changes its shape, so its cached thumbnail (if any) is
        // now stale - regenerate unconditionally rather than only for brand-new routes.
        viewModelScope.launch {
            val route = routeRepository.loadRoute(summary.id) ?: return@launch
            val fileName = "${summary.id}_thumb.png"
            val destination = File(routeRepository.directory, fileName)
            if (RouteThumbnailGenerator.generate(app, route, destination)) {
                routeRepository.setThumbnail(summary.id, fileName)
            }
        }
    }
}
