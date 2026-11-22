package io.github.jqssun.gpssetter.utils

import android.location.Location
import java.util.Locale

object CooldownHelper {

    // Standard anti-cheat cooldown table based on distance
    fun calculateCooldownSeconds(distanceMeters: Double): Long {
        val km = distanceMeters / 1000.0
        return when {
            km < 0.25 -> 0L
            km < 1.0 -> 30L
            km < 3.0 -> 60L       // 1 min
            km < 5.0 -> 120L      // 2 min
            km < 10.0 -> 420L     // 7 min
            km < 25.0 -> 660L     // 11 min
            km < 50.0 -> 1200L    // 20 min
            km < 100.0 -> 1800L   // 30 min
            km < 250.0 -> 2700L   // 45 min
            km < 500.0 -> 3600L   // 60 min
            km < 1000.0 -> 5400L  // 90 min
            else -> 7200L         // 120 min (2 hours max)
        }
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

    fun onTeleport(newLat: Double, newLon: Double) {
        val lastLat = PrefManager.lastTeleportLat
        val lastLon = PrefManager.lastTeleportLng
        val now = System.currentTimeMillis()

        if (lastLat != 0.0 || lastLon != 0.0) {
            val dist = distanceMeters(lastLat, lastLon, newLat, newLon)
            val cdSeconds = calculateCooldownSeconds(dist)
            PrefManager.lastTeleportDistance = dist
            PrefManager.cooldownExpiryTime = now + (cdSeconds * 1000L)
        }

        PrefManager.lastTeleportLat = newLat
        PrefManager.lastTeleportLng = newLon
        PrefManager.lastTeleportTime = now
    }

    fun getRemainingCooldownMillis(): Long {
        val expiry = PrefManager.cooldownExpiryTime
        val now = System.currentTimeMillis()
        return (expiry - now).coerceAtLeast(0L)
    }

    fun formatRemaining(remainingMillis: Long): String {
        if (remainingMillis <= 0L) return "Safe"
        val totalSecs = (remainingMillis + 999L) / 1000L
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return if (mins > 0) {
            String.format(Locale.US, "%dm %02ds", mins, secs)
        } else {
            String.format(Locale.US, "%ds", secs)
        }
    }
}
