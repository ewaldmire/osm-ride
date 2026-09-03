package com.ewaldmire.osmride.ui.navigation

object Destinations {
    const val ROUTES_LIST = "routes_list"
    const val PAIRING = "pairing"
    const val RIDE = "ride/{routeId}"
    const val SUMMARY = "summary"
    const val HISTORY = "history"

    fun ride(routeId: String) = "ride/$routeId"
}
