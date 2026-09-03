package com.ewaldmire.osmride.ride

import com.ewaldmire.osmride.ble.HeartRateSample
import com.ewaldmire.osmride.ble.TrainerSample
import com.ewaldmire.osmride.route.Route
import com.ewaldmire.osmride.util.Haversine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RideState { IDLE, RIDING, PAUSED, FINISHED }

data class RidePosition(
    val lat: Double,
    val lon: Double,
    val elevationMeters: Double?,
    val bearingDegrees: Double,
)

data class RideStats(
    val state: RideState = RideState.IDLE,
    val distanceMeters: Double = 0.0,
    val totalDistanceMeters: Double = 0.0,
    val progressFraction: Double = 0.0,
    val position: RidePosition? = null,
    val elapsedSeconds: Long = 0,
    val currentSpeedMps: Double = 0.0,
    val currentCadenceRpm: Double? = null,
    val currentPowerWatts: Int? = null,
    val currentHeartRateBpm: Int? = null,
    /** Average grade, as a percent, over a short window around the current position. */
    val currentGradePercent: Double? = null,
    val avgSpeedMps: Double = 0.0,
    val avgPowerWatts: Double? = null,
    val avgCadenceRpm: Double? = null,
    val avgHeartRateBpm: Double? = null,
) {
    /** Rough estimate from mechanical work (avg power x duration) at ~24% gross cycling
     * efficiency, which conveniently makes kcal ~= kJ of work. Null without power data to
     * compute it from (e.g. a CSC-only trainer with no power meter). */
    val estimatedKilocalories: Double?
        get() = avgPowerWatts?.let { it * elapsedSeconds / 1000.0 }
}

data class RecordedTrackPoint(
    val timestampMillis: Long,
    val lat: Double,
    val lon: Double,
    val elevationMeters: Double?,
    val heartRateBpm: Int?,
    val cadenceRpm: Double?,
)

/**
 * Turns live trainer/HR samples into progress along a preloaded [route]: cumulative distance,
 * an interpolated avatar position + bearing, live/average stats, and a recorded track for
 * export. One instance is used per ride attempt.
 */
class RideEngine(val route: Route) {
    private val _stats = MutableStateFlow(RideStats(totalDistanceMeters = route.totalDistanceMeters))
    val stats: StateFlow<RideStats> = _stats.asStateFlow()

    private val recordedPoints = mutableListOf<RecordedTrackPoint>()
    fun trackPointsSnapshot(): List<RecordedTrackPoint> = recordedPoints.toList()

    private var distanceMeters = 0.0
    private var ftmsBaselineDistance: Double? = null
    private var usingFtmsDistance = false
    private var lastSampleTimestamp: Long? = null
    private var lastRecordedTimestamp = 0L

    private var elapsedMillis = 0L
    private var lastTickTimestamp: Long? = null

    private var speedSum = 0.0
    private var speedSamples = 0
    private var powerSum = 0.0
    private var powerSamples = 0
    private var cadenceSum = 0.0
    private var cadenceSamples = 0
    private var hrSum = 0.0
    private var hrSamples = 0

    private var latestSpeed = 0.0
    private var latestCadence: Double? = null
    private var latestPower: Int? = null
    private var latestHeartRate: Int? = null

    /** Starts a fresh ride, or resumes one paused with [pause]. */
    fun start() {
        val current = _stats.value.state
        if (current == RideState.IDLE || current == RideState.PAUSED) {
            lastTickTimestamp = System.currentTimeMillis()
            lastSampleTimestamp = null // avoid a huge distance jump from the paused gap
            _stats.value = _stats.value.copy(state = RideState.RIDING)
        }
    }

    fun pause() {
        if (_stats.value.state == RideState.RIDING) {
            lastTickTimestamp = null
            _stats.value = _stats.value.copy(state = RideState.PAUSED)
        }
    }

    /** Ends the ride early, before the route distance is completed. */
    fun finishManually() {
        if (_stats.value.state == RideState.RIDING || _stats.value.state == RideState.PAUSED) {
            lastTickTimestamp = null
            _stats.value = _stats.value.copy(state = RideState.FINISHED)
        }
    }

    fun onTrainerSample(sample: TrainerSample) {
        if (_stats.value.state != RideState.RIDING) return
        val now = sample.timestampMillis

        // The trainer's own cumulative distance counter (FTMS) is more accurate than
        // integrating instantaneous speed across noisy BLE notification gaps, so prefer it.
        if (sample.totalDistanceMeters != null) {
            if (ftmsBaselineDistance == null) ftmsBaselineDistance = sample.totalDistanceMeters
            usingFtmsDistance = true
            val delta = sample.totalDistanceMeters - (ftmsBaselineDistance ?: 0.0)
            distanceMeters = maxOf(distanceMeters, delta)
        } else if (!usingFtmsDistance) {
            val prevTs = lastSampleTimestamp
            val speed = sample.speedMetersPerSecond
            if (prevTs != null && speed != null) {
                val dtSeconds = (now - prevTs) / 1000.0
                if (dtSeconds in 0.0..5.0) { // ignore gaps from reconnects etc.
                    distanceMeters += speed * dtSeconds
                }
            }
        }
        lastSampleTimestamp = now

        sample.speedMetersPerSecond?.let {
            latestSpeed = it
            speedSum += it
            speedSamples++
        }
        sample.powerWatts?.let {
            latestPower = it
            powerSum += it
            powerSamples++
        }
        sample.cadenceRpm?.let {
            latestCadence = it
            cadenceSum += it
            cadenceSamples++
        }

        recordPointIfDue(now)
        publishStats()
    }

