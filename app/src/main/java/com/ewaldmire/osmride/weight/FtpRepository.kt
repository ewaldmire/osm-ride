package com.ewaldmire.osmride.weight

import android.content.Context
import com.ewaldmire.osmride.ui.settings.SettingsPrefs
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persists logged FTP entries to app-private storage, same JSON-index pattern as
 * WeightRepository/WaistRepository. */
class FtpRepository(context: Context) {
    private val appContext = context.applicationContext
    private val ftpDir: File = File(appContext.filesDir, "ftp").apply { mkdirs() }
    private val indexFile = File(ftpDir, "index.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _entries = MutableStateFlow(loadIndex())
    /** Newest first. */
    val entries: StateFlow<List<FtpEntry>> = _entries.asStateFlow()

    suspend fun addEntry(ftpWatts: Int, recordedAtEpochMillis: Long = System.currentTimeMillis()): FtpEntry =
        withContext(Dispatchers.IO) {
            val entry = FtpEntry(
                id = UUID.randomUUID().toString(),
                ftpWatts = ftpWatts,
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

    /** One-time cutover from the old single-value SettingsPrefs FTP field: if there's no history
     * yet but a legacy value is set, seed it as a dated entry (timestamped "now" - the old field
     * never had a date) and clear the legacy pref, so it isn't re-seeded and there's a single
     * source of truth from here on. */
    private fun loadIndex(): List<FtpEntry> {
        val loaded = if (!indexFile.exists()) {
            emptyList()
        } else {
            try {
                json.decodeFromString<List<FtpEntry>>(indexFile.readText())
            } catch (e: Exception) {
                emptyList()
            }
        }
        if (loaded.isNotEmpty()) return loaded

        val legacyWatts = SettingsPrefs.getFtpWatts(appContext) ?: return loaded
        val migrated = listOf(
            FtpEntry(
                id = UUID.randomUUID().toString(),
                ftpWatts = legacyWatts,
                recordedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        saveIndex(migrated)
        SettingsPrefs.setFtpWatts(appContext, null)
        return migrated
    }

    private fun saveIndex(entries: List<FtpEntry>) {
        indexFile.writeText(json.encodeToString(entries))
    }
}
