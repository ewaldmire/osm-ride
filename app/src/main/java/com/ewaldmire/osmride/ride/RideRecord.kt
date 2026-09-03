package com.ewaldmire.osmride.ride

import kotlinx.serialization.Serializable

/**
 * Persisted summary of a completed ride, shown in the ride history list.
 *
 * [title] and [notes] were added after the first release; both have defaults so
 * kotlinx.serialization treats them as optional and rides saved before this change (missing
 * those keys in their stored JSON) still load instead of being dropped.
 */
@Serializable
data class RideRecord(
    val id: String,
    val routeName: String,
    val title: String = routeName,
    val notes: String = "",
    val completedAtEpochMillis: Long,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val avgSpeedMps: Double,
    val avgPowerWatts: Double?,
    val avgCadenceRpm: Double?,
    val avgHeartRateBpm: Double?,
    /** Rough estimate from mechanical work (avg power x duration) at ~24% gross cycling
     * efficiency, which conveniently makes kcal ~= kJ of work. Null when there's no power data
     * to compute it from (e.g. a CSC-only trainer with no power meter). */
    val estimatedKilocalories: Double? = null,
    val gpxFileName: String,
)
