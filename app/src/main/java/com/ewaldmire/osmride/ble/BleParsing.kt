package com.ewaldmire.osmride.ble

/** Little-endian primitive readers shared by the FTMS/CSC/HR characteristic parsers. */
internal fun readUInt8(data: ByteArray, offset: Int): Int =
    data[offset].toInt() and 0xFF

internal fun readUInt16LE(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

internal fun readSInt16LE(data: ByteArray, offset: Int): Int =
    readUInt16LE(data, offset).toShort().toInt()

internal fun readUInt24LE(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8) or
        ((data[offset + 2].toInt() and 0xFF) shl 16)

internal fun readUInt32LE(data: ByteArray, offset: Int): Long =
    (data[offset].toLong() and 0xFF) or
        ((data[offset + 1].toLong() and 0xFF) shl 8) or
        ((data[offset + 2].toLong() and 0xFF) shl 16) or
        ((data[offset + 3].toLong() and 0xFF) shl 24)
