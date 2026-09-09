package com.ewaldmire.osmride.strength

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

/** Persists strength workouts received from fosslift (see StrengthWorkoutImport), same
 * JSON-index pattern as WeightRepository/WorkoutRepository. Read-only from the user's
 * perspective - there's no in-app creation flow, only import. */
class StrengthWorkoutRepository(context: Context) {
    private val appContext = context.applicationContext
    private val strengthDir: File = File(appContext.filesDir, "strength_workouts").apply { mkdirs() }
    private val indexFile = File(strengthDir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _entries = MutableStateFlow(loadIndex())
    /** Newest first. */
    val entries: StateFlow<List<StrengthWorkoutEntry>> = _entries.asStateFlow()

    suspend fun addEntry(
        recordedAtEpochMillis: Long,
        workoutName: String?,
        exercises: List<StrengthExercise>,
    ): StrengthWorkoutEntry = withContext(Dispatchers.IO) {
        val entry = StrengthWorkoutEntry(
            id = UUID.randomUUID().toString(),
            recordedAtEpochMillis = recordedAtEpochMillis,
            workoutName = workoutName,
            exercises = exercises,
        )
        val updated = (_entries.value + entry).sortedByDescending { it.recordedAtEpochMillis }
        _entries.value = updated
        saveIndex(updated)
        entry
    }

    suspend fun deleteEntry(id: String) = withContext(Dispatchers.IO) {
        val updated = _entries.value.filterNot { it.id == id }
        _entries.value = updated
        saveIndex(updated)
    }

    private fun loadIndex(): List<StrengthWorkoutEntry> {
        if (!indexFile.exists()) return emptyList()
        return try {
            json.decodeFromString<List<StrengthWorkoutEntry>>(indexFile.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveIndex(entries: List<StrengthWorkoutEntry>) {
        indexFile.writeText(json.encodeToString(entries))
    }
}
