package com.ewaldmire.osmride.ui.routecreator

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.route.BRouterClient
import com.ewaldmire.osmride.route.GpxParser
import com.ewaldmire.osmride.route.ParsedGpx
import com.ewaldmire.osmride.route.RoutePoint
import com.ewaldmire.osmride.route.RouteSummary
import com.ewaldmire.osmride.route.RouteThumbnailGenerator
import com.ewaldmire.osmride.route.RouteWaypoint
import com.ewaldmire.osmride.util.Haversine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng

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

    private val _hint = MutableStateFlow<String?>(null)
    val hint: StateFlow<String?> = _hint.asStateFlow()

    private val _selectedWaypointIndex = MutableStateFlow<Int?>(null)
    val selectedWaypointIndex: StateFlow<Int?> = _selectedWaypointIndex.asStateFlow()

    // Segment i = the stretch of route between waypoints[i] and waypoints[i + 1].
    private val _selectedSegmentIndex = MutableStateFlow<Int?>(null)
    val selectedSegmentIndex: StateFlow<Int?> = _selectedSegmentIndex.asStateFlow()

    // waypointAnchorIndices[i] = the index into previewGpx.points closest to waypoints[i].
    // Recomputed once per successful reroute; shared by selectNearestSegment's hit-testing and
    // by the map's segment-highlight rendering so the nearest-point search isn't done twice.
    private val _waypointAnchorIndices = MutableStateFlow<List<Int>>(emptyList())
    val waypointAnchorIndices: StateFlow<List<Int>> = _waypointAnchorIndices.asStateFlow()

    fun loadForEdit(routeId: String, showDerivedHint: Boolean = false) {
        if (existingId == routeId) return
        existingId = routeId
        val summary = routeRepository.getRouteSummary(routeId) ?: return
        _name.value = summary.name
        val loadedWaypoints = summary.waypoints ?: emptyList()
        _waypoints.value = loadedWaypoints
        if (loadedWaypoints.size >= 2) routeCurrentWaypoints()
        if (showDerivedHint) {
            _hint.value = "Editing may adjust this route slightly to follow roads."
        }
    }

    fun clearHint() {
        _hint.value = null
    }

    fun updateName(newName: String) {
        _name.value = newName
    }

    fun addWaypoint(lat: Double, lon: Double) {
        clearSelections()
        _waypoints.value = _waypoints.value + RouteWaypoint(lat, lon)
        routeCurrentWaypoints()
    }

    fun undoLastWaypoint() {
        if (_waypoints.value.isEmpty()) return
        clearSelections()
        _waypoints.value = _waypoints.value.dropLast(1)
        routeCurrentWaypoints()
    }

    fun clearWaypoints() {
        clearSelections()
        _waypoints.value = emptyList()
        _previewGpx.value = null
        rawGpxText = null
        _waypointAnchorIndices.value = emptyList()
    }

    /** Selects an existing waypoint (e.g. tapped on the map) so it can be deleted. */
    fun selectWaypoint(index: Int) {
        _selectedSegmentIndex.value = null
        _selectedWaypointIndex.value = index
    }

    /** Called when a pending delete is dismissed without confirming. */
    fun deselectWaypoint() {
        _selectedWaypointIndex.value = null
    }

    fun removeSelectedWaypoint() {
        val index = _selectedWaypointIndex.value ?: return
        clearSelections()
        _waypoints.value = _waypoints.value.filterIndexed { i, _ -> i != index }
        routeCurrentWaypoints()
    }

    /**
     * Long-press handler: highlights the existing route segment nearest [lat]/[lon] so a
     * following tap (anywhere - it usually needs to land *off* the current line to actually
     * divert the route) can insert a new waypoint into that segment.
     */
    fun selectNearestSegment(lat: Double, lon: Double) {
        val points = _previewGpx.value?.points ?: return
        val anchors = _waypointAnchorIndices.value
        if (anchors.size < 2 || points.isEmpty()) return
        _selectedWaypointIndex.value = null
        val nearestPoint = nearestPointIndex(points, lat, lon)
        var segment = anchors.size - 2
        for (i in 0 until anchors.size - 1) {
            if (nearestPoint <= anchors[i + 1]) {
                segment = i
                break
            }
        }
        _selectedSegmentIndex.value = segment
        _hint.value = "Tap to add a point here"
    }

    fun insertWaypointAtSelectedSegment(lat: Double, lon: Double) {
        val index = _selectedSegmentIndex.value ?: return
        clearSelections()
        _waypoints.value = _waypoints.value.toMutableList().apply { add(index + 1, RouteWaypoint(lat, lon)) }
        routeCurrentWaypoints()
    }

    private fun clearSelections() {
        _selectedWaypointIndex.value = null
        _selectedSegmentIndex.value = null
    }

    private fun nearestPointIndex(points: List<RoutePoint>, lat: Double, lon: Double, fromIndex: Int = 0): Int {
        var bestIndex = fromIndex
        var bestDistance = Double.MAX_VALUE
        for (index in fromIndex until points.size) {
            val distance = Haversine.distanceMeters(lat, lon, points[index].lat, points[index].lon)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun routeCurrentWaypoints() {
        val current = _waypoints.value
        if (current.size < 2) {
            _previewGpx.value = null
            rawGpxText = null
            _waypointAnchorIndices.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isRouting.value = true
            _error.value = null
            BRouterClient.route(current)
                .onSuccess { gpxText ->
                    rawGpxText = gpxText
                    val parsed = withContext(Dispatchers.Default) {
                        gpxText.byteInputStream().use { GpxParser.parse(it) }
                    }
                    _previewGpx.value = parsed
                    // Searched sequentially (each waypoint's search starts where the previous
                    // one's ended) rather than independently over the whole polyline, so the
                    // anchors come out non-decreasing even if the route loops back near an
                    // earlier waypoint - selectNearestSegment's binary-ish scan below assumes
                    // that ordering.
                    var searchFrom = 0
                    _waypointAnchorIndices.value = current.map { waypoint ->
                        val anchor = nearestPointIndex(parsed.points, waypoint.lat, waypoint.lon, searchFrom)
                        searchFrom = anchor
                        anchor
                    }
                }
                .onFailure {
                    rawGpxText = null
                    _previewGpx.value = null
                    _waypointAnchorIndices.value = emptyList()
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
            val points = route.points.map { LatLng(it.lat, it.lon) }
            if (RouteThumbnailGenerator.generate(app, points, destination)) {
                routeRepository.setThumbnail(summary.id, fileName)
            }
        }
    }
}
