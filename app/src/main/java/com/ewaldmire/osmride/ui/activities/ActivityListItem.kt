package com.ewaldmire.osmride.ui.activities

import com.ewaldmire.osmride.ride.RideRecord
import com.ewaldmire.osmride.strength.StrengthWorkoutEntry

/** Profile's Activities feed merges two otherwise-unrelated repositories (RideRecord - cycling
 * and, since .fit imports can be anything a bike computer/GPS watch records, other outdoor
 * activities too; and StrengthWorkoutEntry - shared in from fosslift) into one sorted list purely
 * for display. Deliberately not a shared persisted model - each repository keeps its own storage
 * shape, this is just a UI-layer wrapper so one LazyColumn can render both kinds sorted together. */
sealed interface ActivityListItem {
    val epochMillis: Long

    data class Ride(val record: RideRecord) : ActivityListItem {
        override val epochMillis: Long get() = record.completedAtEpochMillis
    }

    data class Strength(val record: StrengthWorkoutEntry) : ActivityListItem {
        override val epochMillis: Long get() = record.recordedAtEpochMillis
    }
}

fun mergeActivities(rides: List<RideRecord>, strengthEntries: List<StrengthWorkoutEntry>): List<ActivityListItem> =
    (rides.map { ActivityListItem.Ride(it) } + strengthEntries.map { ActivityListItem.Strength(it) })
        .sortedByDescending { it.epochMillis }
