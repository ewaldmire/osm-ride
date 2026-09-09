package com.ewaldmire.osmride.strength

import kotlinx.serialization.Serializable

/** One exercise within a received strength workout. osm-ride doesn't own strength-training data;
 * fosslift does, and shares a summary here - not a full per-rep log (fosslift's liftohistory text
 * doesn't carry that either, only completed-set groups: "3x8 185lb" = 3 sets of 8 reps), but
 * [totalSets]/[totalReps]/[totalVolumeLbs] cover every completed set for this exercise, not just
 * the top one. [topSetReps]/[topSetWeightLbs] are kept alongside for "your best set" display.
 * Warmup and target (planned, not actual) sets are excluded from all of these - see
 * LiftohistoryImport.parseExerciseLine.
 *
 * [totalSets]/[totalReps]/[totalVolumeLbs] default to 0/0/0.0 for backward compatibility with
 * workouts imported before these fields existed (top-set-only data, no way to recover the totals
 * retroactively). */
@Serializable
data class StrengthExercise(
    val name: String,
    val topSetReps: Int,
    val topSetWeightLbs: Double,
    val totalSets: Int = 0,
    val totalReps: Int = 0,
    val totalVolumeLbs: Double = 0.0,
)

@Serializable
data class StrengthWorkoutEntry(
    val id: String,
    val recordedAtEpochMillis: Long,
    val workoutName: String?,
    val exercises: List<StrengthExercise>,
)

/** Computed rather than stored, so it's always consistent with [StrengthWorkoutEntry.exercises] -
 * no risk of a persisted total drifting from the per-exercise data it's derived from. */
val StrengthWorkoutEntry.totalReps: Int get() = exercises.sumOf { it.totalReps }
val StrengthWorkoutEntry.totalVolumeLbs: Double get() = exercises.sumOf { it.totalVolumeLbs }
