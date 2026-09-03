package com.ewaldmire.osmride.ride

import kotlinx.serialization.Serializable

/** Persisted summary of a completed ride, shown in the ride history list. */
@Serializable
data class RideRecord(
    val id: String,
    val routeName: String,
    val completedAtEpochMillis: Long,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val avgSpeedMps: Double,
    val avgPowerWatts: Double?,
    val avgCadenceRpm: Double?,
    val avgHeartRateBpm: Double?,
    val gpxFileName: String,
)
