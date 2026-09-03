package com.ewaldmire.osmride.ride

import kotlinx.serialization.Serializable

/**
 * One linear power ramp from [startSeconds] to [endSeconds]. A flat interval has
 * startWatts == endWatts. Null watts means "free ride" / "max effort" - no ERG target is sent
 * to the trainer for that stretch (matches Zwift's FreeRide/MaxEffort blocks, which have no
 * fixed power).
 */
@Serializable
data class WorkoutSegment(
    val startSeconds: Long,
    val endSeconds: Long,
    val startWatts: Int?,
    val endWatts: Int?,
)

/** A structured (ERG-mode) workout: a target-power timeline, independent of any route. */
@Serializable
data class Workout(
    val id: String,
    val name: String,
    val segments: List<WorkoutSegment>,
    val totalDurationSeconds: Long,
)

/** Parser output before an id has been assigned by the repository. */
data class ParsedWorkout(
    val name: String,
    val segments: List<WorkoutSegment>,
    val totalDurationSeconds: Long,
)
