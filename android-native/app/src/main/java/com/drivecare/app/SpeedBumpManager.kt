package com.drivecare.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.*

/**
 * Lightweight, server-driven Speed Bump and Road Hazard Manager for DriveCare.
 * 
 * Key Principles:
 * 1. 20 km Dynamic City Cache: Only holds bumps for the immediate ~20 km sector.
 * 2. Strict Kinematic Speed Gate: Bump recordings strictly rejected if speed > 21 km/h.
 * 3. Pattern Recognition: Deceleration (<21 km/h) -> +Z Shock -> Pitch/Inversion -> -Z Rebound -> Accel.
 * 4. Adaptive Community Verification: New bumps activate immediately; auto-deactivate after 3 negative passes.
 * 5. Audio Proximity Warning: 100m early alert + 20m final alert, toggleable mute.
 */
class SpeedBumpManager(
    private val context: Context,
    private val apiBaseUrlProvider: () -> String
) : SensorEventListener {

    companion object {
        private const val TAG = "SpeedBumpManager"
        private const val EARTH_RADIUS_METERS = 6371000.0
        private const val CACHE_REFRESH_DISTANCE_METERS = 5000.0 // 5 km move triggers 20km sector refresh
        private const val SPEED_GATE_MAX_KMH = 21.0              // Hard limit: > 21 km/h never records a bump
        private const val SPEED_GATE_MIN_KMH = 3.5               // Car must be moving
        private const val EARLY_WARNING_METERS = 100.0
        private const val FINAL_WARNING_METERS = 25.0
        private const val PASS_CHECK_WINDOW_MS = 6000L
    }

    data class CachedHazard(
        val id: String,
        val lat: Double,
        val lng: Double,
        val directionDegrees: Double,
        val bidirectional: Boolean,
        val roadClass: String
    )

    // Public state
    var isWarningEnabled: Boolean = true
    var isAudioMuted: Boolean = false
    var isMuted: Boolean
        get() = isAudioMuted
        set(value) { isAudioMuted = value }

    val alertSoundPlayer: AlertSoundPlayer = AlertSoundPlayer(context)

    var currentWarningText: String? = null
        private set
    var onWarningStateChanged: ((warningText: String?, stage: String?) -> Unit)? = null
    var onBumpDetectedNotice: ((message: String) -> Unit)? = null

    // Cache & state
    private val cachedHazards = CopyOnWriteArrayList<CachedHazard>()
    private var lastFetchLocation: Location? = null
    private val isFetching = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Proximity deduplication
    private var lastAlertedHazardId: String? = null
    private var lastAlertStage: String? = null
    private var lastAlertTimeMs: Long = 0L

    // Pass-check tracker: hazards currently within <= 25m
    private val activePassHazards = mutableMapOf<String, Long>() // id -> entryTimeMs

    // Kinematic detection window
    private var lastRecordedBumpTimeMs: Long = 0L
    private var positiveShockTimeMs: Long = 0L
    private var hasPositiveShock = false
    private var hasPitchRotation = false
    private var hasNegativeRebound = false
    private var lastBumpFeltTimeMs: Long = 0L

    // Current vehicle telemetry
    private var latestGpsLocation: Location? = null
    private var latestGpsSpeedKmh: Double = 0.0
    private var latestObdSpeedKmh: Double? = null

    // Audio & Sensors
    private var toneGenerator: ToneGenerator? = null
    private var sensorManager: SensorManager? = null

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator not available: ${e.message}")
        }
    }

    fun start() {
        try {
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            sensorManager?.let { sm ->
                val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                if (accel != null) {
                    sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
                }
                val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                if (gyro != null) {
                    sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
                }
            }
            Log.i(TAG, "SpeedBumpManager started with sensor listeners")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sensor listeners: ${e.message}")
        }
    }

    fun stop() {
        try {
            sensorManager?.unregisterListener(this)
            toneGenerator?.release()
            toneGenerator = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping SpeedBumpManager: ${e.message}")
        }
    }

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        return isMuted
    }

    fun toggleWarning(): Boolean {
        isWarningEnabled = !isWarningEnabled
        if (!isWarningEnabled) {
            clearWarningState()
        }
        return isWarningEnabled
    }

    /**
     * Called whenever a new GPS fix arrives.
     * Evaluates 20km sector refresh, proximity warnings, and pass-checks.
     */
    fun onLocationChanged(location: Location, obdSpeedKmh: Double? = null) {
        latestGpsLocation = location
        latestGpsSpeedKmh = if (location.hasSpeed()) location.speed * 3.6 else 0.0
        latestObdSpeedKmh = obdSpeedKmh

        val carSpeed = getEffectiveSpeedKmh()

        // 1. Dynamic 20km City Cache refresh check
        val lastLoc = lastFetchLocation
        if (lastLoc == null || distanceMeters(location.latitude, location.longitude, lastLoc.latitude, lastLoc.longitude) >= CACHE_REFRESH_DISTANCE_METERS) {
            fetchNearbyHazards(location.latitude, location.longitude, 20.0)
        }

        // 2. Proximity warning check
        checkProximityWarnings(location, carSpeed)

        // 3. Pass check validation (adaptive verification / negative pass)
        checkPassHazards(location, carSpeed)
    }

    /**
     * Returns effective speed: prefers ECU OBD wheel speed if available, otherwise GPS.
     */
    fun getEffectiveSpeedKmh(): Double {
        val obd = latestObdSpeedKmh
        return if (obd != null && obd >= 0.0) obd else latestGpsSpeedKmh
    }

    /**
     * Evaluates cached hazards ahead of the vehicle.
     */
    private fun checkProximityWarnings(currentLoc: Location, speedKmh: Double) {
        if (!isWarningEnabled || cachedHazards.isEmpty()) {
            clearWarningState()
            return
        }

        val carBearing = currentLoc.bearing.toDouble()
        var nearestWarning: Pair<CachedHazard, Double>? = null // Hazard to distance

        for (hazard in cachedHazards) {
            val dist = distanceMeters(currentLoc.latitude, currentLoc.longitude, hazard.lat, hazard.lng)
            if (dist > EARLY_WARNING_METERS + 10.0) continue

            // Bearing from car to hazard
            val bearingToHazard = initialBearingDegrees(currentLoc.latitude, currentLoc.longitude, hazard.lat, hazard.lng)

            // Only warn if hazard is ahead of the car (within 45 degrees of travel direction)
            if (currentLoc.hasBearing() && speedKmh > 5.0) {
                if (angularDifference(carBearing, bearingToHazard) > 45.0) continue
                // Also check road heading direction if not bidirectional
                if (!hazard.bidirectional && angularDifference(carBearing, hazard.directionDegrees) > 55.0) continue
            }

            if (nearestWarning == null || dist < nearestWarning.second) {
                nearestWarning = Pair(hazard, dist)
            }
        }

        if (nearestWarning != null) {
            val (hazard, dist) = nearestWarning
            val stage = if (dist <= FINAL_WARNING_METERS) "FINAL" else "EARLY"
            val text = if (stage == "FINAL") "🛑 مطب الآن (${dist.toInt()}م)" else "⚠️ مطب بعد ${dist.toInt()}م"

            // Check if we need to emit audio alert
            val now = System.currentTimeMillis()
            val isNewStage = (hazard.id != lastAlertedHazardId || stage != lastAlertStage)
            if (isNewStage || (now - lastAlertTimeMs > 4000L)) {
                lastAlertedHazardId = hazard.id
                lastAlertStage = stage
                lastAlertTimeMs = now
                playProximityAudio(stage)
            }

            updateWarningState(text, stage)
        } else {
            clearWarningState()
        }
    }

    private fun playProximityAudio(stage: String) {
        if (!isWarningEnabled || isAudioMuted) return
        try {
            if (stage == "FINAL") {
                alertSoundPlayer.playBump25m()
            } else {
                alertSoundPlayer.playBump100m()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Natural voice alert failed: ${e.message}")
        }
    }

    private fun updateWarningState(text: String, stage: String) {
        currentWarningText = text
        mainHandler.post {
            onWarningStateChanged?.invoke(text, stage)
        }
    }

    private fun clearWarningState() {
        if (currentWarningText != null) {
            currentWarningText = null
            mainHandler.post {
                onWarningStateChanged?.invoke(null, null)
            }
        }
    }

    /**
     * Checks if the car has entered and then exited the 25m zone of a bump.
     * Emits pass-check (positive or negative) to the server.
     */
    private fun checkPassHazards(currentLoc: Location, speedKmh: Double) {
        val now = System.currentTimeMillis()

        // 1. Detect entering <= 25m
        for (hazard in cachedHazards) {
            val dist = distanceMeters(currentLoc.latitude, currentLoc.longitude, hazard.lat, hazard.lng)
            if (dist <= FINAL_WARNING_METERS) {
                if (!activePassHazards.containsKey(hazard.id)) {
                    activePassHazards[hazard.id] = now
                }
            }
        }

        // 2. Detect exiting > 35m after entering
        val iterator = activePassHazards.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val hId = entry.key
            val hazard = cachedHazards.firstOrNull { it.id == hId }
            if (hazard != null) {
                val dist = distanceMeters(currentLoc.latitude, currentLoc.longitude, hazard.lat, hazard.lng)
                if (dist > 35.0) {
                    // Car passed the bump!
                    val feltBump = (now - lastBumpFeltTimeMs <= PASS_CHECK_WINDOW_MS)
                    sendPassCheckAsync(hId, feltBump, speedKmh)
                    iterator.remove()
                }
            } else {
                iterator.remove()
            }
        }
    }

    /**
     * Sensor Event Listener: Kinematic Speed Bump Detection
     * Strict Speed Gate: Speed MUST be < 21 km/h ($5.83 m/s).
     */
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        val speed = getEffectiveSpeedKmh()

        // Strict User Rule: If vehicle speed > 21 km/h, NEVER record a bump!
        if (speed > SPEED_GATE_MAX_KMH || speed < SPEED_GATE_MIN_KMH) {
            // Reset kinematic state
            hasPositiveShock = false
            hasPitchRotation = false
            hasNegativeRebound = false
            return
        }

        val now = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Vertical acceleration (Z-axis corrected for gravity ~ 9.8)
                val zRaw = event.values[2]
                val zShock = zRaw - 9.81f

                // 1. Positive upward shock (+Z > 2.8 m/s^2)
                if (zShock > 2.8f && !hasPositiveShock) {
                    hasPositiveShock = true
                    positiveShockTimeMs = now
                }

                // 2. Downward rebound (-Z < -1.6 m/s^2) following positive shock
                if (hasPositiveShock && (now - positiveShockTimeMs <= 1400L)) {
                    if (zShock < -1.6f) {
                        hasNegativeRebound = true
                    }
                }

                // 3. Check full sequence completion
                if (hasPositiveShock && hasNegativeRebound) {
                    // Kinematic bump confirmed!
                    lastBumpFeltTimeMs = now
                    hasPositiveShock = false
                    hasNegativeRebound = false
                    hasPitchRotation = false

                    // If not reported recently (10 sec cooldown), report as newly detected bump
                    if (now - lastRecordedBumpTimeMs > 10000L) {
                        lastRecordedBumpTimeMs = now
                        latestGpsLocation?.let { loc ->
                            reportNewBumpAsync(loc.latitude, loc.longitude, loc.bearing.toDouble())
                        }
                    }
                }

                // Window expiry
                if (hasPositiveShock && (now - positiveShockTimeMs > 1500L)) {
                    hasPositiveShock = false
                    hasNegativeRebound = false
                }
            }

            Sensor.TYPE_GYROSCOPE -> {
                // Pitch rate (rotation around X-axis in rad/s)
                val pitchRate = abs(event.values[0])
                if (hasPositiveShock && pitchRate > 0.25f) {
                    hasPitchRotation = true
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Downloads bumps within 20 km of the vehicle's location from the server.
     */
    private fun fetchNearbyHazards(lat: Double, lng: Double, radiusKm: Double) {
        if (!isFetching.compareAndSet(false, true)) return

        thread(name = "FetchHazardsThread") {
            try {
                val baseUrl = apiBaseUrlProvider().trimEnd('/')
                val urlString = "$baseUrl/api/hazards/nearby?lat=$lat&lng=$lng&radius_km=$radiusKm"
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "GET"

                if (conn.responseCode == 200) {
                    val raw = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(raw)
                    val arr = json.optJSONArray("hazards") ?: JSONArray()
                    val newHazards = mutableListOf<CachedHazard>()

                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        newHazards.add(
                            CachedHazard(
                                id = obj.optString("id"),
                                lat = obj.optDouble("lat"),
                                lng = obj.optDouble("lng"),
                                directionDegrees = obj.optDouble("dir", 0.0),
                                bidirectional = obj.optBoolean("bi", false),
                                roadClass = obj.optString("road", "unknown")
                            )
                        )
                    }

                    cachedHazards.clear()
                    cachedHazards.addAll(newHazards)
                    lastFetchLocation = Location("").apply {
                        latitude = lat
                        longitude = lng
                    }
                    Log.i(TAG, "Cached ${newHazards.size} hazards within ${radiusKm}km sector around ($lat, $lng)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch nearby hazards: ${e.message}")
            } finally {
                isFetching.set(false)
            }
        }
    }

    /**
     * Emits a newly detected bump to the server and adds it immediately to the local cache.
     */
    private fun reportNewBumpAsync(lat: Double, lng: Double, bearing: Double) {
        thread(name = "ReportBumpThread") {
            try {
                val baseUrl = apiBaseUrlProvider().trimEnd('/')
                val url = URL("$baseUrl/api/hazards")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

                val payload = JSONObject().apply {
                    put("latitude", lat)
                    put("longitude", lng)
                    put("direction_degrees", bearing)
                    put("bidirectional", false)
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }

                if (conn.responseCode in 200..201) {
                    val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val newId = resp.optString("id", "temp-${System.currentTimeMillis()}")
                    
                    // Add immediately to local cache so driver has it right away
                    cachedHazards.add(
                        CachedHazard(
                            id = newId,
                            lat = lat,
                            lng = lng,
                            directionDegrees = bearing,
                            bidirectional = false,
                            roadClass = "reported"
                        )
                    )

                    mainHandler.post {
                        onBumpDetectedNotice?.invoke("تم رصد وتوثيق مطب جديد ✅")
                        if (!isMuted) {
                            try {
                                toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 250)
                            } catch (_: Exception) {}
                        }
                    }
                    Log.i(TAG, "Successfully reported new bump at ($lat, $lng): $newId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error reporting new bump: ${e.message}")
            }
        }
    }

    /**
     * Reports a pass observation to the server:
     * If feltBump == false, increments negative pass.
     * (Server deactivates if >= 3 negative passes occur).
     */
    private fun sendPassCheckAsync(hazardId: String, feltBump: Boolean, speedKmh: Double) {
        thread(name = "PassCheckThread") {
            try {
                val baseUrl = apiBaseUrlProvider().trimEnd('/')
                val url = URL("$baseUrl/api/hazards/pass-check")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

                val payload = JSONObject().apply {
                    put("hazard_id", hazardId)
                    put("felt_bump", feltBump)
                    put("speed_kmh", speedKmh)
                }

                OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(payload.toString()) }

                if (conn.responseCode == 200) {
                    val resp = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val status = resp.optString("status")
                    if (status == "DEACTIVATED") {
                        // Remove from local cache immediately
                        cachedHazards.removeAll { it.id == hazardId }
                        Log.i(TAG, "Hazard $hazardId deactivated by community verification and removed from cache")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error sending pass check: ${e.message}")
            }
        }
    }

    // Geometry Helpers
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dphi = Math.toRadians(lat2 - lat1)
        val dlambda = Math.toRadians(lon2 - lon1)
        val a = sin(dphi / 2.0).pow(2.0) + cos(phi1) * cos(phi2) * sin(dlambda / 2.0).pow(2.0)
        return EARTH_RADIUS_METERS * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }

    private fun initialBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dlambda = Math.toRadians(lon2 - lon1)
        val y = sin(dlambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dlambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun angularDifference(first: Double, second: Double): Double {
        val diff = abs(first - second) % 360.0
        return min(diff, 360.0 - diff)
    }
}
