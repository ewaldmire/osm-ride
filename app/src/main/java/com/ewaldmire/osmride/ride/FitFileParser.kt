package com.ewaldmire.osmride.ride

/**
 * A minimal hand-rolled parser for Garmin's FIT binary format (as used by bike computers, GPS
 * watches, and exports from Strava/RideWithGPS/etc. for outdoor rides) - same "write our own
 * small parser instead of pulling in a dependency" approach as ErgWorkoutParser/ZwoWorkoutParser
 * take for their text formats. FIT itself is binary and more involved than those, but the actual
 * format (definition messages describing field layout, followed by data messages using that
 * layout) is well-documented and widely reimplemented from scratch (e.g. Python's `fitparse`,
 * which this follows the same shape as).
 *
 * Only the two message types and fields an outdoor cycling history entry actually needs are
 * decoded - `record` (per-second GPS/sensor samples) and `session` (ride-level summary). Every
 * other global message type is walked past using its own definition's field sizes, not
 * interpreted - this keeps the parser small without needing to special-case whatever
 * device/app-specific messages a given file happens to contain.
 *
 * Multi-lap/multi-sport files are not specially handled: this reads every `record` regardless of
 * lap/session boundaries and takes the first `session` message's summary fields (falling back to
 * computing them from records if absent) - single-session outdoor rides are the overwhelming
 * common case for "import my outdoor ride."
 */
object FitFileParser {
    private const val GLOBAL_RECORD = 20
    private const val GLOBAL_SESSION = 18

    // FIT epoch is 1989-12-31T00:00:00Z, this many seconds after the Unix epoch.
    private const val FIT_EPOCH_OFFSET_SECONDS = 631065600L
    private const val SEMICIRCLE_TO_DEGREES = 180.0 / 2147483648.0 // 180 / 2^31

