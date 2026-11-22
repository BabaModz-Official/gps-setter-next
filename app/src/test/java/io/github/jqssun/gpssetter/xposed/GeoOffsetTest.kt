package io.github.jqssun.gpssetter.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// one degree of latitude is ~111320 m everywhere
private const val METERS_PER_DEG = 111319.49

class GeoOffsetTest {
    @Test fun diagonalMovementMatchesCompassDirectionAndDistance() {
        val (north, east) = GeoOffset.destination(0.0, 0.0, 45f, 10.0)
        val componentDegrees = GeoOffset.latOffsetDegrees(10.0 / Math.sqrt(2.0))
        assertEquals(componentDegrees, north, 1e-10)
        assertEquals(componentDegrees, east, 1e-10)
    }

    @Test fun movementWrapsLongitudeAtTheAntimeridian() {
        val (lat, lng) = GeoOffset.destination(0.0, 179.99999, 90f, 10.0)
        assertEquals(0.0, lat, 1e-10)
        assertTrue(lng in -180.0..-179.999)
    }

    @Test fun latOffsetIsLatitudeIndependent() {
        assertEquals(1.0, GeoOffset.latOffsetDegrees(METERS_PER_DEG), 1e-4)
        assertEquals(0.0, GeoOffset.latOffsetDegrees(0.0), 1e-9)
        assertEquals(-0.5, GeoOffset.latOffsetDegrees(-METERS_PER_DEG / 2), 1e-4)
    }

    @Test fun lngOffsetScalesWithLatitude() {
        // at the equator a degree of longitude spans the same metres as latitude
        assertEquals(1.0, GeoOffset.lngOffsetDegrees(METERS_PER_DEG, 0.0), 1e-4)
        // at 60 deg, cos(60)=0.5, so the same metres cover twice the longitude
        assertEquals(2.0, GeoOffset.lngOffsetDegrees(METERS_PER_DEG, 60.0), 1e-3)
    }

    @Test fun jitterStaysInsideTheRadius() {
        assertEquals(0.0 to 0.0, GeoOffset.jitter(17.0, 0.0, 0.7, 0.3))
        // u=1 puts the point on the rim: v=0 due north, v=0.25 due east
        val (n, _) = GeoOffset.jitter(17.0, 10.0, 1.0, 0.0)
        assertEquals(GeoOffset.latOffsetDegrees(10.0), n, 1e-12)
        val (_, e) = GeoOffset.jitter(60.0, 10.0, 1.0, 0.25)
        assertEquals(GeoOffset.lngOffsetDegrees(10.0, 60.0), e, 1e-12)
        // anywhere else: back in metres, never beyond the radius
        for (u in listOf(0.0, 0.2, 0.5, 0.99)) for (v in listOf(0.1, 0.4, 0.8)) {
            val (dLat, dLng) = GeoOffset.jitter(60.0, 10.0, u, v)
            val north = dLat * METERS_PER_DEG
            val east = dLng * METERS_PER_DEG * 0.5 // cos(60)
            assertTrue(Math.hypot(north, east) <= 10.0 + 1e-6)
        }
    }

    @Test fun driftSimulatorProducesContinuousSmoothBoundedDrift() {
        val sim = GeoOffset.DriftSimulator(maxRadiusMeters = 20.0)
        val rng = java.util.Random(42)
        var lastNorth = 0.0
        var lastEast = 0.0

        for (i in 0 until 50) {
            val (dLat, dLng) = sim.tick(28.6139, 15.0, rng)
            val north = dLat * METERS_PER_DEG
            val east = dLng * METERS_PER_DEG * Math.cos(Math.toRadians(28.6139))
            val dist = Math.hypot(north, east)
            // Must stay bounded within radius
            assertTrue("Drift must stay within target radius: $dist", dist <= 15.0 + 1e-3)
            // Should not exhibit erratic huge leaps (must be continuous)
            if (i > 0) {
                val step = Math.hypot(north - lastNorth, east - lastEast)
                assertTrue("Step must be smooth, got: $step", step < 5.0)
            }
            lastNorth = north
            lastEast = east
        }
    }
}
