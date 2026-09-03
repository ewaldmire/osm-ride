package com.ewaldmire.osmride.route

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** A tapped point placed by the user while building a route in-app. */
@Serializable
data class RouteWaypoint(val lat: Double, val lon: Double)

/**
 * Routes a sequence of tapped waypoints onto real roads/paths using BRouter's free public
 * routing API (https://brouter.de) - the same technique manually validated earlier via curl for
 * hand-built sample routes, now called directly from the app.
 */
object BRouterClient {
    private const val BASE_URL = "https://brouter.de/brouter"
    private const val PROFILE = "trekking"

    /** Returns the raw routed GPX text following roads through [waypoints], in order. */
    suspend fun route(waypoints: List<RouteWaypoint>): Result<String> = withContext(Dispatchers.IO) {
        if (waypoints.size < 2) {
            return@withContext Result.failure(IllegalArgumentException("Need at least 2 waypoints to route"))
        }
        try {
            val lonlats = waypoints.joinToString("|") { "${it.lon},${it.lat}" }
            val query = "lonlats=${URLEncoder.encode(lonlats, "UTF-8")}" +
                "&profile=$PROFILE&alternativeidx=0&format=gpx"
            val url = URL("$BASE_URL?$query")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            try {
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    return@withContext Result.failure(
                        IOException("BRouter request failed ($responseCode): ${errorBody ?: "no details"}"),
                    )
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                Result.success(body)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