    fun parse(bytes: ByteArray): FitRideSummary? {
        return try {
            parseInternal(bytes)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseInternal(bytes: ByteArray): FitRideSummary? {
        if (bytes.size < 12) return null
        val headerSize = bytes[0].toInt() and 0xFF
        if (headerSize < 12 || bytes.size < headerSize) return null
        // Signature check (".FIT" at bytes 8-11) - the one thing worth validating up front, since
        // a wrong file type should cleanly no-op rather than fail deep inside record parsing.
        if (bytes[8] != '.'.code.toByte() || bytes[9] != 'F'.code.toByte() ||
            bytes[10] != 'I'.code.toByte() || bytes[11] != 'T'.code.toByte()
        ) {
            return null
        }
        val dataSize = readUInt32LE(bytes, 4)
        val dataEnd = (headerSize + dataSize).coerceAtMost(bytes.size.toLong()).toInt()

        val definitions = HashMap<Int, FitDefinition>()
        val records = mutableListOf<FitRecordFields>()
        var session: FitRecordFields? = null

        var pos = headerSize
        while (pos < dataEnd) {
            val recordHeader = bytes[pos].toInt() and 0xFF
            pos += 1
            if (recordHeader and 0x80 != 0) {
                // Compressed-timestamp header (legacy, rare in modern outdoor-ride exports) - not
                // worth supporting for a personal-use importer; bail out to null rather than
                // silently mis-parsing the rest of the file.
                return if (records.isNotEmpty() || session != null) {
                    buildSummary(records, session)
                } else {
                    null
                }
            }
            val isDefinition = recordHeader and 0x40 != 0
            val localType = recordHeader and 0x0F

            if (isDefinition) {
                val hasDeveloperFields = recordHeader and 0x20 != 0
                pos += 1 // reserved byte
                val bigEndian = bytes[pos] == 1.toByte()
                pos += 1
                val globalMessageNumber = if (bigEndian) {
                    readUInt16BE(bytes, pos)
                } else {
                    readUInt16LE(bytes, pos)
                }
                pos += 2
                val fieldCount = bytes[pos].toInt() and 0xFF
                pos += 1
                val fields = ArrayList<FitFieldDef>(fieldCount)
                repeat(fieldCount) {
                    val fieldNumber = bytes[pos].toInt() and 0xFF
                    val size = bytes[pos + 1].toInt() and 0xFF
                    val baseType = bytes[pos + 2].toInt() and 0xFF
                    fields.add(FitFieldDef(fieldNumber, size, baseType))
                    pos += 3
                }
                if (hasDeveloperFields) {
                    val devFieldCount = bytes[pos].toInt() and 0xFF
                    pos += 1
                    // Developer fields have a device-specific meaning we can't interpret without
                    // their accompanying field-description messages - skip their bytes in data
                    // messages by still recording a size, just with a base type we treat as opaque.
                    repeat(devFieldCount) {
                        val size = bytes[pos + 1].toInt() and 0xFF
                        fields.add(FitFieldDef(fieldNumber = -1, size = size, baseType = -1))
                        pos += 3
                    }
                }
                definitions[localType] = FitDefinition(globalMessageNumber, bigEndian, fields)
            } else {
                val definition = definitions[localType] ?: return buildSummary(records, session)
                val fieldValues = HashMap<Int, Long>()
                for (field in definition.fields) {
                    if (field.fieldNumber < 0 || field.baseType < 0) {
                        pos += field.size // opaque/developer field - skip its bytes
                        continue
                    }
                    val value = readField(bytes, pos, field.baseType, field.size, definition.bigEndian)
                    if (value != null) fieldValues[field.fieldNumber] = value
                    pos += field.size
                }
                when (definition.globalMessageNumber) {
                    GLOBAL_RECORD -> records.add(FitRecordFields(fieldValues))
                    GLOBAL_SESSION -> if (session == null) session = FitRecordFields(fieldValues)
                }
            }
        }
        return buildSummary(records, session)
    }

    private fun buildSummary(records: List<FitRecordFields>, session: FitRecordFields?): FitRideSummary? {
        if (records.isEmpty()) return null

        val points = records.mapNotNull { r ->
            val timestampRaw = r.values[253] ?: return@mapNotNull null
            val lat = r.values[0]?.let { it * SEMICIRCLE_TO_DEGREES }
            val lon = r.values[1]?.let { it * SEMICIRCLE_TO_DEGREES }
            FitRidePoint(
                timestampMillis = (timestampRaw + FIT_EPOCH_OFFSET_SECONDS) * 1000,
                lat = lat,
                lon = lon,
                elevationMeters = r.values[2]?.let { it / 5.0 - 500.0 },
                heartRateBpm = r.values[3]?.takeIf { it != 0xFFL }?.toInt(),
                cadenceRpm = r.values[4]?.takeIf { it != 0xFFL }?.toDouble(),
                powerWatts = r.values[7]?.takeIf { it != 0xFFFFL }?.toInt(),
                cumulativeDistanceMeters = r.values[5]?.let { it / 100.0 },
            )
        }
        if (points.isEmpty()) return null

        val startEpochMillis = points.first().timestampMillis
        val endEpochMillis = points.last().timestampMillis
        val computedDurationSeconds = ((endEpochMillis - startEpochMillis) / 1000).coerceAtLeast(0)
        val computedDistanceMeters = points.mapNotNull { it.cumulativeDistanceMeters }.maxOrNull() ?: 0.0

        val durationSeconds = session?.values?.get(7)?.let { it / 1000 } ?: computedDurationSeconds
        val distanceMeters = session?.values?.get(9)?.let { it / 100.0 } ?: computedDistanceMeters
        val avgSpeedMps = session?.values?.get(14)?.takeIf { it != 0xFFFFL }?.let { it / 1000.0 }
            ?: if (durationSeconds > 0) distanceMeters / durationSeconds else 0.0
        val avgPowerWatts = session?.values?.get(20)?.takeIf { it != 0xFFFFL }?.toDouble()
            ?: points.mapNotNull { it.powerWatts }.takeIf { it.isNotEmpty() }?.average()
        val avgCadenceRpm = session?.values?.get(18)?.takeIf { it != 0xFFL }?.toDouble()
            ?: points.mapNotNull { it.cadenceRpm }.takeIf { it.isNotEmpty() }?.average()
        val avgHeartRateBpm = session?.values?.get(16)?.takeIf { it != 0xFFL }?.toDouble()
            ?: points.mapNotNull { it.heartRateBpm }.takeIf { it.isNotEmpty() }?.map { it.toDouble() }?.average()
        // total_calories, when present, comes from the recording device's own HR/profile-based
        // estimate - more accurate for outdoor riding than the mechanical-power-only estimate
        // RideStats.estimatedKilocalories uses for live indoor rides (which has no HR-based model
        // to fall back on), so prefer it outright rather than recomputing.
        val totalCalories = session?.values?.get(11)?.takeIf { it != 0xFFFFL }?.toDouble()
            ?: avgPowerWatts?.let { it * durationSeconds / 1000.0 }

        return FitRideSummary(
            startEpochMillis = startEpochMillis,
            endEpochMillis = endEpochMillis,
            durationSeconds = durationSeconds,
            distanceMeters = distanceMeters,
            avgSpeedMps = avgSpeedMps,
            avgPowerWatts = avgPowerWatts,
            avgCadenceRpm = avgCadenceRpm,
            avgHeartRateBpm = avgHeartRateBpm,
            totalCalories = totalCalories,
            points = points,
        )
    }

    /** Returns null for a field whose raw bytes are all the base type's "invalid" sentinel, or a
     * base type this parser doesn't decode (string/byte-array/float - none of the fields read
     * above use those, so this only ever discards fields nothing here looks at). */
    private fun readField(bytes: ByteArray, pos: Int, baseType: Int, size: Int, bigEndian: Boolean): Long? {
        return when (baseType) {
            0x00, 0x02, 0x0A -> { // enum, uint8, uint8z
                val v = bytes[pos].toInt() and 0xFF
                if (v == 0xFF) null else v.toLong()
            }
            0x01 -> { // sint8
                val v = bytes[pos].toInt()
                if (v.toByte() == 0x7F.toByte()) null else v.toLong()
            }
            0x84, 0x8B -> { // uint16, uint16z
                val v = if (bigEndian) readUInt16BE(bytes, pos) else readUInt16LE(bytes, pos)
                if (v == 0xFFFF) null else v.toLong()
            }
            0x83 -> { // sint16
                val raw = if (bigEndian) readUInt16BE(bytes, pos) else readUInt16LE(bytes, pos)
                val v = raw.toShort().toInt()
                if (v == 0x7FFF) null else v.toLong()
            }
            0x86, 0x8C -> { // uint32, uint32z
                val v = if (bigEndian) readUInt32BE(bytes, pos) else readUInt32LE(bytes, pos)
                if (v == 0xFFFFFFFFL) null else v
            }
            0x85 -> { // sint32
                val raw = if (bigEndian) readUInt32BE(bytes, pos) else readUInt32LE(bytes, pos)
                val v = raw.toInt()
                if (v == 0x7FFFFFFF) null else v.toLong()
            }
            else -> null // string, byte array, float, sint64/uint64 - unused by any field read above
        }.takeIf { size > 0 }
    }

    private fun readUInt16LE(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun readUInt16BE(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun readUInt32LE(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or
            ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 24)

    private fun readUInt32BE(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)

    private data class FitFieldDef(val fieldNumber: Int, val size: Int, val baseType: Int)
    private data class FitDefinition(val globalMessageNumber: Int, val bigEndian: Boolean, val fields: List<FitFieldDef>)
    private data class FitRecordFields(val values: Map<Int, Long>)
}

data class FitRidePoint(
    val timestampMillis: Long,
    val lat: Double?,
    val lon: Double?,
    val elevationMeters: Double?,
    val heartRateBpm: Int?,
    val cadenceRpm: Double?,
    val powerWatts: Int?,
    val cumulativeDistanceMeters: Double?,
)

data class FitRideSummary(
    val startEpochMillis: Long,
    /** The ride's own last recorded timestamp - used as [com.ewaldmire.osmride.ride.RideRecord]'s
     * completedAtEpochMillis, consistent with how a live ride is saved right as it finishes. */
    val endEpochMillis: Long,
    val durationSeconds: Long,
    val distanceMeters: Double,
    val avgSpeedMps: Double,
    val avgPowerWatts: Double?,
    val avgCadenceRpm: Double?,
    val avgHeartRateBpm: Double?,
    val totalCalories: Double?,
    val points: List<FitRidePoint>,
)
