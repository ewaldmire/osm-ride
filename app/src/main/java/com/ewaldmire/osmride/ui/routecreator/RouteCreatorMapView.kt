package com.ewaldmire.osmride.ui.routecreator

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.ewaldmire.osmride.route.RouteWaypoint
import com.ewaldmire.osmride.ui.map.OsmRasterStyle
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val WAYPOINTS_SOURCE_ID = "creator-waypoints-source"
private const val WAYPOINTS_LAYER_ID = "creator-waypoints-layer"
private const val SELECTED_SOURCE_ID = "creator-selected-source"
private const val SELECTED_LAYER_ID = "creator-selected-layer"
private const val PREVIEW_SOURCE_ID = "creator-preview-source"
private const val PREVIEW_LAYER_ID = "creator-preview-layer"
private const val SEGMENT_HIGHLIGHT_SOURCE_ID = "creator-segment-highlight-source"
private const val SEGMENT_HIGHLIGHT_LAYER_ID = "creator-segment-highlight-layer"
private const val EMPTY_FEATURE_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

/**
 * Map for building/editing a route. Three gestures, dispatched by the caller:
 * - Tap empty map / [onMapTapped]: append a waypoint (or, if the caller has a segment selected
 *   from a prior long-press, insert one into that segment instead - this view doesn't know which).
 * - Tap an existing waypoint / [onWaypointTapped]: hit-tested here via [MapLibreMap]'s own
 *   `queryRenderedFeatures` against the rendered waypoint dots, so precision is screen-space (it
 *   naturally tightens as the user zooms in) rather than a fixed ground-distance radius.
 * - Long-press anywhere / [onSegmentLongPressed]: the press doesn't need to land on anything in
 *   particular - the caller resolves it to the nearest existing route segment.
 */
