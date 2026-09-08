package com.ewaldmire.osmride.weight

import kotlinx.serialization.Serializable

/** A single logged body-weight measurement. Stored in kg (the app displays imperial units
 * throughout, per Units.kt, but keeps metric internally - same convention as route distances). */
@Serializable
data class WeightEntry(
    val id: String,
    val weightKg: Double,
    val recordedAtEpochMillis: Long,
)
