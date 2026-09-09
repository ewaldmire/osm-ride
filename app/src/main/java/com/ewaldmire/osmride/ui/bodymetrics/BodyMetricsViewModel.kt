package com.ewaldmire.osmride.ui.bodymetrics

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ewaldmire.osmride.OsmRideApp
import com.ewaldmire.osmride.ui.settings.SettingsPrefs
import com.ewaldmire.osmride.util.Units
import com.ewaldmire.osmride.weight.NavyBodyFat
import com.ewaldmire.osmride.weight.WaistEntry
import com.ewaldmire.osmride.weight.WeightEntry
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BodyMetricsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OsmRideApp
    private val weightRepository = app.weightRepository
    private val waistRepository = app.waistRepository

    /** Newest first. */
    val weightEntries: StateFlow<List<WeightEntry>> = weightRepository.entries

    /** Newest first. */
    val waistEntries: StateFlow<List<WaistEntry>> = waistRepository.entries

    fun addWeightEntry(weightLbs: Double, recordedAtEpochMillis: Long) {
        viewModelScope.launch { weightRepository.addEntry(Units.lbsToKg(weightLbs), recordedAtEpochMillis) }
    }

    fun deleteWeightEntry(id: String) {
        viewModelScope.launch { weightRepository.deleteEntry(id) }
    }

    fun addWaistEntry(waistCm: Double, recordedAtEpochMillis: Long) {
        viewModelScope.launch { waistRepository.addEntry(waistCm, recordedAtEpochMillis) }
    }

    fun deleteWaistEntry(id: String) {
        viewModelScope.launch { waistRepository.deleteEntry(id) }
    }

    // Neck/height are one-time-ish profile inputs, not tracked trends - plain SharedPreferences
    // reads/writes are enough, same as FTP on Profile.
    fun getNeckCm(): Double? = SettingsPrefs.getNeckCm(app)

    fun setNeckCm(cm: Double?) {
        SettingsPrefs.setNeckCm(app, cm)
    }

    fun getHeightInches(): Double? = SettingsPrefs.getHeightCm(app)?.let { Units.cmToInches(it) }

    fun setHeightInches(inches: Double?) {
        SettingsPrefs.setHeightCm(app, inches?.let { Units.inchesToCm(it) })
    }

    /** Null if there's no waist entry yet, or neck/height haven't been entered. */
    fun estimateBodyFatPercent(): Double? {
        val waistCm = waistEntries.value.firstOrNull()?.waistCm ?: return null
        val neckCm = SettingsPrefs.getNeckCm(app) ?: return null
        val heightCm = SettingsPrefs.getHeightCm(app) ?: return null
        return NavyBodyFat.estimatePercent(waistCm, neckCm, heightCm)
    }
}

/** In display units (lb) - the chart itself is unit-agnostic, it just plots values. Takes the
 * already-collected list rather than reading the ViewModel's StateFlow directly, so it stays
 * correctly tied to Compose recomposition instead of silently reading a stale/fresh value
 * depending on what else happens to be collected in the same composable. */
fun weightTrendPoints(entries: List<WeightEntry>): List<TrendPoint> =
    entries.map { TrendPoint(it.recordedAtEpochMillis, Units.kgToLbs(it.weightKg)) }

/** In display units (cm) - see [weightTrendPoints]. */
fun waistTrendPoints(entries: List<WaistEntry>): List<TrendPoint> =
    entries.map { TrendPoint(it.recordedAtEpochMillis, it.waistCm) }

/** [previousAvg] is null when there's no data from 7-14 days ago yet (e.g. brand new tracking) -
 * callers should show "not enough data yet" rather than a misleading delta in that case. */
data class WeeklyDelta(val currentAvg: Double, val previousAvg: Double?) {
    val delta: Double? get() = previousAvg?.let { currentAvg - it }
}

/** A trailing weekly comparison, not a rolling daily one - weighing in daily and reacting to
 * every wiggle is exactly the noise-driven anxiety a weekly check-in is meant to avoid. */
fun weeklyDelta(points: List<TrendPoint>, nowMillis: Long = System.currentTimeMillis()): WeeklyDelta? {
    val dayMillis = 24L * 60 * 60 * 1000
    val currentWindow = points.filter { it.epochMillis >= nowMillis - 7 * dayMillis }
    if (currentWindow.isEmpty()) return null
    val currentAvg = currentWindow.map { it.value }.average()
    val previousWindowStart = nowMillis - 14 * dayMillis
    val previousWindowEnd = nowMillis - 7 * dayMillis
    val previousWindow = points.filter { it.epochMillis in previousWindowStart until previousWindowEnd }
    val previousAvg = if (previousWindow.isNotEmpty()) previousWindow.map { it.value }.average() else null
    return WeeklyDelta(currentAvg, previousAvg)
}
