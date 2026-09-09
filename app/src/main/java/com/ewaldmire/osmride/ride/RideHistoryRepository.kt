package com.ewaldmire.osmride.ride

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persists completed rides (GPX + summary) to app-private storage for the history screen. */
class RideHistoryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val ridesDir: File = File(appContext.filesDir, "rides").apply { mkdirs() }
    private val indexFile = File(ridesDir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _rides = MutableStateFlow(loadIndex())
    /** Newest first. */
    val rides: StateFlow<List<RideRecord>> = _rides.asStateFlow()

    suspend fun saveRide(routeName: String, routeId: String?, stats: RideStats, gpxContent: String): RideRecord =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val fileName = "$id.gpx"
            File(ridesDir, fileName).writeText(gpxContent)

            val record = RideRecord(
                id = id,
                routeName = routeName,
                title = routeName,
                routeId = routeId,
                completedAtEpochMillis = System.currentTimeMillis(),
                distanceMeters = stats.distanceMeters,
                durationSeconds = stats.elapsedSeconds,
                avgSpeedMps = stats.avgSpeedMps,
                avgPowerWatts = stats.avgPowerWatts,
                avgCadenceRpm = stats.avgCadenceRpm,
                avgHeartRateBpm = stats.avgHeartRateBpm,
                estimatedKilocalories = stats.estimatedKilocalories,
                gpxFileName = fileName,
            )
            val updated = listOf(record) + _rides.value
            _rides.value = updated
            saveIndex(updated)
            record
        }

    /** Saves an outdoor ride imported from a .fit file - same storage shape as a live-recorded
     * ride, just with no [RideRecord.routeId] (there's no in-app route it was ridden against) and
     * a caller-supplied completion time (the ride's own recorded time, not "now"). See
     * FitFileParser/RideHistoryViewModel.importFitFile. */
    suspend fun importRide(
        title: String,
        completedAtEpochMillis: Long,
        distanceMeters: Double,
        durationSeconds: Long,
        avgSpeedMps: Double,
        avgPowerWatts: Double?,
        avgCadenceRpm: Double?,
        avgHeartRateBpm: Double?,
        estimatedKilocalories: Double?,
        gpxContent: String,
    ): RideRecord = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val fileName = "$id.gpx"
        File(ridesDir, fileName).writeText(gpxContent)

        val record = RideRecord(
            id = id,
            routeName = title,
            title = title,
            routeId = null,
            completedAtEpochMillis = completedAtEpochMillis,
            distanceMeters = distanceMeters,
            durationSeconds = durationSeconds,
            avgSpeedMps = avgSpeedMps,
            avgPowerWatts = avgPowerWatts,
            avgCadenceRpm = avgCadenceRpm,
            avgHeartRateBpm = avgHeartRateBpm,
            estimatedKilocalories = estimatedKilocalories,
            gpxFileName = fileName,
        )
        val updated = (listOf(record) + _rides.value).sortedByDescending { it.completedAtEpochMillis }
        _rides.value = updated
        saveIndex(updated)
        record
    }

    /** Lets the rider rename a ride and add notes after the fact - useful when they ride the
     * same route regularly and want to tell repeat rides of it apart in history. */
    suspend fun updateRide(id: String, title: String, notes: String) = withContext(Dispatchers.IO) {
        val updated = _rides.value.map { if (it.id == id) it.copy(title = title, notes = notes) else it }
        _rides.value = updated
        saveIndex(updated)
    }

    fun gpxFile(record: RideRecord): File = File(ridesDir, record.gpxFileName)

    /** Called once RouteThumbnailGenerator finishes snapshotting this ride's own recorded track -
     * never blocks saveRide, since generation happens asynchronously afterward. */
    suspend fun setThumbnail(id: String, thumbnailFileName: String) = withContext(Dispatchers.IO) {
        val updated = _rides.value.map { if (it.id == id) it.copy(thumbnailFileName = thumbnailFileName) else it }
        _rides.value = updated
        saveIndex(updated)
    }

    fun thumbnailFile(record: RideRecord): File? =
        record.thumbnailFileName?.let { File(ridesDir, it) }

    /** Where a newly-generated ride thumbnail should be written - see RouteThumbnailGenerator. */
    val directory: File get() = ridesDir

    suspend fun deleteRide(id: String) = withContext(Dispatchers.IO) {
        val record = _rides.value.find { it.id == id } ?: return@withContext
        File(ridesDir, record.gpxFileName).delete()
        thumbnailFile(record)?.delete()
        val updated = _rides.value.filterNot { it.id == id }
        _rides.value = updated
        saveIndex(updated)
    }

    private fun loadIndex(): List<RideRecord> {
        if (!indexFile.exists()) return emptyList()
        return try {
            json.decodeFromString<List<RideRecord>>(indexFile.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveIndex(records: List<RideRecord>) {
        indexFile.writeText(json.encodeToString(records))
    }
}
