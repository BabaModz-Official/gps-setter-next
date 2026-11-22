package io.github.jqssun.gpssetter.xposed

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Metres -> degrees for the "random position" jitter. Longitude must be scaled by cos(latitude),
// otherwise the east-west jitter grows wrong away from the equator. Pure so it's unit-tested.
object GeoOffset {
    private const val EARTH_RADIUS_M = 6378137.0

    fun latOffsetDegrees(metersNorth: Double): Double =
        Math.toDegrees(metersNorth / EARTH_RADIUS_M)

    fun lngOffsetDegrees(metersEast: Double, atLatDegrees: Double): Double =
        Math.toDegrees(metersEast / (EARTH_RADIUS_M * cos(Math.toRadians(atLatDegrees))))

    fun destination(lat: Double, lng: Double, bearing: Float, meters: Double): Pair<Double, Double> {
        val latitude = Math.toRadians(lat)
        val heading = Math.toRadians(bearing.toDouble())
        val distance = meters / EARTH_RADIUS_M
        val nextLatitude = asin((sin(latitude) * cos(distance) +
            cos(latitude) * sin(distance) * cos(heading)).coerceIn(-1.0, 1.0))
        val longitudeOffset = atan2(
            sin(heading) * sin(distance) * cos(latitude),
            cos(distance) - sin(latitude) * sin(nextLatitude)
        )
        return Math.toDegrees(nextLatitude) to ((lng + Math.toDegrees(longitudeOffset) + 540.0) % 360.0 - 180.0)
    }

    // A uniform random point within [radiusM] of the centre, as (dLat, dLng) degrees.
    // u and v are uniform 0..1 (caller's RNG); sqrt(u) keeps the density even across the disc.
    fun jitter(atLatDegrees: Double, radiusM: Double, u: Double, v: Double): Pair<Double, Double> {
        if (radiusM <= 0.0) return 0.0 to 0.0
        val d = radiusM * sqrt(u)
        val a = 2 * PI * v
        return latOffsetDegrees(d * cos(a)) to lngOffsetDegrees(d * sin(a), atLatDegrees)
    }

    /**
     * Smooth realistic GPS drift simulator using an Ornstein-Uhlenbeck mean-reverting process.
     * Prevents erratic 12 Hz white noise teleporting which anti-spoofing heuristics detect,
     * producing genuine atmospheric/multipath drift with physical momentum.
     */
    class DriftSimulator(private val maxRadiusMeters: Double = 10.0) {
        private var curNorthMeters = 0.0
        private var curEastMeters = 0.0
        private var velNorth = 0.0
        private var velEast = 0.0
        private var lastTimeMs = System.currentTimeMillis()

        @Synchronized
        fun tick(atLatDegrees: Double, targetRadiusM: Double, rng: java.util.Random): Pair<Double, Double> {
            if (targetRadiusM <= 0.0) {
                curNorthMeters = 0.0
                curEastMeters = 0.0
                velNorth = 0.0
                velEast = 0.0
                return 0.0 to 0.0
            }
            val now = System.currentTimeMillis()
            val dt = ((now - lastTimeMs).coerceIn(10L, 1000L)) / 1000.0
            lastTimeMs = now

            // Mean reversion theta: pulls drift gently toward the anchor point
            val theta = 0.35
            // Noise volatility sigma: physical micro-drift in m/s^(1.5)
            val sigma = 0.40

            // Ornstein-Uhlenbeck step for velocity
            val noiseN = rng.nextGaussian() * sigma * sqrt(dt)
            val noiseE = rng.nextGaussian() * sigma * sqrt(dt)

            velNorth += (-theta * curNorthMeters * dt) + noiseN
            velEast += (-theta * curEastMeters * dt) + noiseE

            // Apply friction/drag to velocity
            velNorth *= (1.0 - 0.25 * dt)
            velEast *= (1.0 - 0.25 * dt)

            curNorthMeters += velNorth * dt
            curEastMeters += velEast * dt

            // Clamp within target radius
            val dist = Math.hypot(curNorthMeters, curEastMeters)
            val maxR = targetRadiusM.coerceAtMost(maxRadiusMeters)
            if (dist > maxR && dist > 0.0) {
                val scale = maxR / dist
                curNorthMeters *= scale
                curEastMeters *= scale
                velNorth = -velNorth * 0.5
                velEast = -velEast * 0.5
            }

            return latOffsetDegrees(curNorthMeters) to lngOffsetDegrees(curEastMeters, atLatDegrees)
        }
    }
}
