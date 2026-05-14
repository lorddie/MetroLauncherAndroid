package com.metrolauncher.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.metrolauncher.model.WeatherCondition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Client for Open-Meteo + approximate GPS position reading.
 *
 * WHY OPEN-METEO:
 *  - Free, no API key or registration required
 *  - Accepts lat/lon as query, returns WMO weather code + temperature
 *  - Has a free reverse geocoding endpoint to get the city name
 *
 * STRATEGY:
 *  1. Try to get the last known location from LocationManager (fast, no real GPS wait).
 *     If too old or null, returns null → tile shows "—°".
 *  2. If we have a location, call two endpoints:
 *       - /v1/forecast?latitude&longitude&current=temperature_2m,weather_code,is_day
 *       - /v1/search?latitude&longitude&count=1   (reverse-geocode)
 *  3. The result is cached in SharedPreferences ("weather_last_json") so that on 
 *     the next startup the tile repopulates immediately, even without network — 
 *     the ticker then refreshes when possible.
 *
 * ALL network calls must be invoked from the IO dispatcher — no asynchronous 
 * libraries (OkHttp, Retrofit) are used; we use raw HttpURLConnection to avoid 
 * introducing Gradle dependencies.
 */
object WeatherProvider {

    data class Snapshot(
        val tempC: Double,
        val locationName: String,
        val condition: WeatherCondition,
        val isNight: Boolean,
        val fetchedAtMs: Long,
        val nextCondition: WeatherCondition? = null,
        val nextConditionTime: Long? = null
    )

    private const val PREFS_KEY = "weather_last_json"
    private const val CACHE_MAX_AGE_MS = 6L * 60 * 60 * 1000  // 6 hours
    private const val LOCATION_MAX_AGE_MS = 24L * 60 * 60 * 1000  // 24 hours

    /** Returns true if at least one location permission is granted. */
    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Tries to obtain the current location.
     * 1. Tries the cache (fast).
     * 2. If empty or old, requests a fresh update from the system with a 10s timeout.
     */
    suspend fun getCurrentLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        
        // Step 1: Fast cache
        val cached = lastKnownLocation(context)
        if (cached != null) return cached

