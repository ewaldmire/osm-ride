package com.ewaldmire.osmride.route

import com.ewaldmire.osmride.util.Haversine
import kotlin.math.abs
import kotlin.math.max

/**
 * Derives a sparse, editable [RouteWaypoint] list from a dense recorded/imported track. Used to
 * make imported GPX routes editable in the Route Creator, which drags/re-routes a small set of
 * waypoints via BRouter rather than the full recorded polyline. Mirrors
 * linux-client/osm_ride_linux/route/waypoint_simplifier.py.
 */
object WaypointSimplifier {
    // Roughly one waypoint every 400m along straight stretches - a round metric distance, not a
    // mile-derived one, since cumulativeDistanceMeters is already metric internally regardless
    // of the app's imperial display units.
    private const val MIN_SPACING_METERS = 400.0

    // A bearing change at or above this, between the incoming and outgoing segment, forces a
    // waypoint even if it's short of MIN_SPACING_METERS - otherwise a sharp turn that happens to
    // fall inside one sampling interval would get smoothed away when BRouter re-routes between
    // its neighbors.
    private const val TURN_THRESHOLD_DEGREES = 30.0

    // Floor below which a "sharp turn" still isn't kept - without this, GPS jitter/noise near a
    // real turn could produce a cluster of near-duplicate forced waypoints.
    private const val MIN_TURN_SPACING_METERS = 50.0

    // Widens the effective spacing for very long routes so a 100+ mile import doesn't produce an
    // unwieldy number of waypoints (a long BRouter request, a map full of indistinguishable pins).
    private const val MAX_WAYPOINTS = 150

    fun deriveWaypoints(points: List<RoutePoint>): List<RouteWaypoint> {
        if (points.size <= 2) {
            return points.map { RouteWaypoint(it.lat, it.lon) }
        }

        val totalDistance = points.last().cumulativeDistanceMeters
        val minSpacing = max(MIN_SPACING_METERS, totalDistance / MAX_WAYPOINTS)

        val kept = mutableListOf(points[0])
        var lastKeptDistance = points[0].cumulativeDistanceMeters

        for (i in 1 until points.size - 1) {
            val point = points[i]
            val distanceSinceLast = point.cumulativeDistanceMeters - lastKeptDistance
            if (distanceSinceLast >= minSpacing) {
                kept.add(point)
                lastKeptDistance = point.cumulativeDistanceMeters
                continue
            }
            if (distanceSinceLast >= MIN_TURN_SPACING_METERS && isSharpTurn(points[i - 1], point, points[i + 1])) {
                kept.add(point)
                lastKeptDistance = point.cumulativeDistanceMeters
            }
        }

        kept.add(points.last())
        return kept.map { RouteWaypoint(it.lat, it.lon) }
    }

    private fun isSharpTurn(before: RoutePoint, at: RoutePoint, after: RoutePoint): Boolean {
        val incomingBearing = Haversine.bearingDegrees(before.lat, before.lon, at.lat, at.lon)
        val outgoingBearing = Haversine.bearingDegrees(at.lat, at.lon, after.lat, after.lon)
        var turnAngle = abs(outgoingBearing - incomingBearing) % 360
        if (turnAngle > 180) {
            turnAngle = 360 - turnAngle
        }
        return turnAngle >= TURN_THRESHOLD_DEGREES
    }
}
