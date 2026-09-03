package com.ewaldmire.osmride.ui.routecreator

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
private const val PREVIEW_SOURCE_ID = "creator-preview-source"
private const val PREVIEW_LAYER_ID = "creator-preview-layer"
private const val EMPTY_FEATURE_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

/**
 * Map for building/editing a route: tapping places a waypoint (handled by the caller via
 * [onMapTapped]), placed waypoints render as dots, and [previewPoints] - the BRouter-routed line
 * through them, once fetched - renders as a polyline.
 */
@Composable
fun RouteCreatorMapView(
    waypoints: List<RouteWaypoint>,
    previewPoints: List<RouteWaypoint>?,
    onMapTapped: (lat: Double, lon: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context) }

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
                onMapTapped(latLng.latitude, latLng.longitude)
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
                style.addSource(GeoJsonSource(WAYPOINTS_SOURCE_ID))
                style.addLayer(
                    CircleLayer(WAYPOINTS_LAYER_ID, WAYPOINTS_SOURCE_ID).withProperties(
                        PropertyFactory.circleRadius(7f),
                        PropertyFactory.circleColor("#1976D2"),
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(2f),
                    ),
                )

                if (waypoints.isEmpty()) return@setStyle
                val boundsBuilder = LatLngBounds.Builder()
                waypoints.forEach { boundsBuilder.include(LatLng(it.lat, it.lon)) }
                if (waypoints.size == 1) {
                    val only = waypoints.first()
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(only.lat, only.lon), 15.0))
                } else {
                    map.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 96))
                }
            }
        }
        onDispose { }
    }

    DisposableEffect(waypoints) {
        mapView.getMapAsync { map ->
            val style = map.style ?: return@getMapAsync
            val source = style.getSourceAs<GeoJsonSource>(WAYPOINTS_SOURCE_ID) ?: return@getMapAsync
            source.setGeoJson(waypointsGeoJson(waypoints))
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

    AndroidView(factory = { mapView }, modifier = modifier.fillMaxSize())
}

private fun waypointsGeoJson(waypoints: List<RouteWaypoint>): String {
    if (waypoints.isEmpty()) return EMPTY_FEATURE_COLLECTION
    val features = waypoints.joinToString(",") {
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[${it.lon},${it.lat}]},"properties":{}}"""
    }
    return """{"type":"FeatureCollection","features":[$features]}"""
}
