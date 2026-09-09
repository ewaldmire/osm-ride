package com.ewaldmire.osmride.strength

import android.net.Uri

/**
 * Parses the deep link fosslift uses to share a completed strength workout into osm-ride:
 *
 *   osmride://import-workout?data=<url-encoded liftohistory text>
 *
 * The payload is fosslift's own "Copy as Text" export (see [LiftohistoryImport]), not a format
 * invented for this integration - osm-ride reads what fosslift already produces.
 */
object StrengthWorkoutImport {
    private const val SCHEME = "osmride"
    private const val HOST = "import-workout"

    fun parse(uri: Uri): ImportedStrengthWorkout? {
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        // Uri.getQueryParameter already URL-decodes the value.
        val data = uri.getQueryParameter("data") ?: return null
        return LiftohistoryImport.parse(data)
    }
}
