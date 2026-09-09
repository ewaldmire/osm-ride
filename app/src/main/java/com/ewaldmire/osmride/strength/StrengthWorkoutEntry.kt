package com.ewaldmire.osmride.strength

import kotlinx.serialization.Serializable

/** One exercise within a received strength workout - just enough to show progress at a glance
 * (top set), not a full per-set breakdown. osm-ride doesn't own strength-training data; fosslift
 * does, and shares a lightweight summary here. */
@Serializable
data class StrengthExercise(
    val name: String,
    val topSetReps: Int,
    val topSetWeightLbs: Double,
)

@Serializable
data class StrengthWorkoutEntry(
    val id: String,
    val recordedAtEpochMillis: Long,
    val workoutName: String?,
    val exercises: List<StrengthExercise>,
)
