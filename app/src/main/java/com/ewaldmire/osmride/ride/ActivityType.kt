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

    companion object {
        /** [sport] is the FIT `session` message's field 5 (enum) - see FitFileParser.kt. Values
         * per the FIT SDK's `sport` enum (cross-checked against fitparse's own profile data, not
         * guessed): 1=running, 2=cycling, 5=swimming, 11=walking, 15=rowing, 17=hiking,
         * 19=paddling, 21=e_biking, 37=stand_up_paddleboarding, 41=kayaking. Everything else
         * (golf, skiing, sailing, ...) falls back to OTHER rather than trying to cover all ~80
         * FIT sport values with a dedicated case each. */
        fun fromFitSport(sport: Long?): ActivityType = when (sport?.toInt()) {
            1 -> RUNNING
            2, 21 -> CYCLING
            5 -> SWIMMING
            11 -> WALKING
            15 -> ROWING
            17 -> HIKING
            19, 37, 41 -> KAYAKING
            else -> OTHER
        }
    }
}
