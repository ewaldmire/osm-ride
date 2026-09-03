package com.ewaldmire.osmride.ble

/** One parsed sample from the trainer, either from FTMS Indoor Bike Data or derived from CSC. */
data class TrainerSample(
    val speedMetersPerSecond: Double? = null,
    val cadenceRpm: Double? = null,
    val powerWatts: Int? = null,
    /** Cumulative distance since the trainer's own counter started, when the device reports it (FTMS only). */
    val totalDistanceMeters: Double? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
)

data class HeartRateSample(
    val bpm: Int,
    val timestampMillis: Long = System.currentTimeMillis(),
)

enum class BleConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
}

data class ScannedDevice(
    val name: String,
    val address: String,
)

/** Whether the trainer is auto-adjusting resistance to match the route's simulated grade. */
enum class GradeControlState {
    /** No FTMS Control Point on this device (e.g. CSC-only trainers, or not connected). */
    UNAVAILABLE,
    REQUESTING,
    ACTIVE,
    REJECTED,
}
