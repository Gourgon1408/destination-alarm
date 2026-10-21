package com.destinationalarm.placesearch

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal object ArrivalRules {
    private const val EARTH_RADIUS_METRES = 6_371_000.0

    fun maximumAccuracyMetres(radiusMetres: Double): Double = minOf(250.0, radiusMetres * 0.5)

    fun invalidFixReason(
        latitude: Double,
        longitude: Double,
        ageMillis: Long,
        maximumAgeMillis: Long,
    ): String? = when {
        ageMillis < 0L || ageMillis > maximumAgeMillis -> "stale_fix"
        !latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0 -> "invalid_coordinates"
        else -> null
    }

    fun distanceMetres(latitude: Double, longitude: Double, destinationLatitude: Double, destinationLongitude: Double): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(destinationLatitude)
        val deltaLat = Math.toRadians(destinationLatitude - latitude)
        val deltaLon = Math.toRadians(destinationLongitude - longitude)
        val a = (sin(deltaLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)).coerceIn(0.0, 1.0)
        return EARTH_RADIUS_METRES * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

/** Persists only the one-way arrival edge for an individual journey. */
internal class ArrivalGate(
    private val radiusMetres: Double,
    alreadyArrived: Boolean = false,
) {
    private var insideFixes = 0
    val confirmationCount: Int
        get() = insideFixes
    var hasArrived: Boolean = alreadyArrived
        private set

    data class Check(
        val confirmed: Boolean,
        val confirmationCount: Int,
        val reason: String,
    )

    fun update(distanceMetres: Double, accuracyMetres: Double = 0.0): Check {
        if (hasArrived) return Check(false, insideFixes, "arrival_already_confirmed")
        if (!distanceMetres.isFinite() || distanceMetres < 0.0) {
            return resetConfirmation("invalid_distance")
        }
        val maximumAccuracy = ArrivalRules.maximumAccuracyMetres(radiusMetres)
        if (accuracyMetres < 0.0) {
            return resetConfirmation("invalid_accuracy")
        }
        if (!accuracyMetres.isFinite()) {
            return Check(false, insideFixes, "accuracy_unavailable_ignored")
        }
        if (accuracyMetres > maximumAccuracy) {
            return Check(false, insideFixes, "inaccurate_fix_ignored")
        }
        if (distanceMetres > radiusMetres) {
            return resetConfirmation("outside_radius")
        }
        insideFixes++
        if (insideFixes >= 2) {
            hasArrived = true
            return Check(true, insideFixes, "two_accurate_fixes_inside_radius")
        }
        return Check(false, insideFixes, "awaiting_second_accurate_fix")
    }

    fun resetConfirmation(reason: String): Check {
        insideFixes = 0
        return Check(false, insideFixes, "confirmation_reset_$reason")
    }
}
