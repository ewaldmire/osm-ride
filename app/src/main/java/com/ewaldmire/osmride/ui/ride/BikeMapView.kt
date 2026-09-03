package com.ewaldmire.osmride.ui.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import android.content.Context
import com.ewaldmire.osmride.R
import com.ewaldmire.osmride.ride.RidePosition
import com.ewaldmire.osmride.route.Route
import com.ewaldmire.osmride.ui.map.ThreeDMapStyle
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val ROUTE_SOURCE_ID = "route-source"
private const val ROUTE_LAYER_ID = "route-layer"
private const val BIKE_SOURCE_ID = "bike-source"
private const val BIKE_LAYER_ID = "bike-layer"
private const val BIKE_ICON_ID = "bike-icon"

/** Pitch (degrees from straight-down) for the following camera - gives a 3D "chase cam" view of
 * buildings along the route instead of a flat top-down map. */
private const val RIDE_CAMERA_TILT_DEGREES = 55.0

/**
 * MapLibre map showing the route polyline and a bike marker that follows live ride progress.
 *
 * @param zoomLevel camera zoom while following the bike; caller-controlled so on-screen +/-
 *   buttons can adjust it and have it "stick".
 * @param headingUp true rotates the camera to match travel direction (bike always points up);
 *   false keeps the map north-up and only the bike icon itself rotates.
 */
@Composable
fun BikeMapView(
    route: Route,
    position: RidePosition?,
    followBike: Boolean,
    zoomLevel: Double,
    headingUp: Boolean,
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

    DisposableEffect(route.id) {
        mapView.getMapAsync { loadedMap ->
            // MapLibre's default compass widget is positioned by fixed margins from the
            // MapView's own top-right corner, which extends edge-to-edge under the status bar —
            // it can't account for our Compose-side statusBarsPadding(), so it renders behind
            // the status bar icons. We don't rely on manual map rotation, so just drop it.
            loadedMap.uiSettings.isCompassEnabled = false
            loadedMap.setStyle(Style.Builder().fromUri(ThreeDMapStyle.STYLE_URI)) { style ->
                setUpRouteAndMarker(context, style, route)
                fitCameraToRoute(loadedMap, route)
            }
        }
        onDispose { }
    }

    DisposableEffect(position, followBike, zoomLevel, headingUp) {
        if (position != null) {
            mapView.getMapAsync { map ->
                val style = map.style ?: return@getMapAsync
                updateBikePosition(style, position)
                if (followBike) {
                    // Heading-up: rotate the camera to match travel direction (paired with
                    // iconRotationAlignment(MAP) below, the bike icon then renders pointing
                    // straight up on screen, matching the rotated map underneath it). North-up:
                    // camera bearing stays fixed at 0 and only the icon itself rotates.
                    val cameraPosition = CameraPosition.Builder()
                        .target(LatLng(position.lat, position.lon))
                        .zoom(zoomLevel)
                        .bearing(if (headingUp) position.bearingDegrees else 0.0)
                        .tilt(RIDE_CAMERA_TILT_DEGREES)
                        .build()
                    map.easeCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 900)
                }
            }
        }
        onDispose { }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        Text(
            text = ThreeDMapStyle.ATTRIBUTION,
            color = Color.White,
            fontSize = 9.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

private fun setUpRouteAndMarker(context: Context, style: Style, route: Route) {
    val routeLine = LineString.fromLngLats(route.points.map { Point.fromLngLat(it.lon, it.lat) })
    style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, Feature.fromGeometry(routeLine)))
    style.addLayer(
        LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
            PropertyFactory.lineColor("#EF6C00"),
            PropertyFactory.lineWidth(5f),
        ),
    )

    val bikeDrawable = ContextCompat.getDrawable(context, R.drawable.ic_bike_avatar)
    if (bikeDrawable != null) {
        style.addImage(BIKE_ICON_ID, bikeDrawable.toBitmap())
    }

    val start = route.points.firstOrNull()
    val startPoint = Point.fromLngLat(start?.lon ?: 0.0, start?.lat ?: 0.0)
    style.addSource(GeoJsonSource(BIKE_SOURCE_ID, Feature.fromGeometry(startPoint)))
    style.addLayer(
        SymbolLayer(BIKE_LAYER_ID, BIKE_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(BIKE_ICON_ID),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconRotate(0f),
        ),
    )
}

private fun updateBikePosition(style: Style, position: RidePosition) {
    val source = style.getSourceAs<GeoJsonSource>(BIKE_SOURCE_ID)
    source?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(position.lon, position.lat)))
    val layer = style.getLayerAs<SymbolLayer>(BIKE_LAYER_ID)
    layer?.setProperties(PropertyFactory.iconRotate(position.bearingDegrees.toFloat()))
}

private fun fitCameraToRoute(map: MapLibreMap, route: Route) {
    if (route.points.size < 2) return
    val boundsBuilder = LatLngBounds.Builder()
    route.points.forEach { boundsBuilder.include(LatLng(it.lat, it.lon)) }
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 96))
}