@Composable
fun RouteCreatorMapView(
    waypoints: List<RouteWaypoint>,
    previewPoints: List<RouteWaypoint>?,
    highlightedSegmentPoints: List<RouteWaypoint>?,
    selectedWaypointIndex: Int?,
    onMapTapped: (lat: Double, lon: Double) -> Unit,
    onWaypointTapped: (index: Int) -> Unit,
    onSegmentLongPressed: (lat: Double, lon: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }
    // Fits the camera to the route's bounds exactly once, the first time waypoints go from
    // empty to non-empty (i.e. an existing route just finished loading for editing) - not on
    // every subsequent waypoints change, or each tap while building a new route would yank the
    // camera back to fit the whole-so-far route instead of leaving it where the user placed it.
    val hasCenteredOnce = remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    DisposableEffect(Unit) {
        mapView.getMapAsync { map ->
            map.uiSettings.isCompassEnabled = false
            map.addOnMapClickListener { latLng ->
                val screenPoint = map.projection.toScreenLocation(latLng)
                val tappedIndex = map.queryRenderedFeatures(screenPoint, WAYPOINTS_LAYER_ID)
                    .firstOrNull()
                    ?.getNumberProperty("index")
                    ?.toInt()
                if (tappedIndex != null) {
                    onWaypointTapped(tappedIndex)
                } else {
                    onMapTapped(latLng.latitude, latLng.longitude)
                }
                true
            }
            map.addOnMapLongClickListener { latLng ->
                onSegmentLongPressed(latLng.latitude, latLng.longitude)
                true
            }
            map.setStyle(Style.Builder().fromJson(OsmRasterStyle.JSON)) { style ->
                style.addSource(GeoJsonSource(PREVIEW_SOURCE_ID))
                style.addLayer(
                    LineLayer(PREVIEW_LAYER_ID, PREVIEW_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor("#EF6C00"),
                        PropertyFactory.lineWidth(5f),
                    ),
                )
                style.addSource(GeoJsonSource(SEGMENT_HIGHLIGHT_SOURCE_ID))
                style.addLayer(
                    LineLayer(SEGMENT_HIGHLIGHT_LAYER_ID, SEGMENT_HIGHLIGHT_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor("#FFC107"),
                        PropertyFactory.lineWidth(8f),
                    ),
                )
                // Painted before the normal waypoint dots so it reads as a highlight halo behind
                // the selected one, rather than covering it.
                style.addSource(GeoJsonSource(SELECTED_SOURCE_ID))
                style.addLayer(
                    CircleLayer(SELECTED_LAYER_ID, SELECTED_SOURCE_ID).withProperties(
                        PropertyFactory.circleRadius(13f),
                        PropertyFactory.circleColor("#FFC107"),
                    ),
                )
                style.addSource(GeoJsonSource(WAYPOINTS_SOURCE_ID))
                style.addLayer(
                    CircleLayer(WAYPOINTS_LAYER_ID, WAYPOINTS_SOURCE_ID).withProperties(
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleColor("#1976D2"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(2f),
                    ),
                )
            }
        }
        onDispose { }
    }

    DisposableEffect(waypoints, selectedWaypointIndex) {
        mapView.getMapAsync { map ->
            val style = map.style ?: return@getMapAsync
            style.getSourceAs<GeoJsonSource>(WAYPOINTS_SOURCE_ID)?.setGeoJson(waypointsGeoJson(waypoints))
            style.getSourceAs<GeoJsonSource>(SELECTED_SOURCE_ID)
                ?.setGeoJson(selectedWaypointGeoJson(waypoints, selectedWaypointIndex))

            if (!hasCenteredOnce.value && waypoints.isNotEmpty()) {
                hasCenteredOnce.value = true
                if (waypoints.size == 1) {
                    val only = waypoints.first()
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(only.lat, only.lon), 15.0))
                } else {
                    val boundsBuilder = LatLngBounds.Builder()
                    waypoints.forEach { boundsBuilder.include(LatLng(it.lat, it.lon)) }
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 96))
                }
            }
        }
        onDispose { }
    }

    DisposableEffect(previewPoints) {
        mapView.getMapAsync { map ->
            val style = map.style ?: return@getMapAsync
            val source = style.getSourceAs<GeoJsonSource>(PREVIEW_SOURCE_ID) ?: return@getMapAsync
            if (previewPoints != null && previewPoints.size >= 2) {
                val line = LineString.fromLngLats(previewPoints.map { Point.fromLngLat(it.lon, it.lat) })
                source.setGeoJson(Feature.fromGeometry(line))
            } else {
                source.setGeoJson(EMPTY_FEATURE_COLLECTION)
            }
        }
        onDispose { }
    }

    DisposableEffect(highlightedSegmentPoints) {
        mapView.getMapAsync { map ->
            val style = map.style ?: return@getMapAsync
            val source = style.getSourceAs<GeoJsonSource>(SEGMENT_HIGHLIGHT_SOURCE_ID) ?: return@getMapAsync
            if (highlightedSegmentPoints != null && highlightedSegmentPoints.size >= 2) {
                val line = LineString.fromLngLats(
                    highlightedSegmentPoints.map { Point.fromLngLat(it.lon, it.lat) },
                )
                source.setGeoJson(Feature.fromGeometry(line))
            } else {
                source.setGeoJson(EMPTY_FEATURE_COLLECTION)
            }
        }
        onDispose { }
    }

    AndroidView(factory = { mapView }, modifier = modifier.fillMaxSize())
}

private fun waypointsGeoJson(waypoints: List<RouteWaypoint>): String {
    if (waypoints.isEmpty()) return EMPTY_FEATURE_COLLECTION
    val features = waypoints.mapIndexed { index, waypoint ->
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[${waypoint.lon},${waypoint.lat}]},"properties":{"index":$index}}"""
    }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$features]}"""
}

private fun selectedWaypointGeoJson(waypoints: List<RouteWaypoint>, selectedIndex: Int?): String {
    val waypoint = selectedIndex?.let { waypoints.getOrNull(it) } ?: return EMPTY_FEATURE_COLLECTION
    return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[${waypoint.lon},${waypoint.lat}]},"properties":{}}]}"""
}
