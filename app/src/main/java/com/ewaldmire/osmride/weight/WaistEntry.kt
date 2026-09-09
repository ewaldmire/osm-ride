package com.ewaldmire.osmride.weight

import kotlinx.serialization.Serializable

/** A single logged waist measurement (at the navel). Stored in cm, same metric-internal/
 * imperial-display convention as [WeightEntry]. The primary "visible abs" signal per the body
 * metrics feature's own spec - tracked separately from weight since a shrinking waist at a flat
 * weight is still real recomposition progress. */
@Serializable
data class WaistEntry(
    val id: String,
    val waistCm: Double,
    val recordedAtEpochMillis: Long,
)
