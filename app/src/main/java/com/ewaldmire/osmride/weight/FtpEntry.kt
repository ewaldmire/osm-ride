package com.ewaldmire.osmride.weight

import kotlinx.serialization.Serializable

/** A single logged FTP (Functional Threshold Power) test result, in watts - always a whole
 * number, unlike weight/waist which are tracked to a decimal. */
@Serializable
data class FtpEntry(
    val id: String,
    val ftpWatts: Int,
    val recordedAtEpochMillis: Long,
)
