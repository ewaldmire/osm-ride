package com.ewaldmire.osmride.ui.navigation

object Destinations {
    const val ROUTES_LIST = "routes_list"
    const val PAIRING = "pairing"
    const val RIDE = "ride/{routeId}"
    const val SUMMARY = "summary"
    /** Home screen: ride history + overview, with New Ride / Settings in its bottom bar. */
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val WORKOUTS_LIST = "workouts_list"
    const val ROUTE_CREATOR = "route_creator?routeId={routeId}"
    const val ROUTE_CREATOR_NEW = "route_creator"
    const val WORKOUT_CREATOR = "workout_creator?workoutId={workoutId}"
    const val WORKOUT_CREATOR_NEW = "workout_creator"

    fun ride(routeId: String) = "ride/$routeId"
    fun routeCreatorEdit(routeId: String) = "route_creator?routeId=$routeId"
    fun workoutCreatorEdit(workoutId: String) = "workout_creator?workoutId=$workoutId"
}
