package com.ewaldmire.osmride.ui.map

/**
 * OpenFreeMap's "Liberty" vector style: free, no API key, built from OpenStreetMap data via the
 * OpenMapTiles schema. Includes a `building-3d` fill-extrusion layer (zoom >= 14, using each
 * building's `render_height`/`render_min_height`) so a tilted camera shows real 3D buildings.
 */
object ThreeDMapStyle {
    const val STYLE_URI = "https://tiles.openfreemap.org/styles/liberty"

    /** Required attribution text per https://openfreemap.org/quick_start/ - must stay visible on screen. */
    const val ATTRIBUTION = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap"
}
