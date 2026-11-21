package io.github.jqssun.gpssetter.route

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.jqssun.gpssetter.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// Road route through [stops] in order (A, any via stops, then the destination). Prefers
// OpenRouteService (BuildConfig.ORS_KEY), falls back to resilient OSRM endpoints, and finally to straight lines
// between the stops so the feature always works. Uses HttpURLConnection with browser User-Agent
// so public routing servers do not block mobile Dalvik requests with HTTP 403 Forbidden.
object Routing {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 GPSSetterNext/1.0"

    private val OSRM_ENDPOINTS = listOf(
        "https://router.project-osrm.org/route/v1/driving/",
        "https://routing.openstreetmap.de/routed-car/route/v1/driving/",
        "https://routing.openstreetmap.de/routed-bike/route/v1/driving/",
        "https://routing.openstreetmap.de/routed-foot/route/v1/driving/"
    )

    suspend fun road(stops: List<RoutePoint>): List<RoutePoint> = withContext(Dispatchers.IO) {
        if (stops.size < 2) return@withContext stops

        // 1. Try OpenRouteService if an API key was configured
        if (BuildConfig.ORS_KEY.isNotBlank()) {
            runCatching { ors(stops) }.getOrNull()?.takeIf { it.size >= 2 }?.let { return@withContext it }
        }

        // 2. Try OSRM endpoints with realistic road curves and turns
        for (endpoint in OSRM_ENDPOINTS) {
            val result = runCatching { osrm(endpoint, stops) }.getOrNull()
            if (result != null && result.size >= 2) {
                return@withContext result
            }
        }

        // 3. Fallback to direct stops only if network is completely unreachable
        stops
    }

    private fun ors(stops: List<RoutePoint>): List<RoutePoint> {
        val json = post(
            "https://api.openrouteservice.org/v2/directions/driving-car/geojson",
            """{"coordinates":[${stops.joinToString(",") { "[${it.lng},${it.lat}]" }}]}""",
            mapOf("Authorization" to BuildConfig.ORS_KEY, "Content-Type" to "application/json")
        )
        return coordsFromGeoJson(json, "features")
    }

    private fun osrm(baseEndpoint: String, stops: List<RoutePoint>): List<RoutePoint> =
        coordsFromGeoJson(get(osrmUrl(baseEndpoint, stops)), "routes")

    // default overload kept for test compatibility (RoutingTest)
    internal fun osrmUrl(stops: List<RoutePoint>): String =
        osrmUrl("https://router.project-osrm.org/route/v1/driving/", stops)

    internal fun osrmUrl(baseEndpoint: String, stops: List<RoutePoint>): String =
        baseEndpoint + stops.joinToString(";") { "${it.lng},${it.lat}" } + "?overview=full&geometries=geojson"

    private fun get(url: String): String = open(url, "GET").run {
        try {
            if (responseCode !in 200..299) {
                throw java.io.IOException("HTTP $responseCode from $url")
            }
            inputStream.bufferedReader().use { it.readText() }
        } finally {
            disconnect()
        }
    }

    private fun post(url: String, body: String, headers: Map<String, String>): String =
        open(url, "POST").run {
            try {
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                doOutput = true
                outputStream.use { it.write(body.toByteArray()) }
                if (responseCode !in 200..299) {
                    throw java.io.IOException("HTTP $responseCode from $url")
                }
                inputStream.bufferedReader().use { it.readText() }
            } finally {
                disconnect()
            }
        }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }
}

// Pull geometry.coordinates ([lng,lat] pairs) from the first feature/route of a GeoJSON response.
// Pure, so it's unit-tested. ORS keys it under "features", OSRM under "routes".
@Suppress("DEPRECATION")
fun coordsFromGeoJson(json: String, arrayKey: String): List<RoutePoint> {
    return try {
        val root = JsonParser().parse(json).asJsonObject
        val arr = root.getAsJsonArray(arrayKey) ?: return emptyList()
        if (arr.size() == 0) return emptyList()
        val coords = arr[0].asJsonObject.getAsJsonObject("geometry").getAsJsonArray("coordinates")
        coords.map { el ->
            val c = el.asJsonArray
            RoutePoint(c[1].asDouble, c[0].asDouble)
        }
    } catch (e: Exception) {
        emptyList()
    }
}

// The line in a pasted or imported GeoJSON document: a LineString or MultiLineString, bare or inside a
// Feature or FeatureCollection (every line in it, joined in order). A third coordinate is elevation.
// Empty when there's no line in it (only points, say) or it isn't GeoJSON.
@Suppress("DEPRECATION")
fun lineFromGeoJson(json: String): List<RoutePoint> =
    try { lineOf(JsonParser().parse(json).asJsonObject) } catch (e: Exception) { emptyList() }

// properties.name of the first feature that has one, for naming an imported track
@Suppress("DEPRECATION")
fun nameFromGeoJson(json: String): String? = try {
    val o = JsonParser().parse(json).asJsonObject
    val features = when (o.get("type")?.asString) {
        "FeatureCollection" -> o.getAsJsonArray("features").map { it.asJsonObject }
        "Feature" -> listOf(o)
        else -> emptyList()
    }
    features.firstNotNullOfOrNull { f ->
        f.get("properties")?.takeIf { it.isJsonObject }?.asJsonObject?.get("name")
            ?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
    }
} catch (e: Exception) { null }

private fun lineOf(o: JsonObject): List<RoutePoint> = when (o.get("type")?.asString) {
    "FeatureCollection" -> o.getAsJsonArray("features").flatMap { lineOf(it.asJsonObject) }
    "Feature" -> o.getAsJsonObject("geometry")?.let { lineOf(it) }.orEmpty()
    "LineString" -> positions(o.getAsJsonArray("coordinates"))
    "MultiLineString" -> o.getAsJsonArray("coordinates").flatMap { positions(it.asJsonArray) }
    else -> emptyList()
}

private fun positions(a: JsonArray): List<RoutePoint> = a.map {
    val c = it.asJsonArray
    RoutePoint(c[1].asDouble, c[0].asDouble, if (c.size() > 2) c[2].asDouble else 0.0)
}
