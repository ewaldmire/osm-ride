package com.ewaldmire.osmride.weight

import kotlin.math.log10

/** U.S. Navy circumference method body-fat % estimate. Male formula only - this app is scoped to
 * a single user, per the body metrics feature's own spec, so there's no gender selector to build. */
object NavyBodyFat {
    /** Returns null for physically nonsensical inputs (e.g. neck >= waist) rather than a garbage
     * number - log10 of a non-positive value is undefined. */
    fun estimatePercent(waistCm: Double, neckCm: Double, heightCm: Double): Double? {
        val waistMinusNeck = waistCm - neckCm
        if (waistMinusNeck <= 0 || heightCm <= 0) return null
        val denominator = 1.0324 - 0.19077 * log10(waistMinusNeck) + 0.15456 * log10(heightCm)
        if (denominator <= 0) return null
        return 495.0 / denominator - 450.0
    }
}
