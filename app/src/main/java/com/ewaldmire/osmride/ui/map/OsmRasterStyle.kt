package com.ewaldmire.osmride.ui.map

/** Plain raster style using standard OpenStreetMap tiles — no vector style/API key needed.
 * Shared by every MapLibre view in the app. */
object OsmRasterStyle {
    const val JSON = """
{
  "version": 8,
  "sources": {
    "osm-raster": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [
    {
      "id": "osm-raster-layer",
      "type": "raster",
      "source": "osm-raster"
    }
  ]
}
"""
}
