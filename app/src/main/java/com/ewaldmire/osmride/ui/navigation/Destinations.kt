package com.ewaldmire.osmride.ui.navigation

object Destinations {
    const val PAIRING = "pairing"
    const val RIDE = "ride/{routeId}"
    const val SUMMARY = "summary"
    /** Home screen: Routes + History combined behind a tab switcher (see RideHubScreen.kt) -
     * distinct from [RIDE], the live-riding map screen. */
    const val RIDE_HUB = "ride_hub"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val WORKOUTS_LIST = "workouts_list"
    const val ROUTE_CREATOR = "route_creator?routeId={routeId}&showDerivedHint={showDerivedHint}"
    const val ROUTE_CREATOR_NEW = "route_creator"
    const val WORKOUT_CREATOR = "workout_creator?workoutId={workoutId}"
    const val WORKOUT_CREATOR_NEW = "workout_creator"
    const val WEIGHT = "weight"

    fun ride(routeId: String) = "ride/$routeId"

    /** [showDerivedHint] is true right after a plain GPX import first gets a waypoint list
     * derived from its track (see RoutesListViewModel.prepareEdit) - tells RouteCreatorScreen to
     * show a one-time notice that editing may adjust the route to follow roads. */
    fun routeCreatorEdit(routeId: String, showDerivedHint: Boolean = false) =
        "route_creator?routeId=$routeId&showDerivedHint=$showDerivedHint"
    fun workoutCreatorEdit(workoutId: String) = "workout_creator?workoutId=$workoutId"
}
