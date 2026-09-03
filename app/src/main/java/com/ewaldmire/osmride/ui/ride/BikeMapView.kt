package com.ewaldmire.osmride.ui.ride

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillExtrusionLayer
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

/** Layer id of the 3D buildings baked into OpenFreeMap's "Liberty" style. */
private const val BUILDING_3D_LAYER_ID = "building-3d"

/** OSM's building:colour tag is too sparse to rely on, so buildings all render the same flat
 * tone by default. Picking one of these by each building's render_height (mod 5) fakes enough
 * variety to break up the monotony without needing real per-building color data. */
private val BUILDING_COLORS = intArrayOf(
    0xFFC9BBA8.toInt(), // warm beige
    0xFFB8AFA0.toInt(), // warm gray
    0xFFA79C8C.toInt(), // taupe
    0xFFC08552.toInt(), // brick/terracotta
    0xFF9C9186.toInt(), // cool gray
)

/** Default pitch (degrees from straight-down) for the following camera - gives a 3D "chase cam"
 * view of buildings along the route instead of a flat top-down map. */
const val RIDE_CAMERA_DEFAULT_TILT_DEGREES = 65.0

/** Ceiling for both the tilt slider and the two-finger tilt gesture - MapLibre's own default is
 * 60; this asks for more legroom toward a horizon-level view, but flat vector tiles (no terrain
 * mesh, no sky) will look increasingly stretched/distorted well before reaching it. */
const val RIDE_CAMERA_MAX_PITCH_DEGREES = 80.0

/** Fraction of the map's height reserved as top padding while following - this recentres the
 * camera's focal point toward the bottom of the screen, so the bike renders "in the foreground"
 * with the road/horizon ahead of it visible above, instead of sitting dead-center. */
private const val RIDE_CAMERA_TOP_PADDING_FRACTION = 0.6

/**
 * MapLibre map showing the route polyline and a bike marker that follows live ride progress.
 *
 * @param zoomLevel camera zoom while following the bike; caller-controlled so on-screen +/-
 *   buttons can adjust it and have it "stick".
 * @param headingUp true rotates the camera to match travel direction (bike always points up);
 *   false keeps the map north-up and only the bike icon itself rotates.
 * @param tiltDegrees camera pitch in degrees (0 = flat top-down, higher = more of a 3D chase-cam
 *   view); caller-controlled by the tilt slider, same "stick until a control is touched" pattern
 *   as [zoomLevel].
 */
@Composable
fun BikeMapView(
    route: Route,
    position: RidePosition?,
    followBike: Boolean,
    zoomLevel: Double,
    headingUp: Boolean,
    tiltDegrees: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val mapView = remember { MapView(context) }

    // True once the rider has manually pinch-zoomed/rotated/tilted the map - while true, the
    // follow-camera stops overriding zoom/bearing/tilt every tick (just keeps recentring on the
    // bike) so the manual adjustment "sticks". Cleared below whenever zoomLevel/headingUp
    // actually change, i.e. whenever a MapControls button is tapped.
    var manualOverrideActive by remember { mutableStateOf(false) }

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
            // We show our own compact attribution text below instead - the native widgets
            // duplicate it and stack awkwardly in the same corner.
            loadedMap.uiSettings.isAttributionEnabled = false
            loadedMap.uiSettings.isLogoEnabled = false
            // Standard 3D map gestures: two-finger vertical drag tilts, two-finger twist rotates,
            // pinch zooms - on by default, made explicit here since the tilt button above is the
            // discoverable counterpart to the two-finger-drag tilt gesture.
            loadedMap.uiSettings.isTiltGesturesEnabled = true
            loadedMap.setMaxPitchPreference(RIDE_CAMERA_MAX_PITCH_DEGREES)
            loadedMap.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    manualOverrideActive = true
                }
            }
            loadedMap.setStyle(Style.Builder().fromUri(ThreeDMapStyle.STYLE_URI)) { style ->
                setUpRouteAndMarker(context, style, route)
                applyBuildingColorVariety(style)
                fitCameraToRoute(loadedMap, route)
            }
        }
        onDispose { }
    }

    // A MapControls button tap is the only thing that changes zoomLevel/headingUp - use that as
    // the "revert to normal behavior" signal, distinct from the position-driven effect below
    // (which re-runs every tick and must not clear the override on its own).
    DisposableEffect(zoomLevel, headingUp, tiltDegrees) {
        manualOverrideActive = false
        onDispose { }
    }

    DisposableEffect(position, followBike, zoomLevel, headingUp, tiltDegrees, manualOverrideActive) {
        if (position != null) {
            mapView.getMapAsync { map ->
                val style = map.style ?: return@getMapAsync
                updateBikePosition(style, position)
                if (followBike) {
                    if (manualOverrideActive) {
                        // Keep whatever zoom/bearing/tilt the rider set manually; just recentre.
                        map.easeCamera(
                            CameraUpdateFactory.newLatLng(LatLng(position.lat, position.lon)),
                            900,
                        )
                    } else {
                        // Heading-up: rotate the camera to match travel direction (paired with
                        // iconRotationAlignment(MAP) below, the bike icon then renders pointing
                        // straight up on screen, matching the rotated map underneath it).
                        // North-up: camera bearing stays fixed at 0 and only the icon rotates.
                        // Padding is only meaningful while tilted (it's what reveals the horizon
                        // above the bike), so it fades out toward 0 for a flat top-down view.
                        val tiltFraction = (tiltDegrees / RIDE_CAMERA_MAX_PITCH_DEGREES.toFloat()).coerceIn(0f, 1f)
                        val topPaddingPx = (mapView.height * RIDE_CAMERA_TOP_PADDING_FRACTION * tiltFraction).toInt()
                        map.setPadding(0, topPaddingPx, 0, 0)
                        val cameraPosition = CameraPosition.Builder()
                            .target(LatLng(position.lat, position.lon))
                            .zoom(zoomLevel)
                            .bearing(if (headingUp) position.bearingDegrees else 0.0)
                            .tilt(tiltDegrees.toDouble())
                            .build()
                        map.easeCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 900)
                    }
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

private fun applyBuildingColorVariety(style: Style) {
    val buildingLayer = style.getLayerAs<FillExtrusionLayer>(BUILDING_3D_LAYER_ID) ?: return
    val heightBucket = Expression.mod(
        Expression.toNumber(Expression.get("render_height")),
        Expression.literal(BUILDING_COLORS.size),
    )
    val colorStops = BUILDING_COLORS.mapIndexed { index, color ->
        Expression.stop(index, Expression.color(color))
    }.toTypedArray()
    buildingLayer.setProperties(
        PropertyFactory.fillExtrusionColor(
            Expression.interpolate(Expression.linear(), heightBucket, *colorStops),
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
    map.setPadding(0, 0, 0, 0)
    val boundsBuilder = LatLngBounds.Builder()
    route.points.forEach { boundsBuilder.include(LatLng(it.lat, it.lon)) }
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 96))
}
