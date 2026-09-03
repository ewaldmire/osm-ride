package com.ewaldmire.osmride.ride

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

/**
 * Imports and persists structured (ERG-mode) workouts: .erg (absolute watts), .mrc (%FTP), and
 * .zwo (Zwift XML, %FTP). Workout segment lists are small, so unlike routes the full parsed
 * workout is kept directly in the index rather than needing a separate lazy-loaded file per
 * workout.
 */
class WorkoutRepository(context: Context) {
    private val appContext = context.applicationContext
    private val workoutsDir: File = File(appContext.filesDir, "workouts").apply { mkdirs() }
    private val indexFile = File(workoutsDir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _workouts = MutableStateFlow(loadIndex())
    val workouts: StateFlow<List<Workout>> = _workouts.asStateFlow()

    /** [displayName] should include the file extension (used to pick the right parser). */
    suspend fun importWorkout(uri: Uri, displayName: String?, ftpWatts: Int?): Result<Workout> =
        withContext(Dispatchers.IO) {
            try {
                val text = appContext.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                } ?: return@withContext Result.failure(IOException("Could not open $uri"))

                val lowerName = (displayName ?: "").lowercase()
                val fallbackName = displayName?.substringBeforeLast(".")?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: "Imported Workout"

                val parsed = when {
                    lowerName.endsWith(".mrc") ->
                        ErgWorkoutParser.parse(text, isPercentBased = true, ftpWatts = ftpWatts, fallbackName = fallbackName)
                    lowerName.endsWith(".zwo") ->
                        ZwoWorkoutParser.parse(text.byteInputStream(), ftpWatts = ftpWatts, fallbackName = fallbackName)
                    else ->
                        // .erg, or unrecognized extension - assume the common case, absolute watts.
                        ErgWorkoutParser.parse(text, isPercentBased = false, ftpWatts = ftpWatts, fallbackName = fallbackName)
                }

                if (parsed.segments.isEmpty()) {
                    return@withContext Result.failure(IllegalArgumentException("Workout file has no usable intervals"))
                }

                val workout = Workout(
                    id = UUID.randomUUID().toString(),
                    name = parsed.name,
                    segments = parsed.segments,
                    totalDurationSeconds = parsed.totalDurationSeconds,
                )
                val updated = _workouts.value + workout
                _workouts.value = updated
                saveIndex(updated)
                Result.success(workout)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun getWorkout(id: String): Workout? = _workouts.value.find { it.id == id }

    /** Saves a workout built (or edited) in-app via the block-based workout creator. Pass
     * [existingId] when re-editing an already-saved workout so it updates in place. */
    suspend fun saveCreatedWorkout(
        existingId: String?,
        name: String,
        segments: List<WorkoutSegment>,
    ): Result<Workout> = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Workout has no intervals"))
        }
        val id = existingId ?: UUID.randomUUID().toString()
        val workout = Workout(
            id = id,
            name = name.trim().ifEmpty { "New Workout" },
            segments = segments,
            totalDurationSeconds = segments.maxOf { it.endSeconds },
        )
        val exists = _workouts.value.any { it.id == id }
        val updated = if (exists) {
            _workouts.value.map { if (it.id == id) workout else it }
        } else {
            _workouts.value + workout
        }
        _workouts.value = updated
        saveIndex(updated)
        Result.success(workout)
    }

    suspend fun renameWorkout(id: String, name: String) = withContext(Dispatchers.IO) {
        val resolved = name.ifBlank { return@withContext }
        val updated = _workouts.value.map { if (it.id == id) it.copy(name = resolved) else it }
        _workouts.value = updated
        saveIndex(updated)
    }

    suspend fun deleteWorkout(id: String) = withContext(Dispatchers.IO) {
        val updated = _workouts.value.filterNot { it.id == id }
        _workouts.value = updated
        saveIndex(updated)
    }

    private fun loadIndex(): List<Workout> {
        if (!indexFile.exists()) return emptyList()
        return try {
            json.decodeFromString<List<Workout>>(indexFile.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveIndex(workouts: List<Workout>) {
        indexFile.writeText(json.encodeToString(workouts))
    }
}
