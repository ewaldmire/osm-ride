package com.ewaldmire.osmride.route

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Imports GPX routes into app-private storage and keeps a small JSON index for the list screen. */
class RouteRepository(context: Context) {
    private val appContext = context.applicationContext
    private val routesDir: File = File(appContext.filesDir, "routes").apply { mkdirs() }
    private val indexFile = File(routesDir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _routes = MutableStateFlow(loadIndex())
    val routes: StateFlow<List<RouteSummary>> = _routes.asStateFlow()

    suspend fun importGpx(uri: Uri, displayNameHint: String?): Result<RouteSummary> =
        withContext(Dispatchers.IO) {
            try {
                val id = UUID.randomUUID().toString()
                val destFile = File(routesDir, "$id.gpx")
                val opened = appContext.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                    true
                } ?: false
                if (!opened) {
                    return@withContext Result.failure(IOException("Could not open $uri"))
                }

                val parsed = destFile.inputStream().use { GpxParser.parse(it) }
                if (parsed.points.size < 2) {
                    destFile.delete()
                    return@withContext Result.failure(
                        IllegalArgumentException("GPX file has no usable track points"),
                    )
                }

                val name = parsed.name?.trim()?.takeIf { it.isNotEmpty() }
                    ?: displayNameHint?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "Imported Route"

                val summary = RouteSummary(
                    id = id,
                    name = name,
                    fileName = destFile.name,
                    totalDistanceMeters = parsed.totalDistanceMeters,
                    elevationGainMeters = parsed.elevationGainMeters,
                    importedAtEpochMillis = System.currentTimeMillis(),
                )
                val updated = _routes.value + summary
                _routes.value = updated
                saveIndex(updated)
                Result.success(summary)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun loadRoute(id: String): Route? = withContext(Dispatchers.IO) {
        val summary = _routes.value.find { it.id == id } ?: return@withContext null
        val file = File(routesDir, summary.fileName)
        if (!file.exists()) return@withContext null
        val parsed = file.inputStream().use { GpxParser.parse(it) }
        Route(
            id = summary.id,
            name = summary.name,
            points = parsed.points,
            totalDistanceMeters = parsed.totalDistanceMeters,
            elevationGainMeters = parsed.elevationGainMeters,
        )
    }

    /**
     * Saves a route built (or edited) in-app via the route creator. Pass [existingId] when
     * re-routing an already-created route so it updates in place instead of duplicating.
     */
    suspend fun saveCreatedRoute(
        existingId: String?,
        name: String,
        gpxContent: String,
        waypoints: List<RouteWaypoint>,
    ): Result<RouteSummary> = withContext(Dispatchers.IO) {
        try {
            val id = existingId ?: UUID.randomUUID().toString()
            val destFile = File(routesDir, "$id.gpx")
            destFile.writeText(gpxContent)

            val parsed = destFile.inputStream().use { GpxParser.parse(it) }
            if (parsed.points.size < 2) {
                destFile.delete()
                return@withContext Result.failure(
                    IllegalArgumentException("Route has no usable track points"),
                )
            }

            val existing = _routes.value.find { it.id == id }
            val summary = RouteSummary(
                id = id,
                name = name.trim().ifEmpty { "New Route" },
                fileName = destFile.name,
                totalDistanceMeters = parsed.totalDistanceMeters,
                elevationGainMeters = parsed.elevationGainMeters,
                importedAtEpochMillis = existing?.importedAtEpochMillis ?: System.currentTimeMillis(),
                waypoints = waypoints,
            )
            val updated = if (existing != null) {
                _routes.value.map { if (it.id == id) summary else it }
            } else {
                _routes.value + summary
            }
            _routes.value = updated
            saveIndex(updated)
            Result.success(summary)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getRouteSummary(id: String): RouteSummary? = _routes.value.find { it.id == id }

    suspend fun renameRoute(id: String, name: String) = withContext(Dispatchers.IO) {
        val resolved = name.ifBlank { return@withContext }
        val updated = _routes.value.map { if (it.id == id) it.copy(name = resolved) else it }
        _routes.value = updated
        saveIndex(updated)
    }

    suspend fun deleteRoute(id: String) = withContext(Dispatchers.IO) {
        val summary = _routes.value.find { it.id == id } ?: return@withContext
        File(routesDir, summary.fileName).delete()
        val updated = _routes.value.filterNot { it.id == id }
        _routes.value = updated
        saveIndex(updated)
    }

    private fun loadIndex(): List<RouteSummary> {
        if (!indexFile.exists()) return emptyList()
        return try {
            json.decodeFromString<List<RouteSummary>>(indexFile.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveIndex(summaries: List<RouteSummary>) {
        indexFile.writeText(json.encodeToString(summaries))
    }
}