    fun onHeartRateSample(sample: HeartRateSample) {
        latestHeartRate = sample.bpm
        hrSum += sample.bpm
        hrSamples++
        if (_stats.value.state == RideState.RIDING) publishStats()
    }

    /** Call roughly once a second so elapsed time keeps moving between trainer notifications. */
    fun onClockTick() {
        if (_stats.value.state != RideState.RIDING) return
        val now = System.currentTimeMillis()
        lastTickTimestamp?.let { elapsedMillis += now - it }
        lastTickTimestamp = now
        publishStats()
    }

    private fun recordPointIfDue(now: Long) {
        if (now - lastRecordedTimestamp < 1000) return
        lastRecordedTimestamp = now
        val position = positionAt(distanceMeters)
        recordedPoints.add(
            RecordedTrackPoint(
                timestampMillis = now,
                lat = position.lat,
                lon = position.lon,
                elevationMeters = position.elevationMeters,
                heartRateBpm = latestHeartRate,
                cadenceRpm = latestCadence,
            ),
        )
    }

    private fun publishStats() {
        val total = route.totalDistanceMeters
        val clamped = distanceMeters.coerceIn(0.0, total)
        val finished = total > 0 && clamped >= total
        val position = positionAt(clamped)
        val elapsedSecondsValue = elapsedMillis / 1000

        _stats.value = RideStats(
            state = if (finished) RideState.FINISHED else RideState.RIDING,
            distanceMeters = clamped,
            totalDistanceMeters = total,
            progressFraction = if (total > 0) clamped / total else 0.0,
            position = position,
            elapsedSeconds = elapsedSecondsValue,
            currentSpeedMps = latestSpeed,
            currentCadenceRpm = latestCadence,
            currentPowerWatts = latestPower,
            currentHeartRateBpm = latestHeartRate,
            currentGradePercent = gradeAt(clamped),
            avgSpeedMps = if (elapsedSecondsValue > 0) clamped / elapsedSecondsValue else 0.0,
            avgPowerWatts = if (powerSamples > 0) powerSum / powerSamples else null,
            avgCadenceRpm = if (cadenceSamples > 0) cadenceSum / cadenceSamples else null,
            avgHeartRateBpm = if (hrSamples > 0) hrSum / hrSamples else null,
        )
        if (finished) lastTickTimestamp = null
    }

    /** Average grade (%) over a short window centered on [distance], for the trainer's simulated
     * resistance and the ride screen's grade readout. Null if the route has no elevation data. */
    private fun gradeAt(distance: Double): Double? {
        if (route.points.size < 2) return null
        val windowMeters = 30.0
        val total = route.totalDistanceMeters
        val ahead = (distance + windowMeters).coerceAtMost(total)
        val behind = (distance - windowMeters).coerceAtLeast(0.0)
        val run = ahead - behind
        if (run <= 0) return null
        val aheadElevation = positionAt(ahead).elevationMeters ?: return null
        val behindElevation = positionAt(behind).elevationMeters ?: return null
        return (aheadElevation - behindElevation) / run * 100.0
    }

    /** Binary search + linear interpolation of lat/lon/elevation/bearing at [distance] along the route. */
    private fun positionAt(distance: Double): RidePosition {
        val points = route.points
        if (points.isEmpty()) return RidePosition(0.0, 0.0, null, 0.0)
        if (points.size == 1 || distance <= points.first().cumulativeDistanceMeters) {
            val p = points.first()
            val bearing = if (points.size > 1) {
                Haversine.bearingDegrees(p.lat, p.lon, points[1].lat, points[1].lon)
            } else {
                0.0
            }
            return RidePosition(p.lat, p.lon, p.elevationMeters, bearing)
        }
        if (distance >= points.last().cumulativeDistanceMeters) {
            val p = points.last()
            val prev = points[points.size - 2]
            return RidePosition(
                p.lat,
                p.lon,
                p.elevationMeters,
                Haversine.bearingDegrees(prev.lat, prev.lon, p.lat, p.lon),
            )
        }

        var lo = 0
        var hi = points.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (points[mid].cumulativeDistanceMeters <= distance) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val segmentLength = b.cumulativeDistanceMeters - a.cumulativeDistanceMeters
        val t = if (segmentLength > 0) {
            ((distance - a.cumulativeDistanceMeters) / segmentLength).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val lat = a.lat + (b.lat - a.lat) * t
        val lon = a.lon + (b.lon - a.lon) * t
        val elevation = if (a.elevationMeters != null && b.elevationMeters != null) {
            a.elevationMeters + (b.elevationMeters - a.elevationMeters) * t
        } else {
            null
        }
        return RidePosition(lat, lon, elevation, Haversine.bearingDegrees(a.lat, a.lon, b.lat, b.lon))
    }
}
