package com.ewaldmire.osmride.ui.activities

import com.ewaldmire.osmride.ride.ActivityCategory
import com.ewaldmire.osmride.ride.RideRecord
import com.ewaldmire.osmride.strength.StrengthWorkoutEntry
import com.ewaldmire.osmride.strength.totalVolumeLbs

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

/** One row of the Activities feed's summary breakdown - a per-[ActivityCategory] rollup. Distance/
 * duration/calories are meaningless for STRENGTH (no such data is tracked for a shared-in
 * workout) - that row uses [totalWeightLiftedLbs] instead, everyone else leaves it null. */
data class CategoryStats(
    val category: ActivityCategory,
    val count: Int,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val kilocalories: Double?,
    val totalWeightLiftedLbs: Double? = null,
)

/** Foot/Strength/Wheel/Water, in that display order - see [ActivityCategory]'s own doc comment
 * for why exactly these 4 buckets and nothing else. */
fun computeCategoryBreakdown(rides: List<RideRecord>, strengthEntries: List<StrengthWorkoutEntry>): List<CategoryStats> {
    val ridesByCategory = rides.groupBy { it.activityType.category }
    fun rideStats(category: ActivityCategory): CategoryStats {
        val group = ridesByCategory[category].orEmpty()
        val kilocalories = group.mapNotNull { it.estimatedKilocalories }
        return CategoryStats(
            category = category,
            count = group.size,
            distanceMeters = group.sumOf { it.distanceMeters },
            durationSeconds = group.sumOf { it.durationSeconds },
            kilocalories = kilocalories.sum().takeIf { kilocalories.isNotEmpty() },
        )
    }
    // Real total training volume (sum of every completed set's reps x weight, see
    // LiftohistoryImport/StrengthExercise) - not just a top-set proxy.
    val totalWeightLiftedLbs = strengthEntries.sumOf { it.totalVolumeLbs }

    return listOf(
        rideStats(ActivityCategory.FOOT),
        CategoryStats(
            category = ActivityCategory.STRENGTH,
            count = strengthEntries.size,
            distanceMeters = 0.0,
            durationSeconds = 0,
            kilocalories = null,
            totalWeightLiftedLbs = totalWeightLiftedLbs,
        ),
        rideStats(ActivityCategory.WHEEL),
        rideStats(ActivityCategory.WATER),
    )
}
