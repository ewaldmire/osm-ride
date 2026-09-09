package com.ewaldmire.osmride.strength

import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parses the deep link fosslift uses to share a completed strength workout into osm-ride:
 *
 *   osmride://import-workout?data=<url-encoded JSON>
 *
 * where the JSON matches [ImportedStrengthWorkout], e.g.:
 * ```
 * {
 *   "recordedAtEpochMillis": 1234567890123,
 *   "workoutName": "Push Day",
 *   "exercises": [
 *     {"name": "Bench Press", "topSetReps": 8, "topSetWeightLbs": 185.0}
 *   ]
 * }
 * ```
 * No [StrengthWorkoutEntry.id] in the payload - one is assigned locally on import.
 */
object StrengthWorkoutImport {
    private const val SCHEME = "osmride"
    private const val HOST = "import-workout"
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(uri: Uri): ImportedStrengthWorkout? {
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        // Uri.getQueryParameter already URL-decodes the value.
        val data = uri.getQueryParameter("data") ?: return null
        return try {
            json.decodeFromString<ImportedStrengthWorkout>(data)
        } catch (e: Exception) {
            null
        }
    }
}

@Serializable
data class ImportedStrengthWorkout(
    val recordedAtEpochMillis: Long,
    val workoutName: String? = null,
    val exercises: List<StrengthExercise>,
)
