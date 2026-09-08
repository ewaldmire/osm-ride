package com.ewaldmire.osmride.weight

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

/** Persists logged body-weight entries to app-private storage, same JSON-index pattern as
 * RouteRepository/RideHistoryRepository. */
class WeightRepository(context: Context) {
    private val appContext = context.applicationContext
    private val weightDir: File = File(appContext.filesDir, "weight").apply { mkdirs() }
    private val indexFile = File(weightDir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _entries = MutableStateFlow(loadIndex())
    /** Newest first. */
    val entries: StateFlow<List<WeightEntry>> = _entries.asStateFlow()

    suspend fun addEntry(weightKg: Double, recordedAtEpochMillis: Long = System.currentTimeMillis()): WeightEntry =
        withContext(Dispatchers.IO) {
            val entry = WeightEntry(
                id = UUID.randomUUID().toString(),
                weightKg = weightKg,
                recordedAtEpochMillis = recordedAtEpochMillis,
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

    private fun loadIndex(): List<WeightEntry> {
        if (!indexFile.exists()) return emptyList()
        return try {
            json.decodeFromString<List<WeightEntry>>(indexFile.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveIndex(entries: List<WeightEntry>) {
        indexFile.writeText(json.encodeToString(entries))
    }
}
