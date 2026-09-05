package com.ewaldmire.osmride.route

import kotlinx.serialization.Serializable

data class RoutePoint(
    val lat: Double,
    val lon: Double,
    val elevationMeters: Double?,
    /** Distance in meters from the start of the route to this point, along the track. */
    val cumulativeDistanceMeters: Double,
)

data class Route(
    val id: String,
    val name: String,
    val points: List<RoutePoint>,
    val totalDistanceMeters: Double,
    val elevationGainMeters: Double,
)

/** Lightweight, persisted index entry — avoids re-parsing every GPX just to show the list. */
@Serializable
data class RouteSummary(
    val id: String,
    val name: String,
    val fileName: String,
    val totalDistanceMeters: Double,
    val elevationGainMeters: Double,
    val importedAtEpochMillis: Long,
    /** Non-null only for routes built in-app with the route creator; used to reopen them for editing. */
    val waypoints: List<RouteWaypoint>? = null,
    /** A small cached MapLibre snapshot (PNG, in the same routes dir), generated once at
     * import/edit time rather than redrawn on every list render. Null until generation finishes
     * (or for routes imported before this field existed) - the list falls back to a placeholder
     * icon until the next edit regenerates it. */
    val thumbnailFileName: String? = null,
)