        // Step 2: Active request with timeout
        Log.d("WeatherProvider", "Cache empty, requesting fresh location update...")
        return withTimeoutOrNull(10000L) {
            requestFreshLocation(context)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFreshLocation(context: Context): Location? = suspendCancellableCoroutine { continuation ->
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: Modern and clean API
            lm.getCurrentLocation(
                LocationManager.NETWORK_PROVIDER, null, ContextCompat.getMainExecutor(context)
            ) { loc ->
                if (loc != null) continuation.resume(loc)
                else {
                    // Try GPS if Network fails (e.g. no wifi/cell)
                    lm.getCurrentLocation(
                        LocationManager.GPS_PROVIDER, null, ContextCompat.getMainExecutor(context)
                    ) { loc2 -> continuation.resume(loc2) }
                }
            }
        } else {
            // Legacy fallback: SingleUpdate
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }
                override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }
            val handler = Handler(Looper.getMainLooper())
            // Try Network first (fast, works indoors)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, handler.looper)
            } else if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, handler.looper)
            } else {
                continuation.resume(null)
            }
            
            continuation.invokeOnCancellation { lm.removeUpdates(listener) }
        }
    }

    /**
     * Gets the last known location without triggering the GPS.
     */
    @SuppressLint("MissingPermission")
    fun lastKnownLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) {
            Log.d("WeatherProvider", "No location permission")
            return null
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = runCatching { lm.getProviders(true) }.getOrNull() ?: return null
        var best: Location? = null
        for (p in providers) {
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || loc.accuracy < best.accuracy) best = loc
        }
        if (best == null) {
            Log.d("WeatherProvider", "No last known location found from any provider")
            return null
        }
        val age = System.currentTimeMillis() - best.time
        if (age > LOCATION_MAX_AGE_MS) {
            Log.d("WeatherProvider", "Location too old: ${age / 1000 / 60} minutes")
            return null
        }
        Log.d("WeatherProvider", "Using location from ${best.provider}, age ${age / 1000 / 60} min")
        return best
    }

    /**
     * Reads the cache from disk (fallback for first frames at startup, before 
     * the ticker has completed the fetch). Returns null if we've never performed 
     * a fetch or the cache is older than CACHE_MAX_AGE_MS.
     */
    fun cachedSnapshot(context: Context): Snapshot? {
        val json = Prefs.string(context, PREFS_KEY, null) ?: return null
        return try {
            val o = JSONObject(json)
            Snapshot(
                tempC = o.getDouble("temp"),
                locationName = o.getString("loc"),
                condition = WeatherCondition.values()[o.getInt("cond")],
                isNight = o.getBoolean("night"),
                fetchedAtMs = o.getLong("at"),
                nextCondition = if (o.has("next_cond")) WeatherCondition.values()[o.getInt("next_cond")] else null,
                nextConditionTime = if (o.has("next_at")) o.getLong("next_at") else null
            ).let { if (System.currentTimeMillis() - it.fetchedAtMs > CACHE_MAX_AGE_MS) null else it }
        } catch (_: Throwable) { null }
    }

    private fun writeCache(context: Context, s: Snapshot) {
        val o = JSONObject().apply {
            put("temp", s.tempC)
            put("loc", s.locationName)
            put("cond", s.condition.ordinal)
            put("night", s.isNight)
            put("at", s.fetchedAtMs)
            s.nextCondition?.let { put("next_cond", it.ordinal) }
            s.nextConditionTime?.let { put("next_at", it) }
        }
        Prefs.setString(context, PREFS_KEY, o.toString())
    }

    /**
     * Complete fetch: gets position, calls Open-Meteo forecast + reverse geocode, 
     * saves to cache. Returns null if unsuccessful (no position, network error).
     * If customLocation != null, uses textual geocoding instead of GPS.
     * MUST be called from IO dispatcher.
     */
    suspend fun fetchNow(context: Context, customLocation: String? = null): Snapshot? = withContext(Dispatchers.IO) {
        Log.d("WeatherProvider", "Starting weather fetch (custom=$customLocation)...")
        
        val lat: Double
        val lon: Double
        val finalLocName: String

        if (!customLocation.isNullOrBlank()) {
            // Textual geocoding: search lat/lon for the provided name
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${java.net.URLEncoder.encode(customLocation, "UTF-8")}&count=1&language=${Locale.getDefault().language}"
            val geoJson = httpGetJson(geoUrl)
            val results = geoJson?.optJSONArray("results")
            if (results == null || results.length() == 0) {
                Log.d("WeatherProvider", "Geocoding failed for: $customLocation")
                return@withContext null
            }
            val first = results.getJSONObject(0)
            lat = first.getDouble("latitude")
            lon = first.getDouble("longitude")
            finalLocName = first.optString("name", customLocation)
        } else {
            // Automatic GPS fallback
            val loc = getCurrentLocation(context) 
            if (loc == null) {
                Log.d("WeatherProvider", "Fetch aborted: No location fix obtained")
                return@withContext null
            }
            lat = loc.latitude
            lon = loc.longitude
            finalLocName = reverseGeocode(lat, lon) ?: "%.2f, %.2f".format(Locale.US, lat, lon)
        }

        try {
            // Current forecast + hourly (next 24h)
            val fUrl = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${"%.4f".format(Locale.US, lat)}" +
                "&longitude=${"%.4f".format(Locale.US, lon)}" +
                "&current=temperature_2m,weather_code,is_day" +
                "&hourly=weather_code" +
                "&timezone=auto"
            Log.d("WeatherProvider", "Fetching forecast: $fUrl")
            val fJson = httpGetJson(fUrl) ?: return@withContext null
            val current = fJson.optJSONObject("current") ?: return@withContext null
            val tempC = current.optDouble("temperature_2m", Double.NaN)
            if (tempC.isNaN()) return@withContext null
            val wmo = current.optInt("weather_code", -1)
            val isDay = current.optInt("is_day", 1) == 1
            val condition = WeatherCondition.fromWmoCode(wmo)

            // Hourly analysis to find the next change
            var nextCond: WeatherCondition? = null
            var nextTime: Long? = null
            val hourly = fJson.optJSONObject("hourly")
            if (hourly != null) {
                val times = hourly.optJSONArray("time")
                val codes = hourly.optJSONArray("weather_code")
                if (times != null && codes != null) {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
                    val nowMs = System.currentTimeMillis()
                    Log.d("WeatherProvider", "Parsing hourly forecast (${times.length()} entries)...")
                    for (i in 0 until times.length()) {
                        val tStr = times.getString(i)
                        val tMs = sdf.parse(tStr)?.time ?: continue
                        if (tMs <= nowMs) continue
                        val hCond = WeatherCondition.fromWmoCode(codes.getInt(i))
                        Log.d("WeatherProvider", " - Hour $tStr: $hCond")
                        if (hCond != condition) {
                            nextCond = hCond
                            nextTime = tMs
                            Log.d("WeatherProvider", " >> Found change: $hCond at $tStr")
                            break
                        }
                    }
                } else {
                    Log.w("WeatherProvider", "Hourly arrays missing: times=$times, codes=$codes")
                }
            } else {
                Log.w("WeatherProvider", "Hourly object missing in JSON")
            }

            Log.d("WeatherProvider", "Fetch success: $tempC°C, next: $nextCond at $nextTime")

            val snap = Snapshot(
                tempC = tempC, locationName = finalLocName,
                condition = condition, isNight = !isDay,
                fetchedAtMs = System.currentTimeMillis(),
                nextCondition = nextCond,
                nextConditionTime = nextTime
            )
            writeCache(context, snap)
            snap
        } catch (e: Throwable) {
            Log.e("WeatherProvider", "Fetch failed", e)
            null
        }
    }

    /**
     * City suggestion search for autocomplete. Uses Open-Meteo's geocoding-api 
     * which returns up to `count` results ordered by population.
     * Output: list of "Name, State" strings ready to show in a dropdown.
     *
     * Example: query="bolo" → ["Bologna, IT", "Bolobouni, ML", ...]
     *
     * Returns empty list if network is down or query is too short. MUST be called from IO.
     */
    suspend fun searchCitySuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.length < 2) return@withContext emptyList()
        val url = "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=${java.net.URLEncoder.encode(query, "UTF-8")}" +
            "&count=5" +
            "&language=${Locale.getDefault().language}"
        val json = httpGetJson(url) ?: return@withContext emptyList()
        val results = json.optJSONArray("results") ?: return@withContext emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val name = o.optString("name", "")
            val country = o.optString("country_code", "").uppercase(Locale.US)
            val admin = o.optString("admin1", "")
            // Disambiguator: for cities in large countries include the region
            val display = when {
                name.isBlank() -> continue
                country.isNotBlank() && admin.isNotBlank() -> "$name, $admin, $country"
                country.isNotBlank() -> "$name, $country"
                admin.isNotBlank() -> "$name, $admin"
                else -> name
            }
            if (display !in out) out += display
        }
        out
    }

    private fun reverseGeocode(lat: Double, lon: Double): String? {
        // Open-Meteo geocoding search doesn't support "lat,lon" queries. 
        // We use BigDataCloud free reverse geocoder — no key, simple JSON response.
        // If down, returns null and caller falls back.
        val url = "https://api.bigdatacloud.net/data/reverse-geocode-client" +
            "?latitude=${"%.4f".format(Locale.US, lat)}" +
            "&longitude=${"%.4f".format(Locale.US, lon)}" +
            "&localityLanguage=${Locale.getDefault().language}"
        val o = httpGetJson(url) ?: return null
        val city = o.optString("city", "").ifBlank { o.optString("locality", "") }
        val region = o.optString("principalSubdivision", "")
        return when {
            city.isNotBlank() && region.isNotBlank() -> "$city, $region"
            city.isNotBlank() -> city
            region.isNotBlank() -> region
            else -> null
        }
    }

    private fun httpGetJson(urlStr: String): JSONObject? {
        val url = URL(urlStr)
        val conn = (url.openConnection() as? HttpURLConnection) ?: return null
        return try {
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "MetroLauncher/1.0")
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Refresh interval: 1 hour for energy efficiency (user request). */
    const val WEATHER_REFRESH_INTERVAL_MS = 60L * 60 * 1000

    /** Is it night for the local clock (22-06)? Fallback when is_day is unavailable. */
    fun isNightByLocalClock(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h < 6 || h >= 22
    }
}
