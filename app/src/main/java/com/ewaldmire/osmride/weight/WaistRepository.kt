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

/** Persists logged waist measurements to app-private storage - same JSON-index pattern as
 * WeightRepository, just its own subdirectory. */
class WaistRepository(context: Context) {
    private val appContext = context.applicationContext
    private val waistDir: File = File(appContext.filesDir, "waist").apply { mkdirs() }
    private val indexFile = File(waistDir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _entries = MutableStateFlow(loadIndex())
    /** Newest first. */
    val entries: StateFlow<List<WaistEntry>> = _entries.asStateFlow()

    suspend fun addEntry(waistCm: Double, recordedAtEpochMillis: Long = System.currentTimeMillis()): WaistEntry =
        withContext(Dispatchers.IO) {
            val entry = WaistEntry(
                id = UUID.randomUUID().toString(),
                waistCm = waistCm,
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

    private fun loadIndex(): List<WaistEntry> {
        if (!indexFile.exists()) return emptyList()
        return try {
            json.decodeFromString<List<WaistEntry>>(indexFile.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveIndex(entries: List<WaistEntry>) {
        indexFile.writeText(json.encodeToString(entries))
    }
}
