package com.ewaldmire.osmride.route

import android.content.Context
import android.graphics.Bitmap
import com.ewaldmire.osmride.ui.map.ThreeDMapStyle
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val THUMBNAIL_WIDTH = 300
private const val THUMBNAIL_HEIGHT = 180
private const val ROUTE_SOURCE_ID = "thumbnail-route"
private const val ROUTE_LAYER_ID = "thumbnail-route-line"

/**
 * Renders a small cached MapLibre snapshot of a route's shape, generated once at import/edit
 * time rather than redrawn on every list render (see RouteSummary.thumbnailFileName). Uses
 * MapSnapshotter - MapLibre's built-in headless/static rendering path, the same library
 * BikeMapView.kt uses for the live ride map - rather than a visible MapView. The route line is
 * baked into the style via a Style.Builder (the same GeoJsonSource/LineLayer construction
 * BikeMapView.kt uses) rather than a plain style URL, so the snapshot shows the route, not just
 * bare terrain.
 */
object RouteThumbnailGenerator {
    suspend fun generate(context: Context, route: Route, destination: File): Boolean {
        if (route.points.size < 2) return false

        val boundsBuilder = LatLngBounds.Builder()
        route.points.forEach { boundsBuilder.include(LatLng(it.lat, it.lon)) }

        val routeLine = LineString.fromLngLats(route.points.map { Point.fromLngLat(it.lon, it.lat) })
        val styleBuilder = Style.Builder()
            .fromUri(ThreeDMapStyle.STYLE_URI)
            .withSource(GeoJsonSource(ROUTE_SOURCE_ID, Feature.fromGeometry(routeLine)))
            .withLayer(
                LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                    PropertyFactory.lineColor("#EF6C00"),
                    PropertyFactory.lineWidth(4f),
                ),
            )

        val options = MapSnapshotter.Options(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT)
            .withStyleBuilder(styleBuilder)
            .withRegion(boundsBuilder.build())

        // MapSnapshotter needs to run on the main thread (it drives an internal GL context) -
        // this must not be wrapped in withContext(Dispatchers.IO).
        val bitmap = suspendCancellableCoroutine<Bitmap?> { continuation ->
            val snapshotter = MapSnapshotter(context, options)
            snapshotter.start(
                { snapshot -> continuation.resume(snapshot.bitmap) },
                { _ -> continuation.resume(null) },
            )
            continuation.invokeOnCancellation { snapshotter.cancel() }
        } ?: return false

        return withContext(Dispatchers.IO) {
            try {
                FileOutputStream(destination).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
