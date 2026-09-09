package com.ewaldmire.osmride.ride

import kotlinx.serialization.Serializable

/** A coarse activity classification for entries in Profile's Activities feed - just enough for a
 * sensible label/icon, not a full mirror of FIT's ~80-value sport enum. Live-recorded indoor
 * rides are always CYCLING (the trainer only supports cycling); imported .fit files get their
 * type from the file's own `sport` field (see [fromFitSport]) since outdoor imports can be
 * anything a bike computer/GPS watch records - a kayaking trip imported from the same device
 * shouldn't be mislabeled as a bike ride. */
@Serializable
enum class ActivityType {
    CYCLING,
    RUNNING,
    WALKING,
    HIKING,
    SWIMMING,
    KAYAKING,
    ROWING,
    OTHER,
    ;

    /** Which of the Activities feed's 4 summary buckets this type falls into, or null for OTHER -
     * an unrecognized FIT sport (skiing, sailing, skydiving, ...) doesn't cleanly fit
     * foot/strength/wheel/water, and forcing it into one anyway (the first attempt at this used
     * WATER as a catch-all) reads as arbitrary rather than useful. Per the user's own call, OTHER
     * activities just don't count toward the breakdown at all - they still show up in the plain
     * activity feed, labeled "Activity", just excluded from this summary. */
    val category: ActivityCategory?
        get() = when (this) {
            RUNNING, WALKING, HIKING -> ActivityCategory.FOOT
            CYCLING -> ActivityCategory.WHEEL
            SWIMMING, KAYAKING, ROWING -> ActivityCategory.WATER
            OTHER -> null
        }

    companion object {
        /** [sport] is the FIT `session` message's field 5 (enum) - see FitFileParser.kt. Values
         * per the FIT SDK's `sport` enum (cross-checked against fitparse's own profile data, not
         * guessed): 1=running, 2=cycling, 5=swimming, 11=walking, 15=rowing, 17=hiking,
         * 19=paddling, 21=e_biking, 25=golf, 37=stand_up_paddleboarding, 41=kayaking. Golf maps to
         * WALKING (not a dedicated case) since "you're on foot" is the whole classification it
         * needs - same category (FOOT) either way, just reusing an existing label/icon rather than
         * adding a one-off case. Everything else (skiing, sailing, ...) falls back to OTHER, which
         * [category] excludes from the breakdown summary entirely rather than guessing a bucket. */
        fun fromFitSport(sport: Long?): ActivityType = when (sport?.toInt()) {
            1 -> RUNNING
            2, 21 -> CYCLING
            5 -> SWIMMING
            11, 25 -> WALKING
            15 -> ROWING
            17 -> HIKING
            19, 37, 41 -> KAYAKING
            else -> OTHER
        }
    }
}

/** Profile's Activities feed summary breaks activities into these 4 buckets, in this display
 * order - foot/strength/wheel/water, per the user's own framing. STRENGTH isn't reachable from
 * [ActivityType.category] since it's not a FIT sport at all - it's assigned directly to every
 * StrengthWorkoutEntry in the merged feed (see ui/activities). Activities whose [ActivityType]
 * maps to no category (see [ActivityType.category]) are simply excluded from the breakdown. */
enum class ActivityCategory { FOOT, STRENGTH, WHEEL, WATER }
