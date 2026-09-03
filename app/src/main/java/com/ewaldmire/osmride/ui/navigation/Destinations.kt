package com.ewaldmire.osmride.ui.navigation

object Destinations {
    const val ROUTES_LIST = "routes_list"
    const val PAIRING = "pairing"
    const val RIDE = "ride/{routeId}"
    const val SUMMARY = "summary"
    /** Home screen: ride history + overview, with New Ride / Settings in its bottom bar. */
    const val HISTORY = "history"
    const val SETTINGS = "settings"

    fun ride(routeId: String) = "ride/$routeId"
}
