package com.ewaldmire.osmride.ble

import java.util.UUID

/** Bluetooth SIG standard service/characteristic UUIDs used by trainers and HR monitors. */
object BleConstants {
    private fun sig(shortUuid: Long): UUID =
        UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", shortUuid))

    // Fitness Machine Service (FTMS)
    val FTMS_SERVICE: UUID = sig(0x1826)
    val INDOOR_BIKE_DATA: UUID = sig(0x2AD2)

    // Cycling Speed and Cadence (CSC) — fallback for trainers without FTMS
    val CSC_SERVICE: UUID = sig(0x1816)
    val CSC_MEASUREMENT: UUID = sig(0x2A5B)

    // Heart Rate Service (HRS)
    val HEART_RATE_SERVICE: UUID = sig(0x180D)
    val HEART_RATE_MEASUREMENT: UUID = sig(0x2A37)

    // Standard Client Characteristic Configuration Descriptor, used to enable notifications.
    val CLIENT_CHARACTERISTIC_CONFIG: UUID = sig(0x2902)

    /** Default 700x25c road tire circumference, used only for the CSC-fallback distance calc. */
    const val DEFAULT_WHEEL_CIRCUMFERENCE_METERS = 2.105
}
