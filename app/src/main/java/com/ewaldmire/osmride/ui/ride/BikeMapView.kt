package com.ewaldmire.osmride.ui.ride

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import android.content.Context
import com.ewaldmire.osmride.R
import com.ewaldmire.osmride.ride.RidePosition
import com.ewaldmire.osmride.route.Route
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
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

/** Plain raster style using standard OpenStreetMap tiles — no vector style/API key needed. */
private const val OSM_RASTER_STYLE_JSON = """
{
  "version": 8,
  "sources": {
    "osm-raster": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [
    {
      "id": "osm-raster-layer",
      "type": "raster",
      "source": "osm-raster"
    }
  ]
}
"""

/** MapLibre map showing the route polyline and a bike marker that follows live ride progress. */
@Composable
fun BikeMapView(route: Route, position: RidePosition?, followBike: Boolean, modifier: Modifier = Modifier) {
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
            loadedMap.setStyle(Style.Builder().fromJson(OSM_RASTER_STYLE_JSON)) { style ->
                setUpRouteAndMarker(context, style, route)
                fitCameraToRoute(loadedMap, route)
            }
        }
        onDispose { }
    }

    DisposableEffect(position, followBike) {
        if (position != null) {
            mapView.getMapAsync { map ->
                val style = map.style ?: return@getMapAsync
                updateBikePosition(style, position)
                if (followBike) {
                    map.easeCamera(CameraUpdateFactory.newLatLng(LatLng(position.lat, position.lon)), 500)
                }
            }
        }
        onDispose { }
    }

    AndroidView(factory = { mapView }, modifier = modifier.fillMaxSize())
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
